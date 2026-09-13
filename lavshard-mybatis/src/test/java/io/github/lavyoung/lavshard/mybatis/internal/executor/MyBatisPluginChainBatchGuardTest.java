package io.github.lavyoung.lavshard.mybatis.internal.executor;

import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import io.github.lavyoung.lavshard.core.internal.algorithm.ShardAlgorithmRegistry;
import io.github.lavyoung.lavshard.core.internal.route.SqlRouteEngine;
import io.github.lavyoung.lavshard.mybatis.internal.routing.MyBatisRouteContext;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 MyBatis Executor、CachingExecutor 和 Plugin 代理验证批执行边界。
 * 不连接数据库；事务探针记录是否越过连接获取边界。
 */
class MyBatisPluginChainBatchGuardTest {
    private static final String ROUTING_SENTINEL = "route planning reached";
    private static final String CONNECTION_SENTINEL = "physical connection reached";

    @ParameterizedTest(name = "{0}")
    @MethodSource("batchCases")
    void shouldRejectBatchBeforeRoutingAndConnection(Scenario scenario) {
        // Given
        Harness harness = harness(scenario, ExecutorType.BATCH, true);

        // When / Then
        assertThatThrownBy(() -> invoke(harness, scenario.entry()))
                .isInstanceOf(UnsupportedSqlException.class)
                .hasMessage("MyBatis ExecutorType.BATCH is not supported in v0.1");
        assertThat(harness.snapshotReads().get()).isZero();
        assertThat(harness.transaction().attempts).isZero();
        assertThat(harness.context().currentDecision()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allowedCases")
    void shouldAllowSimpleAndReuseToReachRoutePlanning(AllowedScenario scenario) {
        // Given
        Harness harness = harness(new Scenario(scenario.entry(), true, true, 2), scenario.type(), true);

        // When / Then: a deliberate sentinel stops before SQL routing, not a BATCH rejection.
        assertThatThrownBy(() -> invoke(harness, scenario.entry()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(ROUTING_SENTINEL);
        assertThat(harness.snapshotReads().get()).isEqualTo(1);
        assertThat(harness.transaction().attempts).isZero();
        assertThat(harness.context().currentDecision()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("entries")
    void shouldLeaveOutOfScopeBatchToMyBatis(Entry entry) {
        // Given: every plugin must forward the call when this mapper is out of scope.
        Harness harness = harness(new Scenario(entry, true, true, 2), ExecutorType.BATCH, false);

        // When / Then: reaching the transaction proves LavShard did not reject or route it.
        assertThatThrownBy(() -> invoke(harness, entry))
                .isInstanceOf(SQLException.class)
                .hasMessage(CONNECTION_SENTINEL);
        assertThat(harness.snapshotReads().get()).isZero();
        assertThat(harness.transaction().attempts).isEqualTo(1);
        assertThat(harness.context().currentDecision()).isEmpty();
    }

    private static Stream<Scenario> batchCases() {
        return entries().flatMap(entry -> Stream.of(false, true).flatMap(cache ->
                Stream.of(false, true).flatMap(outer -> Stream.of(1, 3)
                        .map(depth -> new Scenario(entry, cache, outer, depth)))));
    }

    private static Stream<AllowedScenario> allowedCases() {
        return entries().flatMap(entry -> Stream.of(ExecutorType.SIMPLE, ExecutorType.REUSE)
                .map(type -> new AllowedScenario(entry, type)));
    }

    private static Stream<Entry> entries() {
        return Stream.of(Entry.QUERY_FOUR, Entry.QUERY_SIX, Entry.UPDATE, Entry.CURSOR);
    }

    /**
     * 按 MyBatis 的配置流程构造缓存包装和插件链。
     *
     * @param scenario 入口和插件排列
     * @param type     执行器类型
     * @param managed  Mapper 是否属于管理范围
     * @return 用于断言路由和连接访问的测试环境
     * @throws IllegalStateException 测试进入路由阶段时由探针抛出
     */
    private static Harness harness(Scenario scenario, ExecutorType type, boolean managed) {
        Configuration configuration = new Configuration();
        configuration.setCacheEnabled(scenario.cache());
        ConnectionProbe transaction = new ConnectionProbe();
        AtomicInteger reads = new AtomicInteger();
        MyBatisRouteContext context = new MyBatisRouteContext();
        LavShardExecutorInterceptor interceptor = new LavShardExecutorInterceptor(
                new SqlRouteEngine(ShardAlgorithmRegistry.withBuiltInAlgorithms(), Set.of(), "ds0"),
                () -> {
                    reads.incrementAndGet();
                    throw new IllegalStateException(ROUTING_SENTINEL);
                },
                context,
                managed ? MyBatisIntegrationScope.all() : MyBatisIntegrationScope.of(List.of("other.mapper"))
        );
        if (!scenario.lavShardOutermost()) {
            configuration.addInterceptor(interceptor);
        }
        for (int layer = 0; layer < scenario.depth(); layer++) {
            configuration.addInterceptor(new ForwardingPlugin());
        }
        if (scenario.lavShardOutermost()) {
            configuration.addInterceptor(interceptor);
        }
        Executor executor = configuration.newExecutor(transaction, type);
        boolean update = scenario.entry() == Entry.UPDATE;
        String sql = update ? "INSERT INTO ordinary_table (id) VALUES (1)" : "SELECT 1";
        MappedStatement statement = new MappedStatement.Builder(configuration,
                "managed.mapper.OrderMapper.execute", new StaticSqlSource(configuration, sql),
                update ? SqlCommandType.INSERT : SqlCommandType.SELECT).build();
        return new Harness(executor, statement, transaction, reads, context);
    }

    private static void invoke(Harness harness, Entry entry) throws SQLException {
        switch (entry) {
            case QUERY_FOUR -> harness.executor().query(harness.statement(), null, RowBounds.DEFAULT, null);
            case QUERY_SIX -> harness.executor().query(harness.statement(), null, RowBounds.DEFAULT,
                    null, new CacheKey(), harness.statement().getBoundSql(null));
            case UPDATE -> harness.executor().update(harness.statement(), null);
            case CURSOR -> harness.executor().queryCursor(harness.statement(), null, RowBounds.DEFAULT);
        }
    }

    private enum Entry {QUERY_FOUR, QUERY_SIX, UPDATE, CURSOR}

    private record Scenario(Entry entry, boolean cache, boolean lavShardOutermost, int depth) {
    }

    private record AllowedScenario(Entry entry, ExecutorType type) {
    }

    private record Harness(Executor executor, MappedStatement statement, ConnectionProbe transaction,
                           AtomicInteger snapshotReads, MyBatisRouteContext context) {
    }

    @Intercepts({
            @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
            @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class, CacheKey.class, BoundSql.class}),
            @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
            @Signature(type = Executor.class, method = "queryCursor", args = {MappedStatement.class, Object.class, RowBounds.class})
    })
    private static final class ForwardingPlugin implements Interceptor {
        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            return invocation.proceed();
        }
    }

    private static final class ConnectionProbe implements Transaction {
        private int attempts;

        @Override
        public Connection getConnection() throws SQLException {
            attempts++;
            throw new SQLException(CONNECTION_SENTINEL);
        }

        @Override
        public void commit() {
        }

        @Override
        public void rollback() {
        }

        @Override
        public void close() {
        }

        @Override
        public Integer getTimeout() {
            return 0;
        }
    }
}
