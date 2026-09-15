package io.github.lavyoung.lavshard.starter.internal.config;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.exception.ConfigurationException;
import io.github.lavyoung.lavshard.core.api.rule.TableRule;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.api.topology.ShardNode;
import io.github.lavyoung.lavshard.core.api.topology.ShardTopology;
import io.github.lavyoung.lavshard.starter.autoconfigure.config.LavShardProperties;

import java.util.*;

/**
 * 将简化分片布局展开成完整的 Core 表规则。
 *
 * <p>该编译器只在应用启动阶段运行。Core 路由热路径最终仍然读取
 * 完整且不可变的节点和逻辑桶映射，不依赖配置模板。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/09/15
 */
final class LavShardLayoutCompiler {

    /**
     * 校验一个布局模板。
     *
     * @param layoutName    布局名称
     * @param layout        布局配置
     * @param dataSourceIds 已声明的数据源 ID
     * @throws NullPointerException   参数为空时抛出
     * @throws ConfigurationException 布局配置不合法时抛出
     */
    void validate(String layoutName, LavShardProperties.Layout layout, Set<String> dataSourceIds) {
        Objects.requireNonNull(layout, "layout must not be null");
        Objects.requireNonNull(dataSourceIds, "dataSourceIds must not be null");

        String path = "lavshard.layouts." + layoutName;
        requireText(layout.version(), path + ".version must not be blank");
        validateDataSource(path, layout.dataSourceIds(), dataSourceIds);
        validateCounts(path, layout);
        validateTableSuffix(path, layout);

        requireText(layout.algorithm().name(), path + ".algorithm.name must not be blank");
        requireText(layout.algorithm().hashVersion(), path + ".algorithm.hash-version must not be blank");

        if (layout.placementStrategy() != LavShardProperties.PlacementStrategy.ROUND_ROBIN) {
            throw new ConfigurationException(path + ".placement-strategy is not supported: " + layout.placementStrategy());
        }

    }

    /**
     * 将一张逻辑表和布局模板编译成完整表规则。
     *
     * @param logicalTable   逻辑表名
     * @param shardingColumn 分片列
     * @param layoutName     布局名称
     * @param layout         布局模板
     * @param dataSourceIds  已声明的数据源 ID
     * @return 完整且不可变的表规则
     * @throws NullPointerException   参数为空时抛出
     * @throws ConfigurationException 布局配置不合法时抛出
     */
    TableRule compile(String logicalTable, String shardingColumn, String layoutName, LavShardProperties.Layout layout, Set<String> dataSourceIds) {
        Objects.requireNonNull(logicalTable, "logicalTable must not be null");
        Objects.requireNonNull(shardingColumn, "shardingColumn must not be null");

        validate(layoutName, layout, dataSourceIds);
        List<ShardNode> orderedNodes = createOrderedNodes(logicalTable, layout);
        Map<String, ShardNode> nodes = indexNodes(orderedNodes);
        Map<Integer, String> placements = createPlacements(layout.bucketCount(), orderedNodes);

        AlgorithmConfig algorithmConfig = new AlgorithmConfig(layout.bucketCount(), layout.algorithm().hashVersion());

        ShardTopology topology = new ShardTopology(
                layout.version() + "/" + logicalTable + "/topology",
                layout.bucketCount(),
                placements,
                nodes
        );

        return new TableRule(
                layout.version() + "/" + logicalTable + "/rule",
                new QualifiedTableName(logicalTable),
                shardingColumn,
                layout.algorithm().name(),
                algorithmConfig,
                topology
        );
    }

    private static void validateDataSource(String path, List<String> configureIds, Set<String> availableIds) {
        if (configureIds.isEmpty()) {
            throw new ConfigurationException(path + ".data-source-ids must not be empty");
        }

        Set<String> seen = new HashSet<>();

        for (String dataSourceId : configureIds) {
            if (dataSourceId == null || dataSourceId.isBlank()) {
                throw new ConfigurationException(path + ".data-source-ids must not contain blank dataSourceId");
            }

            if (!seen.add(dataSourceId)) {
                throw new ConfigurationException(path + ".data-source-ids must not contain duplicates: " + dataSourceId);
            }

            if (!availableIds.contains(dataSourceId)) {
                throw new ConfigurationException(path + ".data-source-ids references unknown dataSourceId: " + dataSourceId);
            }
        }
    }

    private static void validateCounts(String path, LavShardProperties.Layout layout) {
        if (layout.tablesPerDataSource() <= 0) {
            throw new ConfigurationException(path + ".tables-per-data-source must be greater than zero");
        }

        if (layout.bucketCount() <= 0) {
            throw new ConfigurationException(path + ".bucket-count must be greater than zero");
        }

        long physicalNodeCount = (long) layout.dataSourceIds().size() * layout.tablesPerDataSource();
        if (physicalNodeCount > Integer.MAX_VALUE) {
            throw new ConfigurationException(path + " physical node count exceeds supported integer range");
        }

        if (layout.bucketCount() < physicalNodeCount) {
            throw new ConfigurationException(path + ".bucket-count must be greater than or equal " + "to physical node count: " + physicalNodeCount);
        }
    }

    private static List<ShardNode> createOrderedNodes(String logicalTable, LavShardProperties.Layout layout) {
        List<ShardNode> nodes = new ArrayList<>();

        for (String dataSourceId : layout.dataSourceIds()) {
            if (!layout.tableSuffix().enabled()) {
                nodes.add(new ShardNode(
                        logicalTable + "@" + dataSourceId,
                        dataSourceId,
                        new QualifiedTableName(logicalTable)
                ));
                continue;
            }

            for (int tableIndex = 0; tableIndex < layout.tablesPerDataSource(); tableIndex++) {
                int suffixNumber = layout.tableSuffix().start() + tableIndex;
                String suffix = formatSuffix(suffixNumber, layout.tableSuffix().width());

                String nodeId = logicalTable + "@" + dataSourceId + "@" + suffix;
                String actualTable = logicalTable + layout.tableSuffix().separator() + suffix;

                nodes.add(new ShardNode(nodeId, dataSourceId, new QualifiedTableName(actualTable)));
            }
        }

        return List.copyOf(nodes);
    }

    private static Map<String, ShardNode> indexNodes(List<ShardNode> orderedNodes) {
        Map<String, ShardNode> indexed = new LinkedHashMap<>();

        for (ShardNode node : orderedNodes) {
            ShardNode previous = indexed.put(node.nodeId(), node);
            if (previous != null) {
                throw new ConfigurationException("Generated duplicate nodeId: " + node.nodeId());
            }
        }

        return Map.copyOf(indexed);
    }

    private static Map<Integer, String> createPlacements(int bucketCount, List<ShardNode> orderedNodes) {
        Map<Integer, String> placements = new LinkedHashMap<>();

        for (int bucket = 0; bucket < bucketCount; bucket++) {
            ShardNode node = orderedNodes.get(bucket % orderedNodes.size());
            placements.put(bucket, node.nodeId());
        }

        return Map.copyOf(placements);
    }

    private static String formatSuffix(int value, int width) {
        return String.format(Locale.ROOT, "%0" + width + "d", value);
    }

    private static void validateTableSuffix(String path, LavShardProperties.Layout layout) {
        LavShardProperties.TableSuffix suffix = layout.tableSuffix();

        if (!suffix.enabled() && layout.tablesPerDataSource() != 1) {
            throw new ConfigurationException(path + ".tables-per-data-source must equal one when table-suffix.enabled is false");
        }

        if (suffix.start() < 0) {
            throw new ConfigurationException(path + ".table-suffix.start must not be negative");
        }

        if (suffix.width() <= 0) {
            throw new ConfigurationException(path + ".table-suffix.width must be greater than zero");
        }
    }

    private static String requireText(String value, String failureMessage) {
        if (value == null || value.isBlank()) {
            throw new ConfigurationException(failureMessage);
        }
        return value;
    }
}
