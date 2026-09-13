package io.github.lavyoung.lavshard.starter.internal.config;

import io.github.lavyoung.lavshard.core.api.rule.RuleSnapshot;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.starter.autoconfigure.config.LavShardProperties;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 启动期配置编译产生的不可变运行时快照。
 *
 * @param dataSourceBeanNames 数据源 ID 到 Spring Bean 名称的映射
 * @param managedDataSources  连接池配置。
 * @param defaultDataSourceId 普通 SQL 使用的默认数据源 ID
 * @param ordinaryTables      明确允许透传的普通表
 * @param ruleSnapshot        Core 分片规则快照
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/09/12
 */
public record LavShardConfigurationSnapshot(
        Map<String, String> dataSourceBeanNames,
        Map<String, LavShardProperties.ManagedDataSource> managedDataSources,
        String defaultDataSourceId,
        Set<QualifiedTableName> ordinaryTables,
        RuleSnapshot ruleSnapshot
) {

    public LavShardConfigurationSnapshot {
        Objects.requireNonNull(dataSourceBeanNames, "dataSourceBeanNames must not be null");
        Objects.requireNonNull(managedDataSources, "managedDataSources must not be null");
        Objects.requireNonNull(defaultDataSourceId, "defaultDataSourceId must not be null");
        Objects.requireNonNull(ordinaryTables, "ordinaryTables must not be null");
        Objects.requireNonNull(ruleSnapshot, "ruleSnapshot must not be null");

        dataSourceBeanNames = Map.copyOf(dataSourceBeanNames);
        managedDataSources = Map.copyOf(managedDataSources);
        ordinaryTables = Set.copyOf(ordinaryTables);
    }
}
