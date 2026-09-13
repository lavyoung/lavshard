package io.github.lavyoung.lavshard.starter.internal.datasource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lavyoung.lavshard.starter.autoconfigure.config.LavShardProperties;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Starter 创建并拥有的 Hikari 物理连接池注册表。
 *
 * <p>该类型只管理 {@code managed} 模式的数据源。引用应用 Bean
 * 的数据源生命周期仍由应用 Spring 容器负责。</p>
 *
 * <p>全部托管连接池使用配置快照指定的默认事务隔离级别，保证逻辑连接暴露的默认状态与新建物理连接的真实状态一致。</p>
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
     * @param configurations              数据源 ID 到托管配置的映射
     * @param defaultTransactionIsolation 默认 JDBC 事务隔离级别
     * @throws NullPointerException     配置映射为空时抛出
     * @throws IllegalArgumentException 隔离级别不受支持时抛出
     * @throws RuntimeException         任一连接池创建失败时抛出
     */
    public LavShardManagedDataSourceRegistry(Map<String, LavShardProperties.ManagedDataSource> configurations, int defaultTransactionIsolation) {
        Objects.requireNonNull(configurations, "configurations must not be null");

        Map<String, HikariDataSource> created = new LinkedHashMap<>();

        try {
            configurations.forEach((dataSourceId, configuration) -> {
                created.put(dataSourceId, createPool(dataSourceId, configuration, transactionIsolationName(defaultTransactionIsolation)));
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

    /**
     * 创建一个由 Starter 管理的 Hikari 连接池。
     *
     * @param dataSourceId  数据源 ID
     * @param configuration 托管连接池配置
     * @param isolationName Hikari 事务隔离级别名称
     * @return 已启动的 Hikari 数据源
     */
    private static HikariDataSource createPool(String dataSourceId, LavShardProperties.ManagedDataSource configuration, String isolationName) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("lavshard-" + dataSourceId);
        hikari.setJdbcUrl(configuration.url());
        hikari.setUsername(configuration.username());
        hikari.setPassword(configuration.password());
        hikari.setMaximumPoolSize(configuration.maximumPoolSize());
        hikari.setMinimumIdle(configuration.minimumIdle());
        hikari.setConnectionTimeout(configuration.connectionTimeout());
        hikari.setTransactionIsolation(isolationName);

        if (!configuration.driverClassName().isBlank()) {
            hikari.setDriverClassName(configuration.driverClassName());
        }

        return new HikariDataSource(hikari);
    }

    /**
     * 将 JDBC 隔离级别转换成 Hikari 接受的标准名称。
     *
     * @param transactionIsolation JDBC 隔离级别
     * @return Hikari 事务隔离级别名称
     * @throws IllegalArgumentException 隔离级别不受支持时抛出
     */
    private static String transactionIsolationName(int transactionIsolation) {
        return switch (transactionIsolation) {
            case Connection.TRANSACTION_READ_UNCOMMITTED -> "TRANSACTION_READ_UNCOMMITTED";
            case Connection.TRANSACTION_READ_COMMITTED -> "TRANSACTION_READ_COMMITTED";
            case Connection.TRANSACTION_REPEATABLE_READ -> "TRANSACTION_REPEATABLE_READ";
            case Connection.TRANSACTION_SERIALIZABLE -> "TRANSACTION_SERIALIZABLE";
            default ->
                    throw new IllegalArgumentException("Unsupported transaction isolation level: " + transactionIsolation);
        };
    }
}
