package io.github.lavyoung.lavshard.starter;

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
import io.github.lavyoung.lavshard.mybatis.internal.LavShardExecutorInterceptor;
import io.github.lavyoung.lavshard.mybatis.internal.MyBatisRouteContext;
import io.github.lavyoung.lavshard.starter.support.SpringShardContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
                    assertThat(context).hasSingleBean(ShardAlgorithmRegistry.class);
                    assertThat(context).hasSingleBean(SqlRouteEngine.class);
                    assertThat(context).hasSingleBean(SpringShardContext.class);
                    assertThat(context).hasSingleBean(MyBatisRouteContext.class);
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
                    assertThat(context).doesNotHaveBean(LavShardExecutorInterceptor.class);
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
                            LavShardDataSourceAutoConfiguration.class.getName()
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
