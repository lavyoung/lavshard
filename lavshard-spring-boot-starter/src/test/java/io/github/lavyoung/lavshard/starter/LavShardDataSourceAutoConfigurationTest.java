package io.github.lavyoung.lavshard.starter;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardAlgorithm;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardBucket;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardValue;
import io.github.lavyoung.lavshard.core.api.exception.ConfigurationException;
import io.github.lavyoung.lavshard.core.api.route.PassThroughDecision;
import io.github.lavyoung.lavshard.core.api.route.SqlRouteDecision;
import io.github.lavyoung.lavshard.core.internal.route.SqlRouteEngine;
import io.github.lavyoung.lavshard.mybatis.internal.LavShardRoutingDataSource;
import io.github.lavyoung.lavshard.mybatis.internal.MyBatisRouteContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
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
                            LavShardDataSourceAutoConfiguration.class
                    ));

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
                            .doesNotHaveBean("lavShardDataSource");
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
