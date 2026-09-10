package io.github.lavyoung.lavshard.core.api.rule;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.api.topology.ShardTopology;

import java.util.Objects;

/**
 * 不可变的分片表规则。
 *
 * <p>规则负责把逻辑表、分片列、算法配置和静态拓扑组合为一个完整配置单元。
 * 它只描述路由所需的数据，不持有算法实例、数据库连接或框架对象。
 *
 * @param ruleVersion     规则版本
 * @param logicalTable    受管逻辑表
 * @param shardingColumn  分片列
 * @param algorithmName   注册表中的算法名称
 * @param algorithmConfig 算法配置
 * @param topology        静态分片拓扑
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/10
 */
public record TableRule(
        String ruleVersion,
        QualifiedTableName logicalTable,
        String shardingColumn,
        String algorithmName,
        AlgorithmConfig algorithmConfig,
        ShardTopology topology
) {


    public TableRule {
        requireText(ruleVersion, "ruleVersion");
        Objects.requireNonNull(logicalTable, "logicalTable must not be null");
        requireText(shardingColumn, "shardingColumn");
        requireText(algorithmName, "algorithmName");
        Objects.requireNonNull(algorithmConfig, "algorithmConfig must not be null");
        Objects.requireNonNull(topology, "topology must not be null");

        if (algorithmConfig.bucketCount() != topology.bucketCount()) {
            throw new IllegalArgumentException(
                    "algorithm bucketCount must match topology bucketCount"
            );
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
