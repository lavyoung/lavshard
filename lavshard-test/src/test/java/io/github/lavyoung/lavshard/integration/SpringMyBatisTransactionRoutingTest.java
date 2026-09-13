package io.github.lavyoung.lavshard.integration;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardValue;
import io.github.lavyoung.lavshard.core.api.exception.CrossShardTransactionException;
import io.github.lavyoung.lavshard.core.api.exception.TransactionRequiredException;
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
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.MyBatisSystemException;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spring、MyBatis 与 LavShard 的本地事务集成契约。
 *
 * <p>测试使用两个独立 H2 内存数据库模拟同名物理表分库。Spring
 * 事务管理器和 MyBatis 必须共享最外层 LazyConnectionDataSourceProxy，
 * 跨库路由必须在第二个物理数据源获取连接前失败。</p>
 */
class SpringMyBatisTransactionRoutingTest {

    private static final AlgorithmConfig ALGORITHM_CONFIG =
            new AlgorithmConfig(
                    2,
                    Murmur3HashShardAlgorithm.HASH_VERSION
            );

    private CountingDataSource ds0;
    private CountingDataSource ds1;
    private JdbcTemplate jdbc0;
    private JdbcTemplate jdbc1;
    private TransactionTemplate transactionTemplate;
    private DataSourceTransactionManager transactionManager;
    private DataSource routingDataSource;
    private MyBatisRouteContext routeContext;
    private OrderMapper mapper;
    private String ds0UserId;
    private String ds1UserId;

    @BeforeEach
    void setUp() {
        ds0 = physicalDataSource("ds0");
        ds1 = physicalDataSource("ds1");
        jdbc0 = new JdbcTemplate(ds0);
        jdbc1 = new JdbcTemplate(ds1);
        createSchema(jdbc0);
        createSchema(jdbc1);

        SpringShardContext springShardContext =
                new SpringShardContext();
        routeContext =
                new MyBatisRouteContext(
                        springShardContext::validate
                );
        routingDataSource =
                new LavShardRoutingDataSource(
                        Map.of("ds0", ds0, "ds1", ds1),
                        routeContext
                );
        DataSource transactionDataSource =
                new LazyConnectionDataSourceProxy(
                        routingDataSource
                );

        transactionManager = new DataSourceTransactionManager(transactionDataSource);
        transactionTemplate = new TransactionTemplate(transactionManager);
        mapper = mapper(
                transactionDataSource,
                routeContext
        );
        ds0UserId = userIdForBucket(0);
        ds1UserId = userIdForBucket(1);
    }

    @Test
    void shouldCommitManagedInsertOnSelectedDataSource() {
        transactionTemplate.executeWithoutResult(status ->
                mapper.insert(ds0UserId, "committed")
        );

        assertThat(countOrders(jdbc0)).isEqualTo(1);
        assertThat(countOrders(jdbc1)).isZero();
    }

    @Test
    void shouldRouteManagedSqlWithNonDefaultIsolationWithoutOuterLazyProxy() {
        // Given: Starter exposes LavShardRoutingDataSource itself as the lazy boundary.
        TransactionTemplate serializable = new TransactionTemplate(
                new DataSourceTransactionManager(routingDataSource)
        );
        serializable.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        OrderMapper directMapper = mapper(routingDataSource, routeContext);

        // When
        serializable.executeWithoutResult(status ->
                directMapper.insert(ds1UserId, "serializable")
        );

        // Then
        assertThat(countOrders(jdbc0)).isZero();
        assertThat(countOrders(jdbc1)).isEqualTo(1);
    }

    @Test
    void shouldNotOpenPhysicalConnectionForEmptyNonDefaultIsolationTransaction() {
        // Given
        TransactionTemplate serializable = new TransactionTemplate(
                new DataSourceTransactionManager(routingDataSource)
        );
        serializable.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        int ds0AttemptsBefore = ds0.connectionAttempts();
        int ds1AttemptsBefore = ds1.connectionAttempts();

        // When
        serializable.executeWithoutResult(status -> {
            // An empty transaction must remain a purely logical connection lifecycle.
        });

        // Then
        assertThat(ds0.connectionAttempts()).isEqualTo(ds0AttemptsBefore);
        assertThat(ds1.connectionAttempts()).isEqualTo(ds1AttemptsBefore);
    }

    @Test
    void shouldRejectManagedForUpdateOutsideTransactionBeforeConnection() {
        // Given
        jdbc0.update(
                "INSERT INTO t_order_00 (user_id, note) VALUES (?, ?)",
                ds0UserId,
                "lock-target"
        );
        int ds0AttemptsBefore = ds0.connectionAttempts();

        // When / Then
        assertThatThrownBy(() -> mapper.lockNote(ds0UserId))
                .isInstanceOf(MyBatisSystemException.class)
                .hasRootCauseInstanceOf(TransactionRequiredException.class);
        assertThat(ds0.connectionAttempts()).isEqualTo(ds0AttemptsBefore);
    }

    @Test
    void shouldExecuteManagedForUpdateInsideSelectedTransaction() {
        // Given
        jdbc0.update(
                "INSERT INTO t_order_00 (user_id, note) VALUES (?, ?)",
                ds0UserId,
                "lock-target"
        );
        int ds0AttemptsBefore = ds0.connectionAttempts();

        // When
        String note = transactionTemplate.execute(status ->
                mapper.lockNote(ds0UserId)
        );

        // Then
        assertThat(note).isEqualTo("lock-target");
        assertThat(ds0.connectionAttempts() - ds0AttemptsBefore)
                .isEqualTo(1);
    }

    @Test
    void shouldRejectPassThroughForUpdateOutsideTransactionBeforeConnection() {
        // Given
        jdbc1.update("INSERT INTO sys_audit (id) VALUES (1)");
        int ds1AttemptsBefore = ds1.connectionAttempts();

        // When / Then
        assertThatThrownBy(() -> mapper.lockAudit(1L))
                .isInstanceOf(MyBatisSystemException.class)
                .hasRootCauseInstanceOf(TransactionRequiredException.class);
        assertThat(ds1.connectionAttempts()).isEqualTo(ds1AttemptsBefore);
    }

    @Test
    void shouldExecutePassThroughForUpdateInsideDefaultDataSourceTransaction() {
        // Given
        jdbc1.update("INSERT INTO sys_audit (id) VALUES (1)");

        // When
        Long id = transactionTemplate.execute(status -> mapper.lockAudit(1L));

        // Then
        assertThat(id).isEqualTo(1L);
    }

    @AfterEach
    void shouldReleaseAllTransactionAndRoutingResources() {
        assertThat(routeContext.currentDecision()).isEmpty();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }

    @Test
    void shouldCommitRequiresNewOnAnotherShardAndResumeOuterConnection() {
        // Given
        TransactionTemplate inner = propagation(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        int before0 = ds0.connectionAttempts();
        int before1 = ds1.connectionAttempts();

        // When
        transactionTemplate.executeWithoutResult(outer -> {
            mapper.insert(ds0UserId, "outer-before");
            inner.executeWithoutResult(status -> mapper.insert(ds1UserId, "inner"));
            mapper.insert(ds0UserId, "outer-after");
        });

        // Then: the outer physical connection is resumed, not reopened.
        assertThat(ds0.connectionAttempts() - before0).isEqualTo(1);
        assertThat(ds1.connectionAttempts() - before1).isEqualTo(1);
        assertThat(countOrders(jdbc0)).isEqualTo(2);
        assertThat(countOrders(jdbc1)).isEqualTo(1);
    }

    @Test
    void shouldKeepRequiresNewCommitWhenOuterRollsBack() {
        // Given
        TransactionTemplate inner = propagation(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // When
        transactionTemplate.executeWithoutResult(outer -> {
            mapper.insert(ds0UserId, "outer-rollback");
            inner.executeWithoutResult(status -> mapper.insert(ds1UserId, "inner-commit"));
            outer.setRollbackOnly();
        });

        // Then
        assertThat(countOrders(jdbc0)).isZero();
        assertThat(countOrders(jdbc1)).isEqualTo(1);
    }

    @Test
    void shouldResumeOuterAfterRequiresNewDatabaseFailure() {
        // Given
        TransactionTemplate inner = propagation(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // When
        transactionTemplate.executeWithoutResult(outer -> {
            mapper.insert(ds0UserId, "outer-before");
            assertThatThrownBy(() -> inner.executeWithoutResult(status -> {
                mapper.insert(ds1UserId, "inner-rollback");
                mapper.insert(ds1UserId, null); // NOT NULL violation after a successful write.
            })).hasRootCauseInstanceOf(SQLException.class);
            mapper.insert(ds0UserId, "outer-after");
        });

        // Then
        assertThat(countOrders(jdbc0)).isEqualTo(2);
        assertThat(countOrders(jdbc1)).isZero();
    }

    @Test
    void shouldRestoreOuterGuardAfterSameShardRequiresNew() {
        // Given
        TransactionTemplate inner = propagation(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        int before1 = ds1.connectionAttempts();

        // When
        transactionTemplate.executeWithoutResult(outer -> {
            mapper.insert(ds0UserId, "outer-before");
            inner.executeWithoutResult(status -> mapper.insert(ds0UserId, "inner"));
            assertThatThrownBy(() -> mapper.insert(ds1UserId, "must-reject"))
                    .hasRootCauseInstanceOf(CrossShardTransactionException.class);
            mapper.insert(ds0UserId, "outer-after");
        });

        // Then
        assertThat(ds1.connectionAttempts()).isEqualTo(before1);
        assertThat(countOrders(jdbc0)).isEqualTo(3);
        assertThat(countOrders(jdbc1)).isZero();
    }

    @Test
    void shouldResumeThreeLevelsOfIndependentTransactions() {
        // Given
        TransactionTemplate inner = propagation(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        int before0 = ds0.connectionAttempts();
        int before1 = ds1.connectionAttempts();

        // When
        transactionTemplate.executeWithoutResult(outer -> {
            mapper.insert(ds0UserId, "level-one");
            inner.executeWithoutResult(middle -> {
                mapper.insert(ds1UserId, "level-two-before");
                inner.executeWithoutResult(deepest -> mapper.insert(ds0UserId, "level-three"));
                mapper.insert(ds1UserId, "level-two-after");
            });
            mapper.insert(ds0UserId, "level-one-after");
        });

        // Then
        assertThat(ds0.connectionAttempts() - before0).isEqualTo(2);
        assertThat(ds1.connectionAttempts() - before1).isEqualTo(1);
        assertThat(countOrders(jdbc0)).isEqualTo(3);
        assertThat(countOrders(jdbc1)).isEqualTo(2);
    }

    @Test
    void shouldKeepOuterUnboundWhenRequiresNewRunsBeforeFirstOuterSql() {
        // Given
        TransactionTemplate inner = propagation(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // When
        transactionTemplate.executeWithoutResult(outer -> {
            inner.executeWithoutResult(status -> mapper.insert(ds1UserId, "inner-first"));
            mapper.insert(ds0UserId, "outer-first");
        });

        // Then
        assertThat(countOrders(jdbc0)).isEqualTo(1);
        assertThat(countOrders(jdbc1)).isEqualTo(1);
    }

    @Test
    void shouldAllowPassThroughInRequiresNewAndRestoreOuterGuard() {
        // Given: ordinary tables use ds1; the outer transaction uses ds0.
        TransactionTemplate inner = propagation(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // When
        transactionTemplate.executeWithoutResult(outer -> {
            mapper.insert(ds0UserId, "outer");
            inner.executeWithoutResult(status -> assertThat(mapper.countAuditRows()).isZero());
            assertThatThrownBy(() -> mapper.countAuditRows())
                    .hasRootCauseInstanceOf(CrossShardTransactionException.class);
        });

        // Then
        assertThat(countOrders(jdbc0)).isEqualTo(1);
    }

    @Test
    void shouldAllowNonTransactionalWriteAndRestoreSuspendedGuard() {
        // Given
        TransactionTemplate nonTransactional = propagation(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);

        // When
        transactionTemplate.executeWithoutResult(outer -> {
            mapper.insert(ds0UserId, "outer-rollback");
            nonTransactional.executeWithoutResult(status -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                mapper.insert(ds1UserId, "non-transactional-commit");
            });
            assertThatThrownBy(() -> mapper.insert(ds1UserId, "must-reject"))
                    .hasRootCauseInstanceOf(CrossShardTransactionException.class);
            outer.setRollbackOnly();
        });

        // Then
        assertThat(countOrders(jdbc0)).isZero();
        assertThat(countOrders(jdbc1)).isEqualTo(1);
    }

    @Test
    void shouldRollbackNestedSavepointWithoutReleasingShardBinding() {
        // Given: NESTED shares the physical transaction and its route binding.
        TransactionTemplate nested = propagation(TransactionDefinition.PROPAGATION_NESTED);
        int before0 = ds0.connectionAttempts();

        // When
        transactionTemplate.executeWithoutResult(outer -> {
            mapper.insert(ds0UserId, "outer-before");
            nested.executeWithoutResult(status -> {
                mapper.insert(ds0UserId, "savepoint-rollback");
                status.setRollbackOnly();
            });
            assertThatThrownBy(() -> mapper.insert(ds1UserId, "must-reject"))
                    .hasRootCauseInstanceOf(CrossShardTransactionException.class);
            mapper.insert(ds0UserId, "outer-after");
        });

        // Then
        assertThat(ds0.connectionAttempts() - before0).isEqualTo(1);
        assertThat(countOrders(jdbc0)).isEqualTo(2);
        assertThat(countOrders(jdbc1)).isZero();
    }

    @Test
    void shouldRollbackNestedWorkBeforeFirstOuterSql() {
        // Given
        TransactionTemplate nested = propagation(
                TransactionDefinition.PROPAGATION_NESTED
        );

        // When
        transactionTemplate.executeWithoutResult(outer -> {
            nested.executeWithoutResult(status -> {
                mapper.insert(ds0UserId, "nested-rollback");
                status.setRollbackOnly();
            });
            mapper.insert(ds0UserId, "outer-commit");
        });

        // Then
        assertThat(countOrders(jdbc0)).isEqualTo(1);
        assertThat(countOrders(jdbc1)).isZero();
    }

    @Test
    void shouldCommitNestedWorkBeforeFirstOuterSqlOnSameShard() {
        // Given
        TransactionTemplate nested = propagation(
                TransactionDefinition.PROPAGATION_NESTED
        );

        // When
        transactionTemplate.executeWithoutResult(outer -> {
            nested.executeWithoutResult(status ->
                    mapper.insert(ds1UserId, "nested-commit")
            );
            mapper.insert(ds1UserId, "outer-commit");
        });

        // Then
        assertThat(countOrders(jdbc0)).isZero();
        assertThat(countOrders(jdbc1)).isEqualTo(2);
    }

    @Test
    void shouldKeepNestedFirstRouteBoundForOuterTransaction() {
        // Given
        TransactionTemplate nested = propagation(
                TransactionDefinition.PROPAGATION_NESTED
        );

        // When
        transactionTemplate.executeWithoutResult(outer -> {
            nested.executeWithoutResult(status ->
                    mapper.insert(ds1UserId, "nested-commit")
            );
            assertThatThrownBy(() ->
                    mapper.insert(ds0UserId, "cross-shard")
            ).hasRootCauseInstanceOf(CrossShardTransactionException.class);
        });

        // Then
        assertThat(countOrders(jdbc0)).isZero();
        assertThat(countOrders(jdbc1)).isEqualTo(1);
    }

    @Test
    void shouldKeepEmptyNestedScopeFullyLazyBeforeFirstOuterSql() {
        // Given
        TransactionTemplate nested = propagation(
                TransactionDefinition.PROPAGATION_NESTED
        );
        int before0 = ds0.connectionAttempts();
        int before1 = ds1.connectionAttempts();

        // When
        transactionTemplate.executeWithoutResult(outer ->
                nested.executeWithoutResult(status -> {
                    // No SQL: savepoint creation and release stay logical.
                })
        );

        // Then
        assertThat(ds0.connectionAttempts()).isEqualTo(before0);
        assertThat(ds1.connectionAttempts()).isEqualTo(before1);
    }

    private TransactionTemplate propagation(int behavior) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(behavior);
        return template;
    }

    @Test
    void shouldRollbackManagedInsertOnSelectedDataSource() {
        transactionTemplate.executeWithoutResult(status -> {
            mapper.insert(ds0UserId, "rolled-back");
            status.setRollbackOnly();
        });

        assertThat(countOrders(jdbc0)).isZero();
        assertThat(countOrders(jdbc1)).isZero();
    }

    @Test
    void shouldReuseOnePhysicalConnectionForSameShardTransaction() {
        int attemptsBefore = ds0.connectionAttempts();

        transactionTemplate.executeWithoutResult(status -> {
            mapper.insert(ds0UserId, "first");
            mapper.insert(ds0UserId, "second");
            assertThat(mapper.countByUserId(ds0UserId))
                    .isEqualTo(2);
        });

        assertThat(ds0.connectionAttempts() - attemptsBefore)
                .isEqualTo(1);
        assertThat(countOrders(jdbc0)).isEqualTo(2);
    }

    @Test
    void shouldRouteIndependentTransactionsToDifferentDataSources() {
        transactionTemplate.executeWithoutResult(status ->
                mapper.insert(ds0UserId, "on-ds0")
        );
        transactionTemplate.executeWithoutResult(status ->
                mapper.insert(ds1UserId, "on-ds1")
        );

        assertThat(countOrders(jdbc0)).isEqualTo(1);
        assertThat(countOrders(jdbc1)).isEqualTo(1);
    }

    @Test
    void shouldRejectCrossDataSourceManagedRouteBeforeSecondConnection() {
        int ds1AttemptsBefore = ds1.connectionAttempts();

        assertThatThrownBy(() ->
                transactionTemplate.executeWithoutResult(status -> {
                    mapper.insert(ds0UserId, "must-roll-back");
                    mapper.insert(ds1UserId, "must-be-rejected");
                }))
                .isInstanceOf(MyBatisSystemException.class)
                .hasRootCauseInstanceOf(
                        CrossShardTransactionException.class
                )
                .hasRootCauseMessage(
                        "Transaction is already bound to dataSourceId ds0 "
                                + "and cannot route to ds1"
                );

        assertThat(ds1.connectionAttempts())
                .isEqualTo(ds1AttemptsBefore);
        assertThat(countOrders(jdbc0)).isZero();
        assertThat(countOrders(jdbc1)).isZero();
    }

    @Test
    void shouldRejectPassThroughToDifferentDataSourceBeforeConnection() {
        int ds1AttemptsBefore = ds1.connectionAttempts();

        assertThatThrownBy(() ->
                transactionTemplate.executeWithoutResult(status -> {
                    mapper.insert(ds0UserId, "must-roll-back");
                    mapper.countAuditRows();
                }))
                .isInstanceOf(MyBatisSystemException.class)
                .hasRootCauseInstanceOf(
                        CrossShardTransactionException.class
                )
                .hasRootCauseMessage(
                        "Transaction is already bound to dataSourceId ds0 "
                                + "and cannot route to ds1"
                );

        assertThat(ds1.connectionAttempts())
                .isEqualTo(ds1AttemptsBefore);
        assertThat(countOrders(jdbc0)).isZero();
    }

    private static OrderMapper mapper(
            DataSource transactionDataSource,
            MyBatisRouteContext routeContext
    ) {
        Environment environment = new Environment(
                "lavshard-transaction-test",
                new SpringManagedTransactionFactory(),
                transactionDataSource
        );
        Configuration configuration =
                new Configuration(environment);
        configuration.addMapper(OrderMapper.class);
        configuration.addInterceptor(
                new LavShardExecutorInterceptor(
                        routeEngine(),
                        SpringMyBatisTransactionRoutingTest::snapshot,
                        routeContext
                )
        );

        SqlSessionFactory sqlSessionFactory =
                new SqlSessionFactoryBuilder()
                        .build(configuration);
        return new SqlSessionTemplate(
                sqlSessionFactory
        ).getMapper(OrderMapper.class);
    }

    private static SqlRouteEngine routeEngine() {
        return new SqlRouteEngine(
                ShardAlgorithmRegistry.withBuiltInAlgorithms(),
                Set.of(new QualifiedTableName("sys_audit")),
                "ds1"
        );
    }

    private static RuleSnapshot snapshot() {
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
                "order-topology-v1",
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
                "order-rule-v1",
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

    private static CountingDataSource physicalDataSource(
            String id
    ) {
        JdbcDataSource delegate = new JdbcDataSource();
        delegate.setURL(
                "jdbc:h2:mem:"
                        + id
                        + "-"
                        + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1"
        );
        delegate.setUser("sa");
        delegate.setPassword("");
        return new CountingDataSource(delegate);
    }

    private static void createSchema(
            JdbcTemplate jdbcTemplate
    ) {
        jdbcTemplate.execute("""
                CREATE TABLE t_order_00 (
                    user_id VARCHAR(64) NOT NULL,
                    note VARCHAR(128) NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE sys_audit (
                    id BIGINT PRIMARY KEY
                )
                """);
    }

    private static int countOrders(
            JdbcTemplate jdbcTemplate
    ) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_order_00",
                Integer.class
        );
        return count == null ? 0 : count;
    }

    private interface OrderMapper {

        @Insert("""
                INSERT INTO t_order (user_id, note)
                VALUES (#{userId}, #{note})
                """)
        int insert(
                @Param("userId") String userId,
                @Param("note") String note
        );

        @Select("""
                SELECT COUNT(*)
                FROM t_order
                WHERE user_id = #{userId}
                """)
        int countByUserId(
                @Param("userId") String userId
        );

        @Select("""
                SELECT note
                FROM t_order
                WHERE user_id = #{userId}
                FOR UPDATE
                """)
        String lockNote(
                @Param("userId") String userId
        );

        @Select("""
                SELECT id
                FROM sys_audit
                WHERE id = #{id}
                FOR UPDATE
                """)
        Long lockAudit(@Param("id") long id);

        @Select("SELECT COUNT(*) FROM sys_audit")
        int countAuditRows();
    }

    /**
     * 记录真实物理连接获取次数的数据源包装器。
     */
    private static final class CountingDataSource
            extends AbstractDataSource {

        private final DataSource delegate;
        private final AtomicInteger connectionAttempts =
                new AtomicInteger();

        private CountingDataSource(
                DataSource delegate
        ) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection()
                throws SQLException {
            connectionAttempts.incrementAndGet();
            return delegate.getConnection();
        }

        @Override
        public Connection getConnection(
                String username,
                String password
        ) throws SQLException {
            connectionAttempts.incrementAndGet();
            return delegate.getConnection(
                    username,
                    password
            );
        }

        private int connectionAttempts() {
            return connectionAttempts.get();
        }
    }
}
