package io.github.lavyoung.lavshard.starter.internal.config;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.exception.ConfigurationException;
import io.github.lavyoung.lavshard.core.api.rule.RuleSnapshot;
import io.github.lavyoung.lavshard.core.api.rule.TableRule;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.api.topology.ShardNode;
import io.github.lavyoung.lavshard.core.api.topology.ShardTopology;
import io.github.lavyoung.lavshard.starter.autoconfigure.config.LavShardProperties;

import java.util.*;

/**
 * 将 Spring 外部配置编译为 Core 可直接使用的不可变快照。
 *
 * <p>所有引用完整性和结构约束都在启动阶段验证。运行期路由只读取
 * 已编译模型，不重复解析字符串，也不会观察到半构建配置。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/09/12
 */
public final class LavShardConfigurationCompiler {

    /**
     * 编译外部配置。
     *
     * @param properties 配置绑定模型
     * @return 已校验的不可变运行时快照
     * @throws NullPointerException   properties 为空时抛出
     * @throws ConfigurationException 配置不完整或引用非法时抛出
     */
    public LavShardConfigurationSnapshot compile(LavShardProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");

        CompiledDataSources dataSources = compileDataSources(properties.dataSources());

        String defaultDataSourceId = requireText(properties.integration().defaultDataSource(), "lavshard.integration.default-data-source must not be blank");

        if (!dataSources.dataSourceIds().contains(defaultDataSourceId)) {
            throw new ConfigurationException("lavshard.integration.default-data-source " + "references unknown dataSourceId: " + defaultDataSourceId);
        }

        int defaultTransactionIsolation = properties.integration().defaultTransactionIsolation().jdbcLevel();

        Set<QualifiedTableName> ordinaryTables = compileOrdinaryTables(properties.integration().ordinaryTables());

        List<TableRule> rules = compileRules(properties.tables(), dataSources.dataSourceIds());

        rejectManagedOrdinaryOverlap(rules, ordinaryTables);

        return new LavShardConfigurationSnapshot(dataSources.beanNames(), dataSources.managedDataSources(), defaultDataSourceId, defaultTransactionIsolation, ordinaryTables, new RuleSnapshot(rules));
    }

    private static CompiledDataSources compileDataSources(Map<String, LavShardProperties.DataSourceReference> sources) {
        if (sources.isEmpty()) {
            throw new ConfigurationException("lavshard.data-sources must not be empty");
        }

        Map<String, String> beanNames = new LinkedHashMap<>();
        Map<String, LavShardProperties.ManagedDataSource> managedDataSources = new LinkedHashMap<>();

        for (Map.Entry<String, LavShardProperties.DataSourceReference> entry : sources.entrySet()) {
            String dataSourceId = requireText(entry.getKey(), "lavshard.data-sources must not contain " + "blank dataSourceId");
            LavShardProperties.DataSourceReference reference = Objects.requireNonNull(entry.getValue(), "dataSource reference must not be null");

            boolean hasBeanName = !reference.beanName().isEmpty();
            boolean hasManaged = reference.managed() != null;

            if (hasBeanName == hasManaged) {
                throw new ConfigurationException("lavshard.data-sources." + dataSourceId + " must configure exactly one of " + "bean-name or managed");
            }

            if (hasBeanName) {
                String beanName = requireText(reference.beanName(), "lavshard.data-sources." + dataSourceId + ".bean-name must not be blank");
                beanNames.put(dataSourceId, beanName);
                continue;
            }

            managedDataSources.put(dataSourceId, validateManagedDataSource(dataSourceId, reference.managed()));
        }

        return new CompiledDataSources(beanNames, managedDataSources);
    }

    private static Set<QualifiedTableName> compileOrdinaryTables(Set<String> configuredTables) {
        Set<QualifiedTableName> ordinaryTables = new HashSet<>();

        for (String configuredTable : configuredTables) {
            if (configuredTable == null || configuredTable.isBlank()) {
                throw new ConfigurationException("lavshard.integration.ordinary-tables must not contain blank table names");
            }

            ordinaryTables.add(new QualifiedTableName(configuredTable));
        }

        return Set.copyOf(ordinaryTables);
    }

    private static List<TableRule> compileRules(Map<String, LavShardProperties.Table> configuredTables, Set<String> dataSourceIds) {
        List<TableRule> tableRules = new ArrayList<>(configuredTables.size());

        for (Map.Entry<String, LavShardProperties.Table> entry : configuredTables.entrySet()) {
            String logicalTable = requireText(entry.getKey(), "lavshard.tables must not contain blank logical table names");

            LavShardProperties.Table table = Objects.requireNonNull(entry.getValue(), "table configuration must not be null");

            tableRules.add(compileRule(logicalTable, table, dataSourceIds));
        }

        return List.copyOf(tableRules);
    }

    private static TableRule compileRule(String logicalTable, LavShardProperties.Table table, Set<String> dataSourceIds) {
        String tablePath = "lavshard.tables." + logicalTable;
        String ruleVersion = requireText(table.ruleVersion(), tablePath + ".rule-version must not be blank");
        String shardingColumn = requireText(table.shardingColumn(), tablePath + ".sharding-column must not be blank");
        String algorithmName = requireText(table.algorithm().name(), tablePath + ".algorithm.name must not be blank");
        String hashVersion = requireText(table.algorithm().hashVersion(), tablePath + ".algorithm.hash-version must not be blank");

        LavShardProperties.Topology configuredTopology = table.topology();
        requireText(configuredTopology.version(), tablePath + ".topology.version must not be blank");

        Map<String, ShardNode> nodes = compileNodes(tablePath, configuredTopology.nodes(), dataSourceIds);

        try {
            ShardTopology topology = new ShardTopology(configuredTopology.version(), configuredTopology.bucketCount(), configuredTopology.bucketPlacements(), nodes);

            AlgorithmConfig algorithmConfig = new AlgorithmConfig(configuredTopology.bucketCount(), hashVersion);

            return new TableRule(ruleVersion, new QualifiedTableName(logicalTable), shardingColumn, algorithmName, algorithmConfig, topology);
        } catch (IllegalArgumentException exception) {
            throw new ConfigurationException("Invalid " + tablePath + ": " + exception.getMessage(), exception);
        }
    }

    private static Map<String, ShardNode> compileNodes(String tablePath, Map<String, LavShardProperties.Node> configuredNodes, Set<String> dataSourceIds) {
        Map<String, ShardNode> nodes = new HashMap<>();
        for (Map.Entry<String, LavShardProperties.Node> entry : configuredNodes.entrySet()) {
            String nodeId = requireText(entry.getKey(), tablePath + ".topology.nodes must not contain blank nodeId");

            LavShardProperties.Node node = Objects.requireNonNull(entry.getValue(), "node configuration must not be null");

            String dataSourceId = requireText(node.dataSource(), tablePath + ".topology.nodes." + nodeId + ".data-source must not be blank");

            if (!dataSourceIds.contains(dataSourceId)) {
                throw new ConfigurationException(tablePath + ".topology.nodes." + nodeId + ".data-source references " + "unknown dataSourceId: " + dataSourceId);
            }


            String actualTable = requireText(node.actualTable(), tablePath + ".topology.nodes." + nodeId + ".actual-table must not be blank");

            nodes.put(nodeId, new ShardNode(nodeId, dataSourceId, new QualifiedTableName(actualTable)));

        }

        return Map.copyOf(nodes);
    }


    private static void rejectManagedOrdinaryOverlap(List<TableRule> rules, Set<QualifiedTableName> ordinaryTables) {
        for (TableRule rule : rules) {
            if (ordinaryTables.contains(rule.logicalTable())) {
                throw new ConfigurationException("Table cannot be both managed and ordinary: " + rule.logicalTable());
            }
        }
    }

    private static LavShardProperties.ManagedDataSource validateManagedDataSource(String dataSourceId, LavShardProperties.ManagedDataSource managed) {
        String path = "lavshard.data-sources." + dataSourceId + ".managed";

        requireText(managed.url(), path + ".url must not be blank");

        if (managed.maximumPoolSize() <= 0) {
            throw new ConfigurationException(path + ".maximum-pool-size " + "must be greater than zero");
        }

        if (managed.minimumIdle() < 0 || managed.minimumIdle() > managed.maximumPoolSize()) {
            throw new ConfigurationException(path + ".minimum-idle must be between zero " + "and maximum-pool-size");
        }

        if (managed.connectionTimeout() < 250L) {
            throw new ConfigurationException(path + ".connection-timeout " + "must be at least 250 milliseconds");
        }

        return managed;
    }

    /**
     * 已完成校验的两类物理数据源配置。
     */
    private record CompiledDataSources(Map<String, String> beanNames,
                                       Map<String, LavShardProperties.ManagedDataSource> managedDataSources) {

        private CompiledDataSources {
            beanNames = Map.copyOf(beanNames);
            managedDataSources = Map.copyOf(managedDataSources);
        }

        private Set<String> dataSourceIds() {
            Set<String> ids = new HashSet<>(beanNames.keySet());
            ids.addAll(managedDataSources.keySet());
            return Set.copyOf(ids);
        }
    }

    private static String requireText(String value, String failureMessage) {
        if (value == null || value.isBlank()) {
            throw new ConfigurationException(failureMessage);
        }
        return value;
    }
}
