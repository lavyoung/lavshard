package io.github.lavyoung.lavshard.starter.internal.datasource;

import io.github.lavyoung.lavshard.core.api.exception.ConfigurationException;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Objects;

/**
 * 启动期已解析的物理数据源目录。
 *
 * <p>目录只保存配置明确声明的数据源，不会扫描 Spring 容器中的全部
 * {@link DataSource} Bean，避免无关数据源被误用于路由或广播扫描。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.1
 * @date 2026/10/08
 */
public final class LavShardPhysicalDataSourceCatalog {

    private final Map<String, DataSource> dataSources;

    /**
     * 创建不可变物理数据源目录。
     *
     * @param dataSources 数据源标识到物理数据源的映射
     * @throws NullPointerException 映射或其中的数据源为空时抛出
     */
    public LavShardPhysicalDataSourceCatalog(Map<String, DataSource> dataSources) {
        Objects.requireNonNull(dataSources, "dataSources must not be null");
        this.dataSources = Map.copyOf(dataSources);
    }

    /**
     * 返回全部已解析物理数据源。
     *
     * @return 不可修改的数据源映射
     */
    public Map<String, DataSource> dataSources() {
        return dataSources;
    }

    /**
     * 按数据源标识获取一个物理数据源。
     *
     * @param dataSourceId LavShard 数据源标识
     * @return 对应的物理数据源
     * @throws ConfigurationException 数据源标识不存在时抛出
     */
    public DataSource requireDataSource(String dataSourceId) {
        DataSource dataSource = dataSources.get(dataSourceId);
        if (dataSource == null) {
            throw new ConfigurationException(
                    "Configured physical DataSource does not exist: dataSourceId=" + dataSourceId
            );
        }
        return dataSource;
    }
}
