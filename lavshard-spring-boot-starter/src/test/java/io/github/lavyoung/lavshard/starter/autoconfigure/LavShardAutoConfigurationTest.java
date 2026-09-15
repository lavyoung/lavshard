package io.github.lavyoung.lavshard.starter.autoconfigure;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardAlgorithm;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardBucket;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardValue;
import io.github.lavyoung.lavshard.core.api.exception.ConfigurationException;
import io.github.lavyoung.lavshard.core.api.route.ManagedRouteDecision;
import io.github.lavyoung.lavshard.core.api.route.PassThroughDecision;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.internal.algorithm.ShardAlgorithmRegistry;
import io.github.lavyoung.lavshard.core.internal.route.SqlRouteEngine;
import io.github.lavyoung.lavshard.mybatis.internal.executor.LavShardExecutorInterceptor;
import io.github.lavyoung.lavshard.mybatis.internal.executor.MyBatisIntegrationScope;
import io.github.lavyoung.lavshard.mybatis.internal.routing.MyBatisRouteContext;
import io.github.lavyoung.lavshard.starter.autoconfigure.config.LavShardProperties;
import io.github.lavyoung.lavshard.starter.internal.config.LavShardAlgorithmConfigurationValidator;
import io.github.lavyoung.lavshard.starter.internal.config.LavShardConfigurationCompiler;
import io.github.lavyoung.lavshard.starter.internal.config.LavShardConfigurationSnapshot;
import io.github.lavyoung.lavshard.starter.internal.transaction.SpringShardContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LavShard 核心路由 Bean 自动配置契约。
 */
class LavShardAutoConfigurationTest {

    private static final String AUTO_CONFIGURATION_IMPORTS =
            "META-INF/spring/org.springframework.boot.autoconfigure."
                    + "AutoConfiguration.imports";

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            LavShardAutoConfiguration.class
                    ));

    @Test
    void shouldAutoConfigureCompleteRoutingBeanGraphWhenEnabledByDefault() {
        contextRunner
                .withPropertyValues(validProperties("hash_mod"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LavShardProperties.class);
                    assertThat(context).hasSingleBean(LavShardConfigurationCompiler.class);
                    assertThat(context).hasSingleBean(LavShardConfigurationSnapshot.class);
                    assertThat(context).hasSingleBean(
                            LavShardAlgorithmConfigurationValidator.class
                    );
                    assertThat(context).hasSingleBean(ShardAlgorithmRegistry.class);
                    assertThat(context).hasSingleBean(SqlRouteEngine.class);
                    assertThat(context).hasSingleBean(SpringShardContext.class);
                    assertThat(context).hasSingleBean(MyBatisRouteContext.class);
                    assertThat(context).hasSingleBean(MyBatisIntegrationScope.class);
                    assertThat(context).hasSingleBean(LavShardExecutorInterceptor.class);
                    assertThat(context.getBean(LavShardProperties.class).enabled()).isTrue();
                });
    }

    @Test
    void shouldNotCreateLavShardBeansWhenExplicitlyDisabled() {
        contextRunner
                .withPropertyValues("lavshard.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(LavShardProperties.class);
                    assertThat(context).doesNotHaveBean(LavShardConfigurationSnapshot.class);
                    assertThat(context).doesNotHaveBean(ShardAlgorithmRegistry.class);
                    assertThat(context).doesNotHaveBean(SqlRouteEngine.class);
                    assertThat(context).doesNotHaveBean(MyBatisRouteContext.class);
                    assertThat(context).doesNotHaveBean(MyBatisIntegrationScope.class);
                    assertThat(context).doesNotHaveBean(LavShardExecutorInterceptor.class);
                });
    }

    @Test
    void shouldBindManagedMapperPackagesIntoIntegrationScope() {
        contextRunner
                .withPropertyValues(validProperties("hash_mod"))
                .withPropertyValues(
                        "lavshard.integration.managed-mapper-packages[0]="
                                + "com.acme.order.mapper",
                        "lavshard.integration.managed-mapper-packages[1]="
                                + "com.acme.billing.mapper.InvoiceMapper"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    LavShardProperties properties = context.getBean(
                            LavShardProperties.class
                    );
                    MyBatisIntegrationScope scope = context.getBean(
                            MyBatisIntegrationScope.class
                    );

                    assertThat(properties.integration().managedMapperPackages())
                            .containsExactlyInAnyOrder(
                                    "com.acme.order.mapper",
                                    "com.acme.billing.mapper.InvoiceMapper"
                            );
                    assertThat(scope.includes(
                            "com.acme.order.mapper.OrderMapper.select"
                    )).isTrue();
                    assertThat(scope.includes(
                            "com.acme.order.mapper.archive.ArchiveMapper.insert"
                    )).isTrue();
                    assertThat(scope.includes(
                            "com.acme.billing.mapper.InvoiceMapper.select"
                    )).isTrue();
                    assertThat(scope.includes(
                            "com.acme.customer.mapper.CustomerMapper.select"
                    )).isFalse();
                });
    }

    @Test
    void shouldManageAllMappersWhenManagedPackagesAreNotConfigured() {
        contextRunner
                .withPropertyValues(validProperties("hash_mod"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    LavShardProperties properties = context.getBean(
                            LavShardProperties.class
                    );
                    MyBatisIntegrationScope scope = context.getBean(
                            MyBatisIntegrationScope.class
                    );

                    assertThat(properties.integration().managedMapperPackages())
                            .isEmpty();
                    assertThat(scope.includes(
                            "any.application.Mapper.select"
                    )).isTrue();
                });
    }

    @Test
    void shouldFailStartupWhenManagedMapperPackageIsBlank() {
        contextRunner
                .withPropertyValues(validProperties("hash_mod"))
                .withPropertyValues(
                        "lavshard.integration.managed-mapper-packages[0]= "
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasRootCauseMessage(
                                    "managedMapperPackages must not contain "
                                            + "blank package names"
                            );
                });
    }

    @Test
    void shouldBackOffAndWireApplicationProvidedIntegrationScope() {
        contextRunner
                .withUserConfiguration(CustomIntegrationScopeConfiguration.class)
                .withPropertyValues(validProperties("hash_mod"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MyBatisIntegrationScope applicationScope = context.getBean(
                            "applicationIntegrationScope",
                            MyBatisIntegrationScope.class
                    );
                    LavShardExecutorInterceptor interceptor = context.getBean(
                            LavShardExecutorInterceptor.class
                    );

                    assertThat(context).hasSingleBean(MyBatisIntegrationScope.class);
                    assertThat(context.getBean(MyBatisIntegrationScope.class))
                            .isSameAs(applicationScope);
                    assertThat(ReflectionTestUtils.getField(
                            interceptor,
                            "integrationScope"
                    )).isSameAs(applicationScope);
                });
    }

    @Test
    void shouldRouteManagedAndPassThroughSqlUsingCompiledConfiguration() {
        contextRunner
                .withPropertyValues(validProperties("hash_mod"))
                .run(context -> {
                    SqlRouteEngine routeEngine = context.getBean(SqlRouteEngine.class);
                    LavShardConfigurationSnapshot snapshot = context.getBean(
                            LavShardConfigurationSnapshot.class
                    );

                    assertThat(routeEngine.decide(
                            snapshot.ruleSnapshot(),
                            "SELECT * FROM t_order WHERE user_id = ?",
                            List.of("user-1")
                    )).isInstanceOfSatisfying(
                            ManagedRouteDecision.class,
                            decision -> assertThat(
                                    decision.routePlan()
                                            .units()
                                            .get(0)
                                            .target()
                                            .node()
                                            .actualTable()
                            ).isEqualTo(new QualifiedTableName("t_order_00"))
                    );

                    assertThat(routeEngine.decide(
                            snapshot.ruleSnapshot(),
                            "SELECT * FROM sys_dict",
                            List.of()
                    )).isInstanceOfSatisfying(
                            PassThroughDecision.class,
                            decision -> assertThat(decision.dataSourceId())
                                    .isEqualTo("ds0")
                    );
                });
    }

    @Test
    void shouldAutoConfigureAndRouteUsingDefaultLayoutWithOnlyShardingColumn() {
        contextRunner
                .withPropertyValues(conciseLayoutProperties())
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    LavShardConfigurationSnapshot snapshot = context.getBean(
                            LavShardConfigurationSnapshot.class
                    );
                    SqlRouteEngine routeEngine = context.getBean(SqlRouteEngine.class);

                    assertThat(snapshot.ruleSnapshot().rules())
                            .singleElement()
                            .satisfies(rule -> {
                                assertThat(rule.shardingColumn()).isEqualTo("user_id");
                                assertThat(rule.topology().bucketCount()).isEqualTo(16);
                                assertThat(rule.topology().nodes().values())
                                        .extracting(node -> node.dataSourceId())
                                        .containsExactlyInAnyOrder(
                                                "ds0", "ds0", "ds0", "ds0",
                                                "ds1", "ds1", "ds1", "ds1"
                                        );
                            });

                    assertThat(routeEngine.decide(
                            snapshot.ruleSnapshot(),
                            "SELECT * FROM t_order WHERE user_id = ?",
                            List.of("user-1")
                    )).isInstanceOfSatisfying(
                            ManagedRouteDecision.class,
                            decision -> {
                                assertThat(decision.routePlan()
                                        .units()
                                        .get(0)
                                        .target()
                                        .node()
                                        .dataSourceId())
                                        .isIn("ds0", "ds1");
                                assertThat(decision.routePlan()
                                        .units()
                                        .get(0)
                                        .target()
                                        .node()
                                        .actualTable()
                                        .table())
                                        .matches("t_order_0[0-3]");
                            }
                    );
                });
    }

    @Test
    void shouldFailStartupWhenDefaultLayoutReferencesUnknownDataSource() {
        contextRunner
                .withPropertyValues(conciseLayoutProperties())
                .withPropertyValues(
                        "lavshard.layouts[standard].data-source-ids[1]=missing"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(ConfigurationException.class)
                            .hasRootCauseMessage(
                                    "lavshard.layouts.standard.data-source-ids "
                                            + "references unknown dataSourceId: missing"
                            );
                });
    }

    @Test
    void shouldAutoConfigureDatabaseOnlyLayoutWithSamePhysicalTableName() {
        contextRunner
                .withPropertyValues(conciseDatabaseOnlyProperties())
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    LavShardConfigurationSnapshot snapshot = context.getBean(
                            LavShardConfigurationSnapshot.class
                    );
                    var rule = snapshot.ruleSnapshot()
                            .find(new QualifiedTableName("t_order"))
                            .orElseThrow();

                    assertThat(rule.topology().nodes()).containsOnlyKeys(
                            "t_order@ds0",
                            "t_order@ds1"
                    );
                    assertThat(rule.topology().nodes().values())
                            .allSatisfy(node -> assertThat(
                                    node.actualTable().table()
                            ).isEqualTo("t_order"));
                });
    }

    @Test
    void shouldMergeApplicationShardAlgorithmWithBuiltInAlgorithms() {
        contextRunner
                .withUserConfiguration(CustomAlgorithmConfiguration.class)
                .withPropertyValues(validProperties("test_constant"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ShardAlgorithmRegistry registry = context.getBean(
                            ShardAlgorithmRegistry.class
                    );
                    assertThat(registry.find("hash_mod")).isPresent();
                    assertThat(registry.find("test_constant"))
                            .containsSame(context.getBean(
                                    "testConstantAlgorithm",
                                    ShardAlgorithm.class
                            ));

                    SqlRouteEngine routeEngine = context.getBean(SqlRouteEngine.class);
                    LavShardConfigurationSnapshot snapshot = context.getBean(
                            LavShardConfigurationSnapshot.class
                    );
                    assertThat(routeEngine.decide(
                            snapshot.ruleSnapshot(),
                            "SELECT * FROM t_order WHERE user_id = ?",
                            List.of("any-value")
                    )).isInstanceOf(ManagedRouteDecision.class);
                });
    }

    @Test
    void shouldFailStartupWhenApplicationDuplicatesBuiltInAlgorithmName() {
        contextRunner
                .withUserConfiguration(DuplicateAlgorithmConfiguration.class)
                .withPropertyValues(validProperties("hash_mod"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasRootCauseMessage(
                                    "duplicate shard algorithm name: hash_mod"
                            );
                });
    }

    @Test
    void shouldBackOffWhenApplicationProvidesAlgorithmRegistry() {
        contextRunner
                .withUserConfiguration(CustomRegistryConfiguration.class)
                .withPropertyValues(validProperties("test_constant"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ShardAlgorithmRegistry.class);
                    assertThat(context.getBean(ShardAlgorithmRegistry.class))
                            .isSameAs(context.getBean("applicationAlgorithmRegistry"));
                    assertThat(context.getBean(ShardAlgorithmRegistry.class)
                            .find("test_constant")).isPresent();
                });
    }

    @Test
    void shouldFailContextBeforeRoutingWhenConfigurationIsInvalid() {
        contextRunner
                .withPropertyValues(
                        "lavshard.integration.default-data-source=missing",
                        "lavshard.data-sources[ds0].bean-name=orderDataSource0"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(ConfigurationException.class)
                            .hasRootCauseMessage(
                                    "lavshard.integration.default-data-source "
                                            + "references unknown dataSourceId: missing"
                            );
                });
    }

    @Test
    void shouldFailStartupWhenRuleReferencesUnregisteredAlgorithm() {
        contextRunner
                .withPropertyValues(validProperties("missing"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(ConfigurationException.class)
                            .hasRootCauseMessage(
                                    "lavshard.tables.t_order.algorithm.name "
                                            + "references unregistered "
                                            + "algorithm: missing"
                            );
                });
    }

    @Test
    void shouldFailStartupWhenBuiltInAlgorithmRejectsConfiguration() {
        contextRunner
                .withPropertyValues(validProperties("hash_mod"))
                .withPropertyValues(
                        "lavshard.tables[t_order].algorithm."
                                + "hash-version=unknown"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .cause()
                            .cause()
                            .isInstanceOf(ConfigurationException.class)
                            .hasMessage(
                                    "Invalid algorithm configuration for "
                                            + "table t_order: unsupported "
                                            + "hashVersion: unknown"
                            );
                });
    }

    @Test
    void shouldPublishAutoConfigurationImportsMetadata() throws IOException {
        ClassLoader classLoader = LavShardAutoConfigurationTest.class.getClassLoader();

        try (InputStream input = classLoader.getResourceAsStream(
                AUTO_CONFIGURATION_IMPORTS
        )) {
            assertThat(input).isNotNull();
            String imports = new String(
                    input.readAllBytes(),
                    StandardCharsets.UTF_8
            );
            assertThat(imports.lines())
                    .containsExactly(
                            LavShardAutoConfiguration.class.getName(),
                            LavShardDataSourceAutoConfiguration.class.getName(),
                            LavShardHealthAutoConfiguration.class.getName()
                    );
        }
    }

    private static String[] validProperties(String algorithmName) {
        return new String[]{
                "lavshard.integration.default-data-source=ds0",
                "lavshard.integration.ordinary-tables[0]=sys_dict",
                "lavshard.data-sources[ds0].bean-name=orderDataSource0",
                "lavshard.tables[t_order].rule-version=order-rule-v1",
                "lavshard.tables[t_order].sharding-column=user_id",
                "lavshard.tables[t_order].algorithm.name=" + algorithmName,
                "lavshard.tables[t_order].algorithm.hash-version=murmur3_32_v1",
                "lavshard.tables[t_order].topology.version=order-topology-v1",
                "lavshard.tables[t_order].topology.bucket-count=1",
                "lavshard.tables[t_order].topology.bucket-placements[0]=node0",
                "lavshard.tables[t_order].topology.nodes[node0].data-source=ds0",
                "lavshard.tables[t_order].topology.nodes[node0].actual-table=t_order_00"
        };
    }

    private static String[] conciseLayoutProperties() {
        return new String[]{
                "lavshard.integration.default-data-source=ds0",
                "lavshard.data-sources[ds0].bean-name=orderDataSource0",
                "lavshard.data-sources[ds1].bean-name=orderDataSource1",
                "lavshard.defaults.layout=standard",
                "lavshard.layouts[standard].version=layout-v1",
                "lavshard.layouts[standard].data-source-ids[0]=ds0",
                "lavshard.layouts[standard].data-source-ids[1]=ds1",
                "lavshard.layouts[standard].tables-per-data-source=4",
                "lavshard.layouts[standard].bucket-count=16",
                "lavshard.tables[t_order].sharding-column=user_id"
        };
    }

    private static String[] conciseDatabaseOnlyProperties() {
        return new String[]{
                "lavshard.integration.default-data-source=ds0",
                "lavshard.data-sources[ds0].bean-name=orderDataSource0",
                "lavshard.data-sources[ds1].bean-name=orderDataSource1",
                "lavshard.defaults.layout=database-only",
                "lavshard.layouts[database-only].version=database-v1",
                "lavshard.layouts[database-only].data-source-ids[0]=ds0",
                "lavshard.layouts[database-only].data-source-ids[1]=ds1",
                "lavshard.layouts[database-only].tables-per-data-source=1",
                "lavshard.layouts[database-only].bucket-count=16",
                "lavshard.layouts[database-only].table-suffix.enabled=false",
                "lavshard.tables[t_order].sharding-column=user_id"
        };
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomAlgorithmConfiguration {

        @Bean
        ShardAlgorithm testConstantAlgorithm() {
            return new ConstantAlgorithm("test_constant");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DuplicateAlgorithmConfiguration {

        @Bean
        ShardAlgorithm duplicateBuiltInAlgorithm() {
            return new ConstantAlgorithm("hash_mod");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomRegistryConfiguration {

        @Bean
        ShardAlgorithmRegistry applicationAlgorithmRegistry() {
            return new ShardAlgorithmRegistry(
                    List.of(new ConstantAlgorithm("test_constant"))
            );
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomIntegrationScopeConfiguration {

        @Bean
        MyBatisIntegrationScope applicationIntegrationScope() {
            return MyBatisIntegrationScope.of(List.of(
                    "com.acme.application.mapper"
            ));
        }
    }

    private record ConstantAlgorithm(
            String name
    ) implements ShardAlgorithm {

        @Override
        public ShardBucket calculate(
                ShardValue value,
                AlgorithmConfig config
        ) {
            return new ShardBucket(0);
        }
    }
}
