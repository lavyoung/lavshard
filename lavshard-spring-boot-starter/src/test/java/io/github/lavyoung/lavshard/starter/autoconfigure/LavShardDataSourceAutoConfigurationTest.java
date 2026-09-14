package io.github.lavyoung.lavshard.starter.autoconfigure;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardAlgorithm;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardBucket;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardValue;
import io.github.lavyoung.lavshard.core.api.exception.ConfigurationException;
import io.github.lavyoung.lavshard.core.api.route.PassThroughDecision;
import io.github.lavyoung.lavshard.core.api.route.SqlRouteDecision;
import io.github.lavyoung.lavshard.core.internal.route.SqlRouteEngine;
import io.github.lavyoung.lavshard.mybatis.internal.routing.LavShardRoutingDataSource;
import io.github.lavyoung.lavshard.mybatis.internal.routing.MyBatisRouteContext;
import io.github.lavyoung.lavshard.starter.internal.config.LavShardConfigurationSnapshot;
import io.github.lavyoung.lavshard.starter.internal.datasource.LavShardManagedDataSourceRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * LavShard 物理数据源解析和延迟路由数据源自动配置契约。
 */
class LavShardDataSourceAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            LavShardAutoConfiguration.class,
                            LavShardDataSourceAutoConfiguration.class,
                            DataSourceAutoConfiguration.class
                    ));

    @Test
    void shouldLogSafeRoutingDataSourceStartupSummary() {
        Logger logger = (Logger) LoggerFactory.getLogger(
                LavShardDataSourceAutoConfiguration.class
        );
        Level originalLevel = logger.getLevel();
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);

        try {
            contextRunner
                    .withUserConfiguration(PhysicalDataSourcesConfiguration.class)
                    .withPropertyValues(validProperties("test_identity"))
                    .run(context -> assertThat(context).hasNotFailed());

            assertThat(appender.list)
                    .singleElement()
                    .satisfies(event -> assertThat(event.getFormattedMessage())
                            .contains(
                                    "routing data source initialized",
                                    "dataSourceIds=[ds0, ds1]",
                                    "defaultDataSourceId=ds0",
                                    "managedTableCount=1",
                                    "ordinaryTableCount=1"
                            )
                            .doesNotContain(
                                    "jdbc:",
                                    "password",
                                    "username"
                            ));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }
    }

    @Test
    void shouldPublishPrimaryLazyRoutingDataSourceAndRouteAllDecisionTypes()
            throws SQLException {
        contextRunner
                .withUserConfiguration(PhysicalDataSourcesConfiguration.class)
                .withPropertyValues(validProperties("test_identity"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .hasSingleBean(LavShardRoutingDataSource.class);
                    assertThat(context)
                            .hasSingleBean(LavShardManagedDataSourceRegistry.class);
                    assertThat(context.getBean(
                            LavShardManagedDataSourceRegistry.class
                    ).dataSources()).isEmpty();

                    LavShardRoutingDataSource routingDataSource =
                            context.getBean(
                                    "lavShardDataSource",
                                    LavShardRoutingDataSource.class
                            );
                    assertThat(context.getBean(DataSource.class))
                            .isSameAs(routingDataSource);

                    DataSource dataSource0 = context.getBean(
                            "orderDataSource0",
                            DataSource.class
                    );
                    DataSource dataSource1 = context.getBean(
                            "orderDataSource1",
                            DataSource.class
                    );
                    DataSource unrelated = context.getBean(
                            "unrelatedDataSource",
                            DataSource.class
                    );
                    Connection physical0 = mock(Connection.class);
                    Connection physical1 = mock(Connection.class);
                    when(dataSource0.getConnection()).thenReturn(physical0);
                    when(dataSource1.getConnection()).thenReturn(physical1);
                    when(physical0.createStatement())
                            .thenReturn(mock(Statement.class));
                    when(physical1.createStatement())
                            .thenReturn(mock(Statement.class));

                    Connection managed0 = routingDataSource.getConnection();
                    Connection managed1 = routingDataSource.getConnection();
                    Connection passThrough = routingDataSource.getConnection();

                    verify(dataSource0, never()).getConnection();
                    verify(dataSource1, never()).getConnection();
                    verify(unrelated, never()).getConnection();

                    SqlRouteEngine routeEngine = context.getBean(
                            SqlRouteEngine.class
                    );
                    LavShardConfigurationSnapshot snapshot = context.getBean(
                            LavShardConfigurationSnapshot.class
                    );
                    MyBatisRouteContext routeContext = context.getBean(
                            MyBatisRouteContext.class
                    );

                    initialize(
                            managed0,
                            routeContext,
                            routeEngine.decide(
                                    snapshot.ruleSnapshot(),
                                    "SELECT * FROM t_order WHERE user_id = ?",
                                    List.of(0L)
                            )
                    );
                    initialize(
                            managed1,
                            routeContext,
                            routeEngine.decide(
                                    snapshot.ruleSnapshot(),
                                    "SELECT * FROM t_order WHERE user_id = ?",
                                    List.of(1L)
                            )
                    );
                    initialize(
                            passThrough,
                            routeContext,
                            routeEngine.decide(
                                    snapshot.ruleSnapshot(),
                                    "SELECT * FROM sys_dict",
                                    List.of()
                            )
                    );

                    verify(dataSource0, org.mockito.Mockito.times(2))
                            .getConnection();
                    verify(dataSource1).getConnection();
                    verify(unrelated, never()).getConnection();
                });
    }

    @Test
    void shouldExcludeUnconfiguredDataSourceBeansFromRoutingRegistry() {
        contextRunner
                .withUserConfiguration(PhysicalDataSourcesConfiguration.class)
                .withPropertyValues(validProperties("test_identity"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    LavShardRoutingDataSource routingDataSource =
                            context.getBean(LavShardRoutingDataSource.class);
                    MyBatisRouteContext routeContext = context.getBean(
                            MyBatisRouteContext.class
                    );

                    Connection logicalConnection =
                            routingDataSource.getConnection();

                    try (MyBatisRouteContext.Scope ignored = routeContext.open(
                            new PassThroughDecision(
                                    "unconfigured",
                                    "SELECT 1"
                            )
                    )) {
                        assertThatThrownBy(logicalConnection::createStatement)
                                .isInstanceOf(SQLException.class)
                                .hasMessage(
                                        "Unknown dataSourceId: unconfigured"
                                );
                    }

                    verify(
                            context.getBean(
                                    "unrelatedDataSource",
                                    DataSource.class
                            ),
                            never()
                    ).getConnection();
                });
    }

    @Test
    void shouldFailStartupWhenConfiguredDataSourceBeanIsMissing() {
        contextRunner
                .withUserConfiguration(OnlyFirstDataSourceConfiguration.class)
                .withPropertyValues(validProperties("hash_mod"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(ConfigurationException.class)
                            .hasRootCauseMessage(
                                    "Configured DataSource bean does not exist: "
                                            + "dataSourceId=ds1, "
                                            + "beanName=orderDataSource1"
                            );
                });
    }

    @Test
    void shouldFailStartupWhenConfiguredBeanIsNotADataSource() {
        contextRunner
                .withUserConfiguration(WrongTypeDataSourceConfiguration.class)
                .withPropertyValues(validProperties("hash_mod"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .cause()
                            .cause()
                            .isInstanceOf(ConfigurationException.class)
                            .hasMessage(
                                    "Configured DataSource bean has incompatible "
                                            + "type: dataSourceId=ds1, "
                                            + "beanName=orderDataSource1"
                            );
                });
    }

    @Test
    void shouldBackOffWhenApplicationProvidesRoutingDataSource() {
        contextRunner
                .withUserConfiguration(CustomRoutingDataSourceConfiguration.class)
                .withPropertyValues(validProperties("test_identity"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .hasSingleBean(LavShardRoutingDataSource.class);
                    assertThat(context.getBean(LavShardRoutingDataSource.class))
                            .isSameAs(context.getBean(
                                    "applicationRoutingDataSource"
                            ));
                    assertThat(context)
                            .doesNotHaveBean("lavShardDataSource");
                });
    }

    @Test
    void shouldNotCreateRoutingDataSourceWhenLavShardIsDisabled() {
        contextRunner
                .withUserConfiguration(PhysicalDataSourcesConfiguration.class)
                .withPropertyValues("lavshard.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .doesNotHaveBean(LavShardRoutingDataSource.class);
                    assertThat(context)
                            .doesNotHaveBean(LavShardManagedDataSourceRegistry.class);
                    assertThat(context)
                            .doesNotHaveBean("lavShardDataSource");
                });
    }

    @Test
    void shouldUseMySqlDefaultIsolationWithoutOpeningPhysicalConnection()
            throws SQLException {
        contextRunner
                .withUserConfiguration(PhysicalDataSourcesConfiguration.class)
                .withPropertyValues(validProperties("test_identity"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DataSource physical = context.getBean(
                            "orderDataSource0",
                            DataSource.class
                    );
                    Connection logical = context.getBean(
                            LavShardRoutingDataSource.class
                    ).getConnection();

                    assertThat(logical.getTransactionIsolation())
                            .isEqualTo(
                                    Connection.TRANSACTION_REPEATABLE_READ
                            );
                    verify(physical, never()).getConnection();
                });
    }

    @Test
    void shouldApplyConfiguredDefaultIsolationWithoutOpeningPhysicalConnection()
            throws SQLException {
        contextRunner
                .withUserConfiguration(PhysicalDataSourcesConfiguration.class)
                .withPropertyValues(validProperties("test_identity"))
                .withPropertyValues(
                        "lavshard.integration.default-transaction-isolation="
                                + "read-committed"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DataSource physical = context.getBean(
                            "orderDataSource0",
                            DataSource.class
                    );
                    Connection logical = context.getBean(
                            LavShardRoutingDataSource.class
                    ).getConnection("app", "secret");

                    assertThat(logical.getTransactionIsolation())
                            .isEqualTo(
                                    Connection.TRANSACTION_READ_COMMITTED
                            );
                    verify(physical, never()).getConnection(
                            "app",
                            "secret"
                    );
                });
    }

    @Test
    void shouldRejectUnknownConfiguredTransactionIsolation() {
        contextRunner
                .withUserConfiguration(PhysicalDataSourcesConfiguration.class)
                .withPropertyValues(validProperties("test_identity"))
                .withPropertyValues(
                        "lavshard.integration.default-transaction-isolation="
                                + "dirty-magic"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining(
                                    "dirty-magic"
                            );
                });
    }

    @Test
    void shouldCreateAndRouteToManagedHikariAlongsideReferencedDataSource()
            throws SQLException {
        AtomicReference<HikariDataSource> managedPool =
                new AtomicReference<>();

        contextRunner
                .withUserConfiguration(OnlyFirstDataSourceConfiguration.class)
                .withPropertyValues(mixedDataSourceProperties())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    LavShardManagedDataSourceRegistry registry =
                            context.getBean(
                                    LavShardManagedDataSourceRegistry.class
                            );
                    assertThat(registry.dataSources()).containsOnlyKeys("ds1");
                    HikariDataSource hikari = (HikariDataSource)
                            registry.dataSources().get("ds1");
                    managedPool.set(hikari);

                    assertThat(hikari.getPoolName())
                            .isEqualTo("lavshard-ds1");
                    assertThat(hikari.getMaximumPoolSize()).isEqualTo(4);
                    assertThat(hikari.getMinimumIdle()).isEqualTo(1);
                    assertThat(hikari.getConnectionTimeout()).isEqualTo(1000L);
                    assertThat(hikari.isClosed()).isFalse();

                    LavShardRoutingDataSource routing = context.getBean(
                            LavShardRoutingDataSource.class
                    );
                    MyBatisRouteContext routeContext = context.getBean(
                            MyBatisRouteContext.class
                    );
                    Connection connection = routing.getConnection();

                    try (MyBatisRouteContext.Scope ignored = routeContext.open(
                            new PassThroughDecision("ds1", "SELECT 1")
                    )) {
                        try (Statement statement = connection.createStatement()) {
                            assertThat(statement.execute("SELECT 1")).isTrue();
                        }
                    }

                    connection.close();
                });

        assertThat(managedPool.get()).isNotNull();
        assertThat(managedPool.get().isClosed()).isTrue();
    }

    @Test
    void shouldCreateRoutingDataSourceBeforeBootDefaultWithoutSpringDatasourceUrl()
            throws SQLException {
        contextRunner
                .withPropertyValues(onlyManagedDataSourceProperties())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .hasSingleBean(LavShardManagedDataSourceRegistry.class);
                    assertThat(context)
                            .hasSingleBean(LavShardRoutingDataSource.class);
                    assertThat(context).doesNotHaveBean("dataSource");

                    LavShardRoutingDataSource routing = context.getBean(
                            LavShardRoutingDataSource.class
                    );
                    MyBatisRouteContext routeContext = context.getBean(
                            MyBatisRouteContext.class
                    );
                    Connection connection = routing.getConnection();

                    try (MyBatisRouteContext.Scope ignored = routeContext.open(
                            new PassThroughDecision("ds0", "SELECT 1")
                    )) {
                        assertThat(connection.isValid(1)).isTrue();
                    }

                    connection.close();
                });
    }

    @Test
    void shouldApplyDefaultIsolationToManagedPool() {
        contextRunner
                .withPropertyValues(onlyManagedDataSourceProperties())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    LavShardManagedDataSourceRegistry registry =
                            context.getBean(
                                    LavShardManagedDataSourceRegistry.class
                            );
                    HikariDataSource managed = (HikariDataSource)
                            registry.dataSources().get("ds0");

                    assertThat(managed.getTransactionIsolation())
                            .isEqualTo("TRANSACTION_REPEATABLE_READ");
                });
    }

    @Test
    void shouldKeepConfiguredIsolationConsistentAcrossLogicalPoolAndPhysicalConnection()
            throws SQLException {
        contextRunner
                .withPropertyValues(onlyManagedDataSourceProperties())
                .withPropertyValues(
                        "lavshard.integration.default-transaction-isolation="
                                + "read-committed"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    LavShardManagedDataSourceRegistry registry =
                            context.getBean(
                                    LavShardManagedDataSourceRegistry.class
                            );
                    HikariDataSource managed = (HikariDataSource)
                            registry.dataSources().get("ds0");
                    LavShardRoutingDataSource routing = context.getBean(
                            LavShardRoutingDataSource.class
                    );
                    MyBatisRouteContext routeContext = context.getBean(
                            MyBatisRouteContext.class
                    );

                    assertThat(managed.getTransactionIsolation())
                            .isEqualTo("TRANSACTION_READ_COMMITTED");

                    try (Connection logical = routing.getConnection()) {
                        assertThat(logical.getTransactionIsolation())
                                .isEqualTo(Connection.TRANSACTION_READ_COMMITTED);

                        try (MyBatisRouteContext.Scope ignored = routeContext.open(
                                new PassThroughDecision("ds0", "SELECT 1")
                        )) {
                            try (Statement statement = logical.createStatement()) {
                                assertThat(statement.execute("SELECT 1")).isTrue();
                            }
                        }

                        assertThat(logical.getTransactionIsolation())
                                .isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
                    }
                });
    }

    private static void initialize(
            Connection connection,
            MyBatisRouteContext routeContext,
            SqlRouteDecision decision
    ) throws SQLException {
        try (MyBatisRouteContext.Scope ignored = routeContext.open(decision)) {
            connection.createStatement();
        }
    }

    private static String[] validProperties(String algorithmName) {
        return new String[]{
                "lavshard.integration.default-data-source=ds0",
                "lavshard.integration.ordinary-tables[0]=sys_dict",
                "lavshard.data-sources[ds0].bean-name=orderDataSource0",
                "lavshard.data-sources[ds1].bean-name=orderDataSource1",
                "lavshard.tables[t_order].rule-version=order-rule-v1",
                "lavshard.tables[t_order].sharding-column=user_id",
                "lavshard.tables[t_order].algorithm.name=" + algorithmName,
                "lavshard.tables[t_order].algorithm.hash-version=murmur3_32_v1",
                "lavshard.tables[t_order].topology.version=order-topology-v1",
                "lavshard.tables[t_order].topology.bucket-count=2",
                "lavshard.tables[t_order].topology.bucket-placements[0]=node0",
                "lavshard.tables[t_order].topology.bucket-placements[1]=node1",
                "lavshard.tables[t_order].topology.nodes[node0].data-source=ds0",
                "lavshard.tables[t_order].topology.nodes[node0].actual-table=t_order",
                "lavshard.tables[t_order].topology.nodes[node1].data-source=ds1",
                "lavshard.tables[t_order].topology.nodes[node1].actual-table=t_order"
        };
    }

    private static String[] mixedDataSourceProperties() {
        String databaseName = "managed-" + UUID.randomUUID();
        return new String[]{
                "lavshard.integration.default-data-source=ds0",
                "lavshard.data-sources[ds0].bean-name=orderDataSource0",
                "lavshard.data-sources[ds1].managed.url=jdbc:h2:mem:"
                        + databaseName + ";DB_CLOSE_DELAY=-1",
                "lavshard.data-sources[ds1].managed.username=sa",
                "lavshard.data-sources[ds1].managed.password=",
                "lavshard.data-sources[ds1].managed.driver-class-name=org.h2.Driver",
                "lavshard.data-sources[ds1].managed.maximum-pool-size=4",
                "lavshard.data-sources[ds1].managed.minimum-idle=1",
                "lavshard.data-sources[ds1].managed.connection-timeout=1000",
                "lavshard.tables[t_order].rule-version=order-rule-v1",
                "lavshard.tables[t_order].sharding-column=user_id",
                "lavshard.tables[t_order].algorithm.name=hash_mod",
                "lavshard.tables[t_order].algorithm.hash-version=murmur3_32_v1",
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

    private static String[] onlyManagedDataSourceProperties() {
        String databaseName = "managed-only-" + UUID.randomUUID();
        return new String[]{
                "lavshard.integration.default-data-source=ds0",
                "lavshard.data-sources[ds0].managed.url=jdbc:h2:mem:"
                        + databaseName + ";DB_CLOSE_DELAY=-1",
                "lavshard.data-sources[ds0].managed.username=sa",
                "lavshard.data-sources[ds0].managed.password=",
                "lavshard.data-sources[ds0].managed.driver-class-name=org.h2.Driver"
        };
    }

    @Configuration(proxyBeanMethods = false)
    static class PhysicalDataSourcesConfiguration {

        @Bean
        DataSource orderDataSource0() {
            return mock(DataSource.class);
        }

        @Bean
        DataSource orderDataSource1() {
            return mock(DataSource.class);
        }

        @Bean
        DataSource unrelatedDataSource() {
            return mock(DataSource.class);
        }

        @Bean
        ShardAlgorithm testIdentityAlgorithm() {
            return new IdentityAlgorithm();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OnlyFirstDataSourceConfiguration {

        @Bean
        DataSource orderDataSource0() {
            return mock(DataSource.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class WrongTypeDataSourceConfiguration {

        @Bean
        DataSource orderDataSource0() {
            return mock(DataSource.class);
        }

        @Bean
        String orderDataSource1() {
            return "not-a-data-source";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomRoutingDataSourceConfiguration {

        @Bean
        DataSource orderDataSource0() {
            return mock(DataSource.class);
        }

        @Bean
        DataSource orderDataSource1() {
            return mock(DataSource.class);
        }

        @Bean
        ShardAlgorithm testIdentityAlgorithm() {
            return new IdentityAlgorithm();
        }

        @Bean
        @Primary
        LavShardRoutingDataSource applicationRoutingDataSource(
                MyBatisRouteContext routeContext,
                @Qualifier("orderDataSource0") DataSource dataSource0,
                @Qualifier("orderDataSource1") DataSource dataSource1
        ) {
            return new LavShardRoutingDataSource(
                    Map.of(
                            "ds0", dataSource0,
                            "ds1", dataSource1
                    ),
                    routeContext
            );
        }
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
}
