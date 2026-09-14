package io.github.lavyoung.lavshard.mybatis.internal.executor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.exception.MissingShardKeyException;
import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import io.github.lavyoung.lavshard.core.api.route.ManagedRouteDecision;
import io.github.lavyoung.lavshard.core.api.route.PassThroughDecision;
import io.github.lavyoung.lavshard.core.api.route.SqlRouteDecision;
import io.github.lavyoung.lavshard.core.api.rule.RuleSnapshot;
import io.github.lavyoung.lavshard.core.api.rule.TableRule;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.api.topology.ShardNode;
import io.github.lavyoung.lavshard.core.api.topology.ShardTopology;
import io.github.lavyoung.lavshard.core.internal.algorithm.ShardAlgorithmRegistry;
import io.github.lavyoung.lavshard.core.internal.route.SqlRouteEngine;
import io.github.lavyoung.lavshard.mybatis.internal.routing.MyBatisRouteContext;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.BatchExecutor;
import org.apache.ibatis.executor.BatchResult;
import org.apache.ibatis.executor.CachingExecutor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MyBatis Executor 路由拦截器契约。
 *
 * <p>拦截器必须在 CacheKey 创建前完成参数提取、路由、SQL 改写和
 * MappedStatement 重建，并在执行期间绑定路由上下文。</p>
 */
class LavShardExecutorInterceptorTest {

    private static final QualifiedTableName ORDER_TABLE =
            new QualifiedTableName("t_order");

    private final Configuration configuration =
            new Configuration();

    private final MyBatisRouteContext routeContext =
            new MyBatisRouteContext();

    private final SqlRouteEngine routeEngine =
            new SqlRouteEngine(
                    ShardAlgorithmRegistry
                            .withBuiltInAlgorithms(),
                    Set.of(
                            new QualifiedTableName("sys_dict")
                    ),
                    "ds-default"
            );

    private final LavShardExecutorInterceptor interceptor =
            new LavShardExecutorInterceptor(
                    routeEngine,
                    LavShardExecutorInterceptorTest::snapshot,
                    routeContext
            );

    @Test
    void shouldRouteFourArgumentQueryBeforeCacheKeyCreation()
            throws SQLException {
        RecordingExecutor target =
                new RecordingExecutor(routeContext);
        Executor executor = plugin(target);
        MappedStatement logical = statement(
                "OrderMapper.selectByUserId",
                SqlCommandType.SELECT,
                "SELECT * FROM t_order WHERE user_id = ?",
                "userId"
        );

        List<Object> result = executor.query(
                logical,
                Map.of("userId", "user-123"),
                RowBounds.DEFAULT,
                Executor.NO_RESULT_HANDLER
        );

        assertThat(result).isEmpty();
        assertThat(target.fourArgumentQueryCount)
                .isZero();
        assertThat(target.sixArgumentQueryCount)
                .isEqualTo(1);
        assertThat(target.queryRecord.boundSql().getSql())
                .isEqualTo(
                        "SELECT * FROM t_order_00 "
                                + "WHERE user_id = ?"
                );
        assertThat(target.queryRecord.mappedStatement())
                .isNotSameAs(logical);
        assertThat(target.queryRecord.cacheKey())
                .isSameAs(target.createdCacheKey);
        assertThat(target.queryRecord.decision())
                .isInstanceOf(
                        ManagedRouteDecision.class
                );
        assertThat(routeContext.currentDecision())
                .isEmpty();
    }

    @Test
    void shouldRebuildProvidedSixArgumentQueryCacheKey()
            throws SQLException {
        RecordingExecutor target =
                new RecordingExecutor(routeContext);
        Executor executor = plugin(target);
        MappedStatement logical = statement(
                "OrderMapper.selectByUserId",
                SqlCommandType.SELECT,
                "SELECT * FROM t_order WHERE user_id = ?",
                "userId"
        );
        Object parameter = Map.of(
                "userId",
                "user-123"
        );
        BoundSql logicalBoundSql =
                logical.getBoundSql(parameter);
        CacheKey staleLogicalKey =
                new CacheKey(new Object[]{"logical"});

        executor.query(
                logical,
                parameter,
                RowBounds.DEFAULT,
                Executor.NO_RESULT_HANDLER,
                staleLogicalKey,
                logicalBoundSql
        );

        assertThat(target.queryRecord.boundSql().getSql())
                .contains("t_order_00");
        assertThat(target.queryRecord.cacheKey())
                .isNotSameAs(staleLogicalKey)
                .isSameAs(target.createdCacheKey);
        assertThat(routeContext.currentDecision())
                .isEmpty();
    }

    @Test
    void shouldPassThroughOrdinaryQueryOnDefaultDataSource()
            throws SQLException {
        RecordingExecutor target =
                new RecordingExecutor(routeContext);
        Executor executor = plugin(target);
        MappedStatement logical = statement(
                "DictionaryMapper.selectByType",
                SqlCommandType.SELECT,
                "SELECT * FROM sys_dict WHERE type = ?",
                "type"
        );

        executor.query(
                logical,
                Map.of("type", "ORDER_STATUS"),
                RowBounds.DEFAULT,
                Executor.NO_RESULT_HANDLER
        );

        assertThat(target.queryRecord.boundSql().getSql())
                .isEqualTo(
                        "SELECT * FROM sys_dict WHERE type = ?"
                );
        assertThat(target.queryRecord.decision())
                .isInstanceOfSatisfying(
                        PassThroughDecision.class,
                        decision -> assertThat(
                                decision.dataSourceId()
                        ).isEqualTo("ds-default")
                );
        assertThat(routeContext.currentDecision())
                .isEmpty();
    }

    @Test
    void shouldRouteUpdateWithPhysicalMappedStatement()
            throws SQLException {
        RecordingExecutor target =
                new RecordingExecutor(routeContext);
        Executor executor = plugin(target);
        MappedStatement logical = statement(
                "OrderMapper.markPaid",
                SqlCommandType.UPDATE,
                "UPDATE t_order SET status = 'PAID' "
                        + "WHERE user_id = ?",
                "userId"
        );

        int affected = executor.update(
                logical,
                Map.of("userId", "user-123")
        );

        assertThat(affected).isEqualTo(1);
        assertThat(target.updateRecord.boundSql().getSql())
                .isEqualTo(
                        "UPDATE t_order_00 SET status = 'PAID' "
                                + "WHERE user_id = ?"
                );
        assertThat(target.updateRecord.decision())
                .isInstanceOf(
                        ManagedRouteDecision.class
                );
        assertThat(routeContext.currentDecision())
                .isEmpty();
    }

    @Test
    void shouldRouteCursorQuery() throws SQLException {
        RecordingExecutor target =
                new RecordingExecutor(routeContext);
        Executor executor = plugin(target);
        MappedStatement logical = statement(
                "OrderMapper.scanByUserId",
                SqlCommandType.SELECT,
                "SELECT * FROM t_order WHERE user_id = ?",
                "userId"
        );

        executor.queryCursor(
                logical,
                Map.of("userId", "user-123"),
                RowBounds.DEFAULT
        );

        assertThat(target.cursorRecord.boundSql().getSql())
                .contains("t_order_00");
        assertThat(target.cursorRecord.decision())
                .isInstanceOf(
                        ManagedRouteDecision.class
                );
        assertThat(routeContext.currentDecision())
                .isEmpty();
    }

    @Test
    void shouldRejectUnsafeManagedSqlBeforeExecutor()
            throws SQLException {
        RecordingExecutor target =
                new RecordingExecutor(routeContext);
        Executor executor = plugin(target);
        MappedStatement unsafe = statement(
                "OrderMapper.selectByStatus",
                SqlCommandType.SELECT,
                "SELECT * FROM t_order WHERE status = ?",
                "status"
        );

        assertThatThrownBy(() -> executor.query(
                unsafe,
                Map.of("status", "PAID"),
                RowBounds.DEFAULT,
                Executor.NO_RESULT_HANDLER
        )).isInstanceOf(MissingShardKeyException.class);

        assertThat(target.fourArgumentQueryCount)
                .isZero();
        assertThat(target.sixArgumentQueryCount)
                .isZero();
        assertThat(routeContext.currentDecision())
                .isEmpty();
    }

    @Test
    void shouldLogManagedAndPassThroughRoutesWithoutSqlOrParameterValues()
            throws SQLException {
        Logger logger = (Logger) LoggerFactory.getLogger(
                LavShardExecutorInterceptor.class
        );
        Level originalLevel = logger.getLevel();
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);

        try {
            RecordingExecutor target = new RecordingExecutor(routeContext);
            Executor executor = plugin(target);
            MappedStatement managed = statement(
                    "OrderMapper.selectSecret",
                    SqlCommandType.SELECT,
                    "SELECT * FROM t_order WHERE user_id = ?",
                    "userId"
            );
            MappedStatement passThrough = statement(
                    "DictionaryMapper.selectSecret",
                    SqlCommandType.SELECT,
                    "SELECT * FROM sys_dict WHERE type = ?",
                    "type"
            );

            executor.query(
                    managed,
                    Map.of("userId", "sensitive-user-123"),
                    RowBounds.DEFAULT,
                    Executor.NO_RESULT_HANDLER
            );
            executor.query(
                    passThrough,
                    Map.of("type", "sensitive-dictionary-value"),
                    RowBounds.DEFAULT,
                    Executor.NO_RESULT_HANDLER
            );

            List<String> messages = appender.list.stream()
                    .map(event -> event.getFormattedMessage())
                    .toList();
            assertThat(messages).anySatisfy(message -> assertThat(message)
                    .contains(
                            "statementId=OrderMapper.selectSecret",
                            "decision=MANAGED",
                            "logicalTable=QualifiedTableName",
                            "dataSourceId=ds0",
                            "actualTable=QualifiedTableName",
                            "ruleVersion=order-rule-v1",
                            "topologyVersion=order-topology-v1"
                    ));
            assertThat(messages).anySatisfy(message -> assertThat(message)
                    .contains(
                            "statementId=DictionaryMapper.selectSecret",
                            "decision=PASSTHROUGH",
                            "dataSourceId=ds-default"
                    ));
            assertThat(String.join("\n", messages))
                    .doesNotContain(
                            "sensitive-user-123",
                            "sensitive-dictionary-value",
                            "SELECT * FROM"
                    );
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }
    }

    @Test
    void shouldRejectManagedUpdateForBatchExecutorBeforeConnectionAccess() {
        CountingTransaction transaction = new CountingTransaction();
        Executor executor = (Executor) interceptor.plugin(
                new BatchExecutor(configuration, transaction)
        );
        MappedStatement managed = statement(
                "OrderMapper.insert",
                SqlCommandType.INSERT,
                "INSERT INTO t_order (user_id) VALUES (?)",
                "userId"
        );

        assertThatThrownBy(() -> executor.update(
                managed,
                Map.of("userId", "user-123")
        ))
                .isInstanceOf(UnsupportedSqlException.class)
                .hasMessage(
                        "MyBatis ExecutorType.BATCH is not supported in v0.1"
                );

        assertThat(transaction.connectionAttempts).isZero();
        assertThat(routeContext.currentDecision()).isEmpty();
    }

    @Test
    void shouldRejectPassThroughUpdateForWrappedBatchExecutorBeforeConnectionAccess() {
        CountingTransaction transaction = new CountingTransaction();
        Executor batchExecutor = new BatchExecutor(
                configuration,
                transaction
        );
        Executor executor = (Executor) interceptor.plugin(
                new CachingExecutor(batchExecutor)
        );
        MappedStatement passThrough = statement(
                "DictionaryMapper.insert",
                SqlCommandType.INSERT,
                "INSERT INTO sys_dict (type) VALUES (?)",
                "type"
        );

        assertThatThrownBy(() -> executor.update(
                passThrough,
                Map.of("type", "ORDER_STATUS")
        ))
                .isInstanceOf(UnsupportedSqlException.class)
                .hasMessage(
                        "MyBatis ExecutorType.BATCH is not supported in v0.1"
                );

        assertThat(transaction.connectionAttempts).isZero();
        assertThat(routeContext.currentDecision()).isEmpty();
    }

    @Test
    void shouldRejectQueryForBatchExecutorBeforeConnectionAccess() {
        CountingTransaction transaction = new CountingTransaction();
        Executor executor = (Executor) interceptor.plugin(
                new BatchExecutor(configuration, transaction)
        );
        MappedStatement query = statement(
                "OrderMapper.selectByUserId",
                SqlCommandType.SELECT,
                "SELECT * FROM t_order WHERE user_id = ?",
                "userId"
        );

        assertThatThrownBy(() -> executor.query(
                query,
                Map.of("userId", "user-123"),
                RowBounds.DEFAULT,
                Executor.NO_RESULT_HANDLER
        ))
                .isInstanceOf(UnsupportedSqlException.class)
                .hasMessage(
                        "MyBatis ExecutorType.BATCH is not supported in v0.1"
                );

        assertThat(transaction.connectionAttempts).isZero();
        assertThat(routeContext.currentDecision()).isEmpty();
    }

    @Test
    void shouldCompletelyBypassEveryExecutorEntryOutsideManagedScope()
            throws SQLException {
        AtomicInteger snapshotRequests = new AtomicInteger();
        LavShardExecutorInterceptor scopedInterceptor =
                new LavShardExecutorInterceptor(
                        routeEngine,
                        () -> {
                            snapshotRequests.incrementAndGet();
                            return snapshot();
                        },
                        routeContext,
                        MyBatisIntegrationScope.of(Set.of(
                                "com.acme.managed.mapper"
                        ))
                );
        RecordingExecutor target = new RecordingExecutor(routeContext);
        Executor executor = (Executor) scopedInterceptor.plugin(target);
        MappedStatement query = statement(
                "com.acme.legacy.mapper.OrderMapper.select",
                SqlCommandType.SELECT,
                "SELECT * FROM t_order WHERE status = ?",
                "status"
        );
        MappedStatement update = statement(
                "com.acme.legacy.mapper.OrderMapper.update",
                SqlCommandType.UPDATE,
                "UPDATE t_order SET status = ?",
                "status"
        );
        BoundSql logicalBoundSql = query.getBoundSql(
                Map.of("status", "PAID")
        );
        CacheKey logicalCacheKey = new CacheKey(
                new Object[]{"logical"}
        );

        executor.query(
                query,
                Map.of("status", "PAID"),
                RowBounds.DEFAULT,
                Executor.NO_RESULT_HANDLER
        );
        executor.query(
                query,
                Map.of("status", "PAID"),
                RowBounds.DEFAULT,
                Executor.NO_RESULT_HANDLER,
                logicalCacheKey,
                logicalBoundSql
        );
        executor.update(update, Map.of("status", "PAID"));
        executor.queryCursor(
                query,
                Map.of("status", "PAID"),
                RowBounds.DEFAULT
        );

        assertThat(snapshotRequests).hasValue(0);
        assertThat(target.fourArgumentQueryCount).isEqualTo(1);
        assertThat(target.sixArgumentQueryCount).isEqualTo(1);
        assertThat(target.queryRecord.mappedStatement()).isSameAs(query);
        assertThat(target.queryRecord.boundSql()).isSameAs(logicalBoundSql);
        assertThat(target.queryRecord.cacheKey()).isSameAs(logicalCacheKey);
        assertThat(target.queryRecord.decision()).isNull();
        assertThat(target.updateRecord.mappedStatement()).isSameAs(update);
        assertThat(target.updateRecord.boundSql().getSql())
                .isEqualTo("UPDATE t_order SET status = ?");
        assertThat(target.updateRecord.decision()).isNull();
        assertThat(target.cursorRecord.mappedStatement()).isSameAs(query);
        assertThat(target.cursorRecord.boundSql().getSql())
                .isEqualTo("SELECT * FROM t_order WHERE status = ?");
        assertThat(target.cursorRecord.decision()).isNull();
        assertThat(routeContext.currentDecision()).isEmpty();
    }

    @Test
    void shouldRouteStatementInsideManagedScope() throws SQLException {
        LavShardExecutorInterceptor scopedInterceptor =
                new LavShardExecutorInterceptor(
                        routeEngine,
                        LavShardExecutorInterceptorTest::snapshot,
                        routeContext,
                        MyBatisIntegrationScope.of(Set.of(
                                "com.acme.order.mapper"
                        ))
                );
        RecordingExecutor target = new RecordingExecutor(routeContext);
        Executor executor = (Executor) scopedInterceptor.plugin(target);
        MappedStatement managed = statement(
                "com.acme.order.mapper.OrderMapper.selectByUserId",
                SqlCommandType.SELECT,
                "SELECT * FROM t_order WHERE user_id = ?",
                "userId"
        );

        executor.query(
                managed,
                Map.of("userId", "user-123"),
                RowBounds.DEFAULT,
                Executor.NO_RESULT_HANDLER
        );

        assertThat(target.fourArgumentQueryCount).isZero();
        assertThat(target.sixArgumentQueryCount).isEqualTo(1);
        assertThat(target.queryRecord.boundSql().getSql())
                .contains("t_order_00");
        assertThat(target.queryRecord.decision())
                .isInstanceOf(ManagedRouteDecision.class);
    }

    @Test
    void shouldApplyBatchRejectionOnlyInsideManagedScope() {
        CountingTransaction transaction = new CountingTransaction();
        LavShardExecutorInterceptor scopedInterceptor =
                new LavShardExecutorInterceptor(
                        routeEngine,
                        LavShardExecutorInterceptorTest::snapshot,
                        routeContext,
                        MyBatisIntegrationScope.of(Set.of(
                                "com.acme.managed.mapper"
                        ))
                );
        Executor executor = (Executor) scopedInterceptor.plugin(
                new BatchExecutor(configuration, transaction)
        );
        MappedStatement outsideScope = statement(
                "com.acme.legacy.mapper.LegacyMapper.update",
                SqlCommandType.UPDATE,
                "UPDATE legacy_table SET value = ?",
                "value"
        );

        assertThatThrownBy(() -> executor.update(
                outsideScope,
                Map.of("value", "new")
        ))
                .isInstanceOf(SQLException.class)
                .hasMessage("Physical connection was accessed");

        assertThat(transaction.connectionAttempts).isEqualTo(1);
        assertThat(routeContext.currentDecision()).isEmpty();
    }

    @Test
    void shouldClearRouteContextWhenExecutorFails() {
        RecordingExecutor target =
                new RecordingExecutor(routeContext);
        target.failQuery = true;
        Executor executor = plugin(target);
        MappedStatement logical = statement(
                "OrderMapper.selectByUserId",
                SqlCommandType.SELECT,
                "SELECT * FROM t_order WHERE user_id = ?",
                "userId"
        );

        assertThatThrownBy(() -> executor.query(
                logical,
                Map.of("userId", "user-123"),
                RowBounds.DEFAULT,
                Executor.NO_RESULT_HANDLER
        ))
                .isInstanceOf(SQLException.class)
                .hasMessage("database unavailable");

        assertThat(target.queryRecord.decision())
                .isInstanceOf(
                        ManagedRouteDecision.class
                );
        assertThat(routeContext.currentDecision())
                .isEmpty();
    }

    @Test
    void shouldRejectNullSnapshotReturnedBySupplier() {
        LavShardExecutorInterceptor invalidInterceptor =
                new LavShardExecutorInterceptor(
                        routeEngine,
                        () -> null,
                        routeContext
                );
        Executor executor = (Executor)
                invalidInterceptor.plugin(
                        new RecordingExecutor(routeContext)
                );
        MappedStatement logical = statement(
                "DictionaryMapper.selectOne",
                SqlCommandType.SELECT,
                "SELECT 1",
                null
        );

        assertThatThrownBy(() -> executor.query(
                logical,
                null,
                RowBounds.DEFAULT,
                Executor.NO_RESULT_HANDLER
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage(
                        "snapshotSupplier returned null"
                );
    }

    @Test
    void shouldRejectInvalidConstructorArguments() {
        assertThatThrownBy(() ->
                new LavShardExecutorInterceptor(
                        null,
                        LavShardExecutorInterceptorTest::snapshot,
                        routeContext
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage(
                        "routeEngine must not be null"
                );

        assertThatThrownBy(() ->
                new LavShardExecutorInterceptor(
                        routeEngine,
                        null,
                        routeContext
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage(
                        "snapshotSupplier must not be null"
                );

        assertThatThrownBy(() ->
                new LavShardExecutorInterceptor(
                        routeEngine,
                        LavShardExecutorInterceptorTest::snapshot,
                        null
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage(
                        "routeContext must not be null"
                );

        assertThatThrownBy(() ->
                new LavShardExecutorInterceptor(
                        routeEngine,
                        LavShardExecutorInterceptorTest::snapshot,
                        routeContext,
                        null
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage(
                        "integrationScope must not be null"
                );
    }

    private Executor plugin(RecordingExecutor target) {
        return (Executor) interceptor.plugin(target);
    }

    private MappedStatement statement(
            String id,
            SqlCommandType commandType,
            String sql,
            String parameterProperty
    ) {
        List<ParameterMapping> mappings =
                parameterProperty == null
                        ? List.of()
                        : List.of(
                        new ParameterMapping.Builder(
                                configuration,
                                parameterProperty,
                                Object.class
                        ).build()
                );

        return new MappedStatement.Builder(
                configuration,
                id,
                parameter -> new BoundSql(
                        configuration,
                        sql,
                        mappings,
                        parameter
                ),
                commandType
        ).build();
    }

    private static RuleSnapshot snapshot() {
        ShardNode node = new ShardNode(
                "order-node",
                "ds0",
                new QualifiedTableName("t_order_00")
        );

        Map<Integer, String> placements =
                new HashMap<>();

        for (int bucket = 0; bucket < 1024; bucket++) {
            placements.put(bucket, node.nodeId());
        }

        ShardTopology topology = new ShardTopology(
                "order-topology-v1",
                1024,
                placements,
                Map.of(node.nodeId(), node)
        );

        TableRule rule = new TableRule(
                "order-rule-v1",
                ORDER_TABLE,
                "user_id",
                "hash_mod",
                new AlgorithmConfig(
                        1024,
                        "murmur3_32_v1"
                ),
                topology
        );

        return new RuleSnapshot(List.of(rule));
    }

    private record ExecutionRecord(
            MappedStatement mappedStatement,
            BoundSql boundSql,
            CacheKey cacheKey,
            SqlRouteDecision decision
    ) {
    }

    private static final class CountingTransaction
            implements Transaction {

        private int connectionAttempts;

        @Override
        public Connection getConnection() throws SQLException {
            connectionAttempts++;
            throw new SQLException("Physical connection was accessed");
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

    private static final class RecordingExecutor
            implements Executor {

        private final MyBatisRouteContext routeContext;

        private int fourArgumentQueryCount;
        private int sixArgumentQueryCount;
        private boolean failQuery;
        private CacheKey createdCacheKey;
        private ExecutionRecord queryRecord;
        private ExecutionRecord updateRecord;
        private ExecutionRecord cursorRecord;

        private RecordingExecutor(
                MyBatisRouteContext routeContext
        ) {
            this.routeContext = routeContext;
        }

        @Override
        public int update(
                MappedStatement mappedStatement,
                Object parameter
        ) {
            BoundSql boundSql =
                    mappedStatement.getBoundSql(parameter);
            updateRecord = record(
                    mappedStatement,
                    boundSql,
                    null
            );
            return 1;
        }

        @Override
        public <E> List<E> query(
                MappedStatement mappedStatement,
                Object parameter,
                RowBounds rowBounds,
                ResultHandler resultHandler,
                CacheKey cacheKey,
                BoundSql boundSql
        ) throws SQLException {
            sixArgumentQueryCount++;
            queryRecord = record(
                    mappedStatement,
                    boundSql,
                    cacheKey
            );

            if (failQuery) {
                throw new SQLException(
                        "database unavailable"
                );
            }

            return List.of();
        }

        @Override
        public <E> List<E> query(
                MappedStatement mappedStatement,
                Object parameter,
                RowBounds rowBounds,
                ResultHandler resultHandler
        ) {
            fourArgumentQueryCount++;
            return List.of();
        }

        @Override
        public <E> Cursor<E> queryCursor(
                MappedStatement mappedStatement,
                Object parameter,
                RowBounds rowBounds
        ) {
            BoundSql boundSql =
                    mappedStatement.getBoundSql(parameter);
            cursorRecord = record(
                    mappedStatement,
                    boundSql,
                    null
            );
            return null;
        }

        @Override
        public List<BatchResult> flushStatements() {
            return List.of();
        }

        @Override
        public void commit(boolean required) {
        }

        @Override
        public void rollback(boolean required) {
        }

        @Override
        public CacheKey createCacheKey(
                MappedStatement mappedStatement,
                Object parameterObject,
                RowBounds rowBounds,
                BoundSql boundSql
        ) {
            CacheKey cacheKey = new CacheKey();
            cacheKey.update(mappedStatement.getId());
            cacheKey.update(rowBounds.getOffset());
            cacheKey.update(rowBounds.getLimit());
            cacheKey.update(boundSql.getSql());
            createdCacheKey = cacheKey;
            return cacheKey;
        }

        @Override
        public boolean isCached(
                MappedStatement mappedStatement,
                CacheKey cacheKey
        ) {
            return false;
        }

        @Override
        public void clearLocalCache() {
        }

        @Override
        public void deferLoad(
                MappedStatement mappedStatement,
                MetaObject resultObject,
                String property,
                CacheKey key,
                Class<?> targetType
        ) {
        }

        @Override
        public Transaction getTransaction() {
            return null;
        }

        @Override
        public void close(boolean forceRollback) {
        }

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public void setExecutorWrapper(
                Executor executor
        ) {
        }

        private ExecutionRecord record(
                MappedStatement mappedStatement,
                BoundSql boundSql,
                CacheKey cacheKey
        ) {
            return new ExecutionRecord(
                    mappedStatement,
                    boundSql,
                    cacheKey,
                    routeContext.currentDecision()
                            .orElse(null)
            );
        }
    }
}
