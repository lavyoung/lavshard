package io.github.lavyoung.lavshard.integration;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
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
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
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
 * 同一事务访问多张受管逻辑表的 MyBatis、Spring 和 JDBC 闭环。
 */
class MultiManagedTableTransactionTest {

    private CountingDataSource ds0;
    private CountingDataSource ds1;
    private JdbcTemplate jdbc0;
    private JdbcTemplate jdbc1;
    private MyBatisRouteContext routeContext;
    private TransactionTemplate transaction;
    private MultiTableMapper mapper;

    @BeforeEach
    void setUp() {
        ds0 = physicalDataSource("ds0");
        ds1 = physicalDataSource("ds1");
        jdbc0 = new JdbcTemplate(ds0);
        jdbc1 = new JdbcTemplate(ds1);
        createSchema(jdbc0);
        createSchema(jdbc1);

        SpringShardContext shardContext = new SpringShardContext();
        routeContext = new MyBatisRouteContext(shardContext::validate);
        DataSource routing = new LavShardRoutingDataSource(
                Map.of("ds0", ds0, "ds1", ds1),
                routeContext
        );
        DataSource transactionDataSource =
                new LazyConnectionDataSourceProxy(routing);
        transaction = new TransactionTemplate(
                new DataSourceTransactionManager(transactionDataSource)
        );
        mapper = mapper(transactionDataSource, routeContext);
    }

    @AfterEach
    void verifyContextCleanup() {
        assertThat(routeContext.currentDecision()).isEmpty();
        assertThat(TransactionSynchronizationManager.getResourceMap())
                .isEmpty();
    }

    @Test
    void shouldCommitDifferentManagedTablesOnSameDataSource() {
        // Given
        int attemptsBefore = ds0.connectionAttempts();

        // When
        transaction.executeWithoutResult(status -> {
            mapper.insertOrder("user-1", "order");
            mapper.insertPayment("user-1", "payment");
            mapper.insertOrder("user-2", "order-2");
        });

        // Then
        assertThat(ds0.connectionAttempts() - attemptsBefore).isEqualTo(1);
        assertThat(count(jdbc0, "t_order_00")).isEqualTo(2);
        assertThat(count(jdbc0, "t_payment_00")).isEqualTo(1);
        assertThat(count(jdbc1, "t_order_00")).isZero();
        assertThat(count(jdbc1, "t_payment_00")).isZero();
    }

    @Test
    void shouldRollbackAllManagedTablesOnSameDataSource() {
        // Given / When
        transaction.executeWithoutResult(status -> {
            mapper.insertOrder("user-1", "order");
            mapper.insertPayment("user-1", "payment");
            status.setRollbackOnly();
        });

        // Then
        assertThat(count(jdbc0, "t_order_00")).isZero();
        assertThat(count(jdbc0, "t_payment_00")).isZero();
    }

    @Test
    void shouldRejectSecondManagedTableOnDifferentDataSource() {
        // Given
        int ds1AttemptsBefore = ds1.connectionAttempts();

        // When / Then
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            mapper.insertOrder("user-1", "must-roll-back");
            mapper.insertInvoice("user-1", "must-reject");
        }))
                .isInstanceOf(MyBatisSystemException.class)
                .hasRootCauseInstanceOf(CrossShardTransactionException.class)
                .hasRootCauseMessage(
                        "Transaction is already bound to dataSourceId ds0 "
                                + "and cannot route to ds1"
                );

        assertThat(ds1.connectionAttempts()).isEqualTo(ds1AttemptsBefore);
        assertThat(count(jdbc0, "t_order_00")).isZero();
        assertThat(count(jdbc1, "t_invoice_00")).isZero();
    }

    private static MultiTableMapper mapper(
            DataSource dataSource,
            MyBatisRouteContext routeContext
    ) {
        Environment environment = new Environment(
                "multi-managed-table-test",
                new SpringManagedTransactionFactory(),
                dataSource
        );
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(MultiTableMapper.class);
        configuration.addInterceptor(new LavShardExecutorInterceptor(
                new SqlRouteEngine(
                        ShardAlgorithmRegistry.withBuiltInAlgorithms(),
                        Set.of(),
                        "ds0"
                ),
                MultiManagedTableTransactionTest::snapshot,
                routeContext
        ));
        SqlSessionFactory factory =
                new SqlSessionFactoryBuilder().build(configuration);
        return new SqlSessionTemplate(factory)
                .getMapper(MultiTableMapper.class);
    }

    private static RuleSnapshot snapshot() {
        return new RuleSnapshot(List.of(
                rule("t_order", "order-rule-v1", "order-topology-v1", "ds0"),
                rule("t_payment", "payment-rule-v7", "payment-topology-v3", "ds0"),
                rule("t_invoice", "invoice-rule-v2", "invoice-topology-v4", "ds1")
        ));
    }

    private static TableRule rule(
            String logicalTable,
            String ruleVersion,
            String topologyVersion,
            String dataSourceId
    ) {
        ShardNode node = new ShardNode(
                logicalTable + "-node",
                dataSourceId,
                new QualifiedTableName(logicalTable + "_00")
        );
        ShardTopology topology = new ShardTopology(
                topologyVersion,
                1,
                Map.of(0, node.nodeId()),
                Map.of(node.nodeId(), node)
        );
        return new TableRule(
                ruleVersion,
                new QualifiedTableName(logicalTable),
                "user_id",
                Murmur3HashShardAlgorithm.NAME,
                new AlgorithmConfig(
                        1,
                        Murmur3HashShardAlgorithm.HASH_VERSION
                ),
                topology
        );
    }

    private static CountingDataSource physicalDataSource(String id) {
        JdbcDataSource delegate = new JdbcDataSource();
        delegate.setURL(
                "jdbc:h2:mem:multi-table-"
                        + id + "-" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1"
        );
        delegate.setUser("sa");
        delegate.setPassword("");
        return new CountingDataSource(delegate);
    }

    private static void createSchema(JdbcTemplate jdbcTemplate) {
        for (String table : List.of(
                "t_order_00",
                "t_payment_00",
                "t_invoice_00"
        )) {
            jdbcTemplate.execute("""
                    CREATE TABLE %s (
                        user_id VARCHAR(64) PRIMARY KEY,
                        note VARCHAR(128) NOT NULL
                    )
                    """.formatted(table));
        }
    }

    private static int count(
            JdbcTemplate jdbcTemplate,
            String table
    ) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table,
                Integer.class
        );
        return value == null ? 0 : value;
    }

    private interface MultiTableMapper {

        @Insert("""
                INSERT INTO t_order (user_id, note)
                VALUES (#{userId}, #{note})
                """)
        int insertOrder(
                @Param("userId") String userId,
                @Param("note") String note
        );

        @Insert("""
                INSERT INTO t_payment (user_id, note)
                VALUES (#{userId}, #{note})
                """)
        int insertPayment(
                @Param("userId") String userId,
                @Param("note") String note
        );

        @Insert("""
                INSERT INTO t_invoice (user_id, note)
                VALUES (#{userId}, #{note})
                """)
        int insertInvoice(
                @Param("userId") String userId,
                @Param("note") String note
        );
    }

    private static final class CountingDataSource
            extends AbstractDataSource {

        private final DataSource delegate;
        private final AtomicInteger connectionAttempts =
                new AtomicInteger();

        private CountingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            connectionAttempts.incrementAndGet();
            return delegate.getConnection();
        }

        @Override
        public Connection getConnection(
                String username,
                String password
        ) throws SQLException {
            connectionAttempts.incrementAndGet();
            return delegate.getConnection(username, password);
        }

        private int connectionAttempts() {
            return connectionAttempts.get();
        }
    }
}
