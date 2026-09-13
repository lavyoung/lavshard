package io.github.lavyoung.lavshard.integration;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardValue;
import io.github.lavyoung.lavshard.core.api.exception.CrossShardTransactionException;
import io.github.lavyoung.lavshard.core.api.rule.RuleSnapshot;
import io.github.lavyoung.lavshard.core.api.rule.TableRule;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.api.topology.ShardNode;
import io.github.lavyoung.lavshard.core.api.topology.ShardTopology;
import io.github.lavyoung.lavshard.core.internal.algorithm.Murmur3HashShardAlgorithm;
import io.github.lavyoung.lavshard.core.internal.algorithm.ShardAlgorithmRegistry;
import io.github.lavyoung.lavshard.core.internal.route.SqlRouteEngine;
import io.github.lavyoung.lavshard.mybatis.internal.executor.LavShardExecutorInterceptor;
import io.github.lavyoung.lavshard.mybatis.internal.routing.LavShardRoutingDataSource;
import io.github.lavyoung.lavshard.mybatis.internal.routing.MyBatisRouteContext;
import io.github.lavyoung.lavshard.starter.internal.transaction.SpringShardContext;
import org.apache.ibatis.annotations.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实一级/二级缓存的命中、写后失效及事务安全契约。
 * JDBC 探针计数执行 SELECT 的次数，而不是只计数连接或 Statement 创建。
 */
class MyBatisCacheTransactionTest {
    private static final AlgorithmConfig ALGORITHM_CONFIG =
            new AlgorithmConfig(2, Murmur3HashShardAlgorithm.HASH_VERSION);
    private CountingDataSource ds0;
    private CountingDataSource ds1;
    private JdbcTemplate jdbc0;
    private JdbcTemplate jdbc1;
    private MyBatisRouteContext routeContext;
    private TransactionTemplate transaction;
    private AtomicReference<RuleSnapshot> snapshots;
    private String user0;
    private String user1;

    @BeforeEach
    void setUp() {
        ds0 = dataSource();
        ds1 = dataSource();
        jdbc0 = new JdbcTemplate(ds0.delegate);
        jdbc1 = new JdbcTemplate(ds1.delegate);
        user0 = userIdForBucket(0);
        user1 = userIdForBucket(1);
        schema(jdbc0, user0, "ds0-original");
        schema(jdbc1, user1, "ds1-original");
        snapshots = new AtomicReference<>(snapshot("rule-v1", "topology-v1"));
        SpringShardContext guard = new SpringShardContext();
        routeContext = new MyBatisRouteContext(guard::validate);
    }

    @AfterEach
    void cleanUp() {
        try {
            assertThat(routeContext.currentDecision()).isEmpty();
            assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        } finally {
            jdbc0.execute("SHUTDOWN");
            jdbc1.execute("SHUTDOWN");
        }
    }

    @ParameterizedTest
    @EnumSource(value = ExecutorType.class, names = {"SIMPLE", "REUSE"})
    void shouldHitSessionCacheAndInvalidateItAfterUpdate(ExecutorType type) {
        // Given: disable L2 so only L1 can explain a cache hit.
        OrderMapper mapper = mapper(type, false, LocalCacheScope.SESSION);

        // When / Then
        transaction.executeWithoutResult(status -> {
            assertThat(mapper.note(user0)).isEqualTo("ds0-original");
            assertThat(mapper.note(user0)).isEqualTo("ds0-original");
            assertThat(ds0.selects).isEqualTo(1);
            assertThat(mapper.update(user0, "changed")).isEqualTo(1);
            assertThat(mapper.note(user0)).isEqualTo("changed");
            assertThat(ds0.selects).isEqualTo(2);
        });
    }

    @ParameterizedTest
    @EnumSource(value = ExecutorType.class, names = {"SIMPLE", "REUSE"})
    void shouldExecuteAgainWithStatementLocalCacheScope(ExecutorType type) {
        // Given
        OrderMapper mapper = mapper(type, false, LocalCacheScope.STATEMENT);

        // When
        transaction.executeWithoutResult(status -> {
            assertThat(mapper.note(user0)).isEqualTo("ds0-original");
            assertThat(mapper.note(user0)).isEqualTo("ds0-original");
        });

        // Then: REUSE still executes twice even if it reuses the PreparedStatement.
        assertThat(ds0.selects).isEqualTo(2);
        assertThat(ds0.connections).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = ExecutorType.class, names = {"SIMPLE", "REUSE"})
    void shouldHitSecondLevelCacheAcrossTransactionsWithoutPhysicalConnection(ExecutorType type) {
        // Given
        OrderMapper mapper = mapper(type, true, LocalCacheScope.SESSION);
        transaction.executeWithoutResult(status -> assertThat(mapper.note(user0)).isEqualTo("ds0-original"));
        int connections = ds0.connections;

        // When
        transaction.executeWithoutResult(status -> assertThat(mapper.note(user0)).isEqualTo("ds0-original"));

        // Then
        assertThat(ds0.selects).isEqualTo(1);
        assertThat(ds0.connections).isEqualTo(connections);
    }

    @ParameterizedTest
    @EnumSource(value = ExecutorType.class, names = {"SIMPLE", "REUSE"})
    void shouldInvalidateSecondLevelCacheAfterCommittedUpdate(ExecutorType type) {
        // Given
        OrderMapper mapper = mapper(type, true, LocalCacheScope.SESSION);
        assertThat(mapper.note(user0)).isEqualTo("ds0-original");

        // When
        transaction.executeWithoutResult(status -> assertThat(mapper.update(user0, "committed")).isEqualTo(1));

        // Then
        assertThat(mapper.note(user0)).isEqualTo("committed");
        assertThat(mapper.note(user0)).isEqualTo("committed");
        assertThat(ds0.selects).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(value = ExecutorType.class, names = {"SIMPLE", "REUSE"})
    void shouldNotPublishUncommittedValueAfterRollback(ExecutorType type) {
        // Given
        OrderMapper mapper = mapper(type, true, LocalCacheScope.SESSION);
        assertThat(mapper.note(user0)).isEqualTo("ds0-original");

        // When
        transaction.executeWithoutResult(status -> {
            mapper.update(user0, "must-not-cache");
            assertThat(mapper.note(user0)).isEqualTo("must-not-cache");
            status.setRollbackOnly();
        });

        // Then: database and a new session both see the committed value.
        assertThat(jdbc0.queryForObject("SELECT note FROM t_order_00", String.class)).isEqualTo("ds0-original");
        assertThat(mapper.note(user0)).isEqualTo("ds0-original");
        assertThat(ds0.selects).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(value = ExecutorType.class, names = {"SIMPLE", "REUSE"})
    void shouldRejectCrossShardEvenWhenOtherShardIsAlreadyCached(ExecutorType type) {
        // Given
        OrderMapper mapper = mapper(type, true, LocalCacheScope.SESSION);
        assertThat(mapper.note(user0)).isEqualTo("ds0-original");
        assertThat(mapper.note(user1)).isEqualTo("ds1-original");
        int before0 = ds0.connections;
        int beforeConnections = ds1.connections;

        // When / Then
        transaction.executeWithoutResult(status -> {
            assertThat(mapper.note(user0)).isEqualTo("ds0-original");
            assertThatThrownBy(() -> mapper.note(user1))
                    .hasRootCauseInstanceOf(CrossShardTransactionException.class);
            assertThat(mapper.note(user0)).isEqualTo("ds0-original");
        });
        assertThat(ds1.connections).isEqualTo(beforeConnections);
        assertThat(ds0.connections).isEqualTo(before0);
        assertThat(ds0.selects).isEqualTo(1);
        assertThat(ds1.selects).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = ExecutorType.class, names = {"SIMPLE", "REUSE"})
    void shouldInvalidateWholeMapperNamespaceAfterOneShardWrite(ExecutorType type) {
        // Given: both shards use one MyBatis Mapper namespace cache.
        OrderMapper mapper = mapper(type, true, LocalCacheScope.SESSION);
        assertThat(mapper.note(user0)).isEqualTo("ds0-original");
        assertThat(mapper.note(user1)).isEqualTo("ds1-original");

        // When
        transaction.executeWithoutResult(status -> mapper.update(user0, "changed"));

        // Then: standard MyBatis invalidation is namespace-wide, not shard-local.
        assertThat(mapper.note(user1)).isEqualTo("ds1-original");
        assertThat(ds1.selects).isEqualTo(2);
        assertThat(mapper.note(user0)).isEqualTo("changed");
        assertThat(ds0.selects).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(value = ExecutorType.class, names = {"SIMPLE", "REUSE"})
    void shouldKeepDifferentShardsSeparateInSameMapperCache(ExecutorType type) {
        // Given
        OrderMapper mapper = mapper(type, true, LocalCacheScope.SESSION);

        // When / Then: identical physical table names, different shard parameters.
        assertThat(mapper.note(user0)).isEqualTo("ds0-original");
        assertThat(mapper.note(user1)).isEqualTo("ds1-original");
        assertThat(mapper.note(user0)).isEqualTo("ds0-original");
        assertThat(mapper.note(user1)).isEqualTo("ds1-original");
        assertThat(ds0.selects).isEqualTo(1);
        assertThat(ds1.selects).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versionCases")
    void shouldMissOldCacheWhenOnlyRouteVersionChanges(VersionCase scenario) {
        // Given: same mapper, SQL, parameters and dataSource; only a route version changes.
        OrderMapper mapper = mapper(scenario.type(), true, LocalCacheScope.SESSION);
        assertThat(mapper.note(user0)).isEqualTo("ds0-original");
        jdbc0.update("UPDATE t_order_00 SET note = 'new-version-value'");

        // When: controlled supplier replacement tests the key contract, not a production refresh feature.
        snapshots.set(snapshot(scenario.ruleVersion(), scenario.topologyVersion()));

        // Then
        assertThat(mapper.note(user0)).isEqualTo("new-version-value");
        assertThat(mapper.note(user0)).isEqualTo("new-version-value");
        assertThat(ds0.selects).isEqualTo(2);
    }

    private static Stream<VersionCase> versionCases() {
        return Stream.of(ExecutorType.SIMPLE, ExecutorType.REUSE).flatMap(type -> Stream.of(
                new VersionCase(type, "rule-v2", "topology-v1"),
                new VersionCase(type, "rule-v1", "topology-v2")));
    }

    /**
     * 为每个用例创建独立配置和 Mapper 缓存，避免测试之间共享缓存数据。
     *
     * @param type  执行器类型
     * @param cache 是否开启二级缓存
     * @param scope 一级缓存作用域
     * @return 已装配路由插件的 Mapper
     * @throws RuntimeException 框架装配失败时抛出
     */
    private OrderMapper mapper(ExecutorType type, boolean cache, LocalCacheScope scope) {
        DataSource routing = new LavShardRoutingDataSource(Map.of("ds0", ds0, "ds1", ds1), routeContext);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(routing));
        Configuration configuration = new Configuration(new Environment("cache-test",
                new SpringManagedTransactionFactory(), routing));
        configuration.setCacheEnabled(cache);
        configuration.setLocalCacheScope(scope);
        configuration.addInterceptor(new LavShardExecutorInterceptor(
                new SqlRouteEngine(ShardAlgorithmRegistry.withBuiltInAlgorithms(), Set.of(), "ds0"),
                snapshots::get, routeContext));
        configuration.addMapper(OrderMapper.class);
        return new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration), type)
                .getMapper(OrderMapper.class);
    }

    private static CountingDataSource dataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:cache-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        return new CountingDataSource(ds);
    }

    private static void schema(JdbcTemplate jdbc, String userId, String note) {
        jdbc.execute("""
                CREATE TABLE t_order_00 (
                    user_id VARCHAR(64) PRIMARY KEY,
                    note VARCHAR(64) NOT NULL
                )
                """);
        jdbc.update("INSERT INTO t_order_00 (user_id, note) VALUES (?, ?)", userId, note);
    }

    private static RuleSnapshot snapshot(String ruleVersion, String topologyVersion) {
        ShardNode node0 = new ShardNode(
                "order-node-0",
                "ds0",
                new QualifiedTableName("t_order_00")
        );
        ShardNode node1 = new ShardNode(
                "order-node-1",
                "ds1",
                new QualifiedTableName("t_order_00")
        );
        ShardTopology topology = new ShardTopology(
                topologyVersion,
                2,
                Map.of(
                        0, node0.nodeId(),
                        1, node1.nodeId()
                ),
                Map.of(
                        node0.nodeId(), node0,
                        node1.nodeId(), node1
                )
        );
        TableRule rule = new TableRule(
                ruleVersion,
                new QualifiedTableName("t_order"),
                "user_id",
                Murmur3HashShardAlgorithm.NAME,
                ALGORITHM_CONFIG,
                topology
        );
        return new RuleSnapshot(List.of(rule));
    }

    private static String userIdForBucket(
            int expectedBucket
    ) {
        Murmur3HashShardAlgorithm algorithm =
                new Murmur3HashShardAlgorithm();

        for (int candidate = 0; candidate < 100; candidate++) {
            String userId = "user-" + candidate;
            int bucket = algorithm.calculate(
                    ShardValue.of(userId),
                    ALGORITHM_CONFIG
            ).value();

            if (bucket == expectedBucket) {
                return userId;
            }
        }

        throw new IllegalStateException(
                "No user id found for bucket "
                        + expectedBucket
        );
    }


    private record VersionCase(ExecutorType type, String ruleVersion, String topologyVersion) {
    }

    @CacheNamespace(readWrite = false)
    private interface OrderMapper {
        @Select("SELECT note FROM t_order WHERE user_id = #{userId}")
        String note(@Param("userId") String userId);

        @Update("UPDATE t_order SET note = #{note} WHERE user_id = #{userId}")
        @Options(flushCache = Options.FlushCachePolicy.TRUE)
        int update(@Param("userId") String userId, @Param("note") String note);
    }

    private static final class CountingDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private int connections;
        private int selects;

        private CountingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return track(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return track(delegate.getConnection(username, password));
        }

        private Connection track(Connection connection) {
            connections++;
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        Object value = call(connection, method, args);
                        if (value instanceof PreparedStatement statement && args != null
                                && args.length > 0 && args[0] instanceof String sql
                                && sql.stripLeading().regionMatches(true, 0, "SELECT", 0, 6)) {
                            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                    new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                                        if (m.getName().equals("execute") || m.getName().equals("executeQuery")) {
                                            selects++;
                                        }
                                        return call(statement, m, a);
                                    });
                        }
                        return value;
                    });
        }

        private static Object call(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }
}
