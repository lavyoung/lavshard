package io.github.lavyoung.lavshard.starter;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardAlgorithm;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardBucket;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardValue;
import io.github.lavyoung.lavshard.core.api.exception.CrossShardTransactionException;
import io.github.lavyoung.lavshard.mybatis.internal.LavShardExecutorInterceptor;
import io.github.lavyoung.lavshard.mybatis.internal.LavShardRoutingDataSource;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.MyBatisSystemException;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spring Boot、MyBatis、JDBC 路由和本地事务的零手工装配契约。
 */
class LavShardMyBatisBootIntegrationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            LavShardAutoConfiguration.class,
                            LavShardDataSourceAutoConfiguration.class,
                            MybatisAutoConfiguration.class,
                            DataSourceTransactionManagerAutoConfiguration.class
                    ))
                    .withUserConfiguration(PhysicalDataSourcesConfiguration.class)
                    .withPropertyValues(validProperties());

    @Test
    void shouldWireMyBatisAndTransactionManagerToTheSameRoutingDataSource() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SqlSessionFactory.class);
            assertThat(context).hasSingleBean(SqlSessionTemplate.class);
            assertThat(context).hasSingleBean(PlatformTransactionManager.class);

            LavShardRoutingDataSource routingDataSource =
                    context.getBean(LavShardRoutingDataSource.class);
            SqlSessionFactory sqlSessionFactory =
                    context.getBean(SqlSessionFactory.class);
            LavShardExecutorInterceptor interceptor =
                    context.getBean(LavShardExecutorInterceptor.class);

            assertThat(sqlSessionFactory
                    .getConfiguration()
                    .getEnvironment()
                    .getDataSource())
                    .isSameAs(routingDataSource);
            assertThat(sqlSessionFactory
                    .getConfiguration()
                    .getInterceptors())
                    .containsExactly(interceptor);

            PlatformTransactionManager transactionManager =
                    context.getBean(PlatformTransactionManager.class);
            assertThat(transactionManager)
                    .isInstanceOf(DataSourceTransactionManager.class);
            assertThat(((DataSourceTransactionManager) transactionManager)
                    .getDataSource())
                    .isSameAs(routingDataSource);
        });
    }

    @Test
    void shouldExecuteManagedAndPassThroughMapperMethodsWithoutManualSetup() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            OrderMapper mapper = mapper(context.getBean(
                    SqlSessionFactory.class
            ), context.getBean(SqlSessionTemplate.class));

            assertThat(mapper.insertOrder(0L, "on-ds0")).isEqualTo(1);
            assertThat(mapper.insertOrder(1L, "on-ds1")).isEqualTo(1);
            assertThat(mapper.insertAudit(100L, "ordinary")).isEqualTo(1);

            assertThat(mapper.countOrders(0L)).isEqualTo(1);
            assertThat(mapper.countOrders(1L)).isEqualTo(1);
            assertThat(mapper.countAuditRows()).isEqualTo(1);

            assertThat(countOrders(context, "orderDataSource0"))
                    .isEqualTo(1);
            assertThat(countOrders(context, "orderDataSource1"))
                    .isEqualTo(1);
            assertThat(countAuditRows(context, "orderDataSource0"))
                    .isEqualTo(1);
            assertThat(countAuditRows(context, "orderDataSource1"))
                    .isZero();
        });
    }

    @Test
    void shouldCommitAndRollbackThroughAutoConfiguredTransactionManager() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            OrderMapper mapper = mapper(context.getBean(
                    SqlSessionFactory.class
            ), context.getBean(SqlSessionTemplate.class));
            TransactionTemplate transaction = new TransactionTemplate(
                    context.getBean(PlatformTransactionManager.class)
            );

            transaction.executeWithoutResult(status ->
                    mapper.insertOrder(0L, "committed")
            );
            transaction.executeWithoutResult(status -> {
                mapper.insertOrder(1L, "rolled-back");
                status.setRollbackOnly();
            });

            assertThat(countOrders(context, "orderDataSource0"))
                    .isEqualTo(1);
            assertThat(countOrders(context, "orderDataSource1"))
                    .isZero();
        });
    }

    @Test
    void shouldReuseOnePhysicalConnectionInsideSameShardTransaction() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            OrderMapper mapper = mapper(context.getBean(
                    SqlSessionFactory.class
            ), context.getBean(SqlSessionTemplate.class));
            TransactionTemplate transaction = new TransactionTemplate(
                    context.getBean(PlatformTransactionManager.class)
            );
            CountingDataSource dataSource0 = context.getBean(
                    "orderDataSource0",
                    CountingDataSource.class
            );
            int attemptsBefore = dataSource0.connectionAttempts();

            transaction.executeWithoutResult(status -> {
                mapper.insertOrder(0L, "first");
                mapper.insertOrder(0L, "second");
                assertThat(mapper.countOrders(0L)).isEqualTo(2);
            });

            assertThat(dataSource0.connectionAttempts() - attemptsBefore)
                    .isEqualTo(1);
            assertThat(countOrders(context, "orderDataSource0"))
                    .isEqualTo(2);
        });
    }

    @Test
    void shouldRejectCrossDataSourceTransactionBeforeSecondConnection() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            OrderMapper mapper = mapper(context.getBean(
                    SqlSessionFactory.class
            ), context.getBean(SqlSessionTemplate.class));
            TransactionTemplate transaction = new TransactionTemplate(
                    context.getBean(PlatformTransactionManager.class)
            );
            CountingDataSource dataSource1 = context.getBean(
                    "orderDataSource1",
                    CountingDataSource.class
            );
            int attemptsBefore = dataSource1.connectionAttempts();

            assertThatThrownBy(() -> transaction.executeWithoutResult(
                    status -> {
                        mapper.insertOrder(0L, "must-roll-back");
                        mapper.insertOrder(1L, "must-be-rejected");
                    }
            ))
                    .isInstanceOf(MyBatisSystemException.class)
                    .hasRootCauseInstanceOf(
                            CrossShardTransactionException.class
                    )
                    .hasRootCauseMessage(
                            "Transaction is already bound to dataSourceId ds0 "
                                    + "and cannot route to ds1"
                    );

            assertThat(dataSource1.connectionAttempts())
                    .isEqualTo(attemptsBefore);
            assertThat(countOrders(context, "orderDataSource0"))
                    .isZero();
            assertThat(countOrders(context, "orderDataSource1"))
                    .isZero();
        });
    }

    private static OrderMapper mapper(
            SqlSessionFactory sqlSessionFactory,
            SqlSessionTemplate sqlSessionTemplate
    ) {
        org.apache.ibatis.session.Configuration configuration =
                sqlSessionFactory.getConfiguration();
        if (!configuration.hasMapper(OrderMapper.class)) {
            configuration.addMapper(OrderMapper.class);
        }
        return sqlSessionTemplate.getMapper(OrderMapper.class);
    }

    private static int countOrders(
            org.springframework.context.ApplicationContext context,
            String beanName
    ) {
        return jdbcTemplate(context, beanName).queryForObject(
                "SELECT COUNT(*) FROM t_order_00",
                Integer.class
        );
    }

    private static int countAuditRows(
            org.springframework.context.ApplicationContext context,
            String beanName
    ) {
        return jdbcTemplate(context, beanName).queryForObject(
                "SELECT COUNT(*) FROM sys_audit",
                Integer.class
        );
    }

    private static JdbcTemplate jdbcTemplate(
            org.springframework.context.ApplicationContext context,
            String beanName
    ) {
        return new JdbcTemplate(context.getBean(
                beanName,
                DataSource.class
        ));
    }

    private static String[] validProperties() {
        return new String[]{
                "lavshard.integration.default-data-source=ds0",
                "lavshard.integration.ordinary-tables[0]=sys_audit",
                "lavshard.data-sources[ds0].bean-name=orderDataSource0",
                "lavshard.data-sources[ds1].bean-name=orderDataSource1",
                "lavshard.tables[t_order].rule-version=order-rule-v1",
                "lavshard.tables[t_order].sharding-column=user_id",
                "lavshard.tables[t_order].algorithm.name=test_identity",
                "lavshard.tables[t_order].algorithm.hash-version=test-v1",
                "lavshard.tables[t_order].topology.version=order-topology-v1",
                "lavshard.tables[t_order].topology.bucket-count=2",
                "lavshard.tables[t_order].topology.bucket-placements[0]=node0",
                "lavshard.tables[t_order].topology.bucket-placements[1]=node1",
                "lavshard.tables[t_order].topology.nodes[node0].data-source=ds0",
                "lavshard.tables[t_order].topology.nodes[node0].actual-table=t_order_00",
                "lavshard.tables[t_order].topology.nodes[node1].data-source=ds1",
                "lavshard.tables[t_order].topology.nodes[node1].actual-table=t_order_00"
        };
    }

    @Configuration(proxyBeanMethods = false)
    static class PhysicalDataSourcesConfiguration {

        @Bean
        CountingDataSource orderDataSource0() {
            return physicalDataSource("ds0");
        }

        @Bean
        CountingDataSource orderDataSource1() {
            return physicalDataSource("ds1");
        }

        @Bean
        ShardAlgorithm testIdentityAlgorithm() {
            return new IdentityAlgorithm();
        }
    }

    private static CountingDataSource physicalDataSource(String id) {
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

        CountingDataSource dataSource =
                new CountingDataSource(delegate);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE t_order_00 (
                    user_id BIGINT NOT NULL,
                    note VARCHAR(128) NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE sys_audit (
                    id BIGINT PRIMARY KEY,
                    note VARCHAR(128) NOT NULL
                )
                """);
        return dataSource;
    }

    private interface OrderMapper {

        @Insert("""
                INSERT INTO t_order (user_id, note)
                VALUES (#{userId}, #{note})
                """)
        int insertOrder(
                @Param("userId") long userId,
                @Param("note") String note
        );

        @Select("""
                SELECT COUNT(*)
                FROM t_order
                WHERE user_id = #{userId}
                """)
        int countOrders(@Param("userId") long userId);

        @Insert("""
                INSERT INTO sys_audit (id, note)
                VALUES (#{id}, #{note})
                """)
        int insertAudit(
                @Param("id") long id,
                @Param("note") String note
        );

        @Select("SELECT COUNT(*) FROM sys_audit")
        int countAuditRows();
    }

    private record IdentityAlgorithm() implements ShardAlgorithm {

        @Override
        public String name() {
            return "test_identity";
        }

        @Override
        public ShardBucket calculate(
                ShardValue value,
                AlgorithmConfig config
        ) {
            if (!(value instanceof ShardValue.LongValue longValue)) {
                throw new IllegalArgumentException(
                        "test_identity requires LongValue"
                );
            }
            return new ShardBucket(
                    Math.floorMod(longValue.value(), config.bucketCount())
            );
        }
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
