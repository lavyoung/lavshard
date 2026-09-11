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
import io.github.lavyoung.lavshard.mybatis.internal.LavShardExecutorInterceptor;
import io.github.lavyoung.lavshard.mybatis.internal.LavShardRoutingDataSource;
import io.github.lavyoung.lavshard.mybatis.internal.MyBatisRouteContext;
import io.github.lavyoung.lavshard.starter.support.SpringShardContext;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.MyBatisSystemException;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
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
        MyBatisRouteContext routeContext =
                new MyBatisRouteContext(
                        springShardContext::validate
                );
        DataSource routingDataSource =
                new LavShardRoutingDataSource(
                        Map.of("ds0", ds0, "ds1", ds1),
                        routeContext
                );
        DataSource transactionDataSource =
                new LazyConnectionDataSourceProxy(
                        routingDataSource
                );

        transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(
                        transactionDataSource
                )
        );
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
