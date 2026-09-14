package io.github.lavyoung.lavshard.starter.autoconfigure;

import io.github.lavyoung.lavshard.starter.internal.datasource.LavShardManagedDataSourceRegistry;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.CompositeHealthContributor;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.jdbc.DataSourceHealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * LavShard 物理数据源健康检查自动配置。
 *
 * <p>Spring Boot 默认数据库健康检查会访问主 DataSource。LavShard
 * 的主 DataSource 是延迟路由数据源，必须在 MyBatis 建立路由决策后
 * 才能选择物理数据库，因此不能用于无 SQL 上下文的 Actuator 健康检查。</p>
 *
 * <p>本配置接管 Boot 的 {@code dbHealthContributor}，直接检查
 * LavShard 注册表中的每个物理数据源，避免触发逻辑路由连接。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/09/14
 */
@AutoConfiguration(after = LavShardDataSourceAutoConfiguration.class, beforeName = "org.springframework.boot.actuate.autoconfigure.jdbc.DataSourceHealthContributorAutoConfiguration")
@ConditionalOnClass({HealthContributor.class, DataSourceHealthIndicator.class})
@ConditionalOnBean(LavShardManagedDataSourceRegistry.class)
@ConditionalOnProperty(prefix = "lavshard", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnEnabledHealthIndicator("db")
public class LavShardHealthAutoConfiguration {
    /**
     * 创建基于全部物理数据源的数据库健康贡献者。
     *
     * <p>Bean 名使用 Spring Boot 约定的 {@code dbHealthContributor}，
     * 使默认单数据源健康检查自动退让。每个物理数据源以
     * {@code dataSourceId} 作为子贡献者名称。</p>
     *
     * @param registry Starter 托管的物理数据源注册表
     * @return 包含全部物理数据源健康状态的组合贡献者
     */
    @Bean(name = "dbHealthContributor")
    @ConditionalOnMissingBean(name = {"dbHealthIndicator", "dbHealthContributor"})
    public HealthContributor dbHealthContributor(LavShardManagedDataSourceRegistry registry) {
        return CompositeHealthContributor.fromMap(registry.dataSources(), DataSourceHealthIndicator::new);
    }
}
