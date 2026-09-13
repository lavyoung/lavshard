package io.github.lavyoung.lavshard.starter.internal.datasource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lavyoung.lavshard.starter.autoconfigure.config.LavShardProperties;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Starter 创建并拥有的 Hikari 物理连接池注册表。
 *
 * <p>该类型只管理 {@code managed} 模式的数据源。引用应用 Bean
 * 的数据源生命周期仍由应用 Spring 容器负责。</p>
 *
 * <p>注册表关闭时会关闭全部托管连接池；关闭操作可重复执行。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/09/13
 */
public final class LavShardManagedDataSourceRegistry implements AutoCloseable {

    private final Map<String, HikariDataSource> managedPools;
    private final Map<String, DataSource> dataSources;

    /**
     * 根据已校验的配置创建全部托管连接池。
     *
     * @param configurations 数据源 ID 到托管配置的映射
     * @throws NullPointerException 配置映射为空时抛出
     * @throws RuntimeException     任一连接池创建失败时抛出
     */
    public LavShardManagedDataSourceRegistry(Map<String, LavShardProperties.ManagedDataSource> configurations) {
        Objects.requireNonNull(configurations, "configurations must not be null");

        Map<String, HikariDataSource> created = new LinkedHashMap<>();

        try {
            configurations.forEach((dataSourceId, configuration) -> {
                created.put(dataSourceId, createPool(dataSourceId, configuration));
            });
        } catch (RuntimeException exception) {
            created.values().forEach(HikariDataSource::close);
            throw exception;
        }

        managedPools = Map.copyOf(created);
        dataSources = Map.copyOf(new LinkedHashMap<>(created));
    }

    /**
     * 返回 dataSourceId 到托管物理数据源的不可变映射。
     *
     * @return 不可变物理数据源映射
     */
    public Map<String, DataSource> dataSources() {
        return dataSources;
    }

    @Override
    public void close() throws Exception {
        managedPools.values().forEach(HikariDataSource::close);
    }

    private static HikariDataSource createPool(String dataSourceId, LavShardProperties.ManagedDataSource configuration) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("lavshard-" + dataSourceId);
        hikari.setJdbcUrl(configuration.url());
        hikari.setUsername(configuration.username());
        hikari.setPassword(configuration.password());
        hikari.setMaximumPoolSize(configuration.maximumPoolSize());
        hikari.setMinimumIdle(configuration.minimumIdle());
        hikari.setConnectionTimeout(configuration.connectionTimeout());

        if (!configuration.driverClassName().isBlank()) {
            hikari.setDriverClassName(configuration.driverClassName());
        }
        return new HikariDataSource(hikari);
    }
}
