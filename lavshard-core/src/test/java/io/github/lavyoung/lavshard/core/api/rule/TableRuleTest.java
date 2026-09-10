package io.github.lavyoung.lavshard.core.api.rule;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.api.topology.ShardNode;
import io.github.lavyoung.lavshard.core.api.topology.ShardTopology;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

/**
 *
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/09/10
 */
class TableRuleTest {

    @Test
    void shouldCreateCompleteTableRule() {
        // Given
        AlgorithmConfig config =
                new AlgorithmConfig(2, "murmur3_32_v1");
        ShardTopology topology = topology();

        // When
        TableRule rule = new TableRule(
                "order-rule-v1",
                new QualifiedTableName("t_order"),
                "user_id",
                "hash_mod",
                config,
                topology
        );

        // Then
        assertThat(rule.ruleVersion())
                .isEqualTo("order-rule-v1");
        assertThat(rule.logicalTable())
                .isEqualTo(new QualifiedTableName("t_order"));
        assertThat(rule.shardingColumn())
                .isEqualTo("user_id");
        assertThat(rule.algorithmName())
                .isEqualTo("hash_mod");
        assertThat(rule.algorithmConfig()).isSameAs(config);
        assertThat(rule.topology()).isSameAs(topology);
    }

    @Test
    void shouldRejectBlankRuleVersion() {
        // Given
        ShardTopology topology = topology();

        // When / Then
        assertThatThrownBy(() -> new TableRule(
                " ",
                new QualifiedTableName("t_order"),
                "user_id",
                "hash_mod",
                new AlgorithmConfig(2, "murmur3_32_v1"),
                topology
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ruleVersion must not be blank");
    }

    @Test
    void shouldRejectNullLogicalTable() {
        // Given
        ShardTopology topology = topology();

        // When / Then
        assertThatThrownBy(() -> new TableRule(
                "order-rule-v1",
                null,
                "user_id",
                "hash_mod",
                new AlgorithmConfig(2, "murmur3_32_v1"),
                topology
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("logicalTable must not be null");
    }

    @Test
    void shouldRejectBlankShardingColumn() {
        // Given
        ShardTopology topology = topology();

        // When / Then
        assertThatThrownBy(() -> new TableRule(
                "order-rule-v1",
                new QualifiedTableName("t_order"),
                " ",
                "hash_mod",
                new AlgorithmConfig(2, "murmur3_32_v1"),
                topology
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("shardingColumn must not be blank");
    }

    @Test
    void shouldRejectBlankAlgorithmName() {
        // Given
        ShardTopology topology = topology();

        // When / Then
        assertThatThrownBy(() -> new TableRule(
                "order-rule-v1",
                new QualifiedTableName("t_order"),
                "user_id",
                " ",
                new AlgorithmConfig(2, "murmur3_32_v1"),
                topology
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("algorithmName must not be blank");
    }

    @Test
    void shouldRejectBucketCountMismatch() {
        // Given
        AlgorithmConfig config =
                new AlgorithmConfig(4, "murmur3_32_v1");
        ShardTopology topology = topology();

        // When / Then
        assertThatThrownBy(() -> new TableRule(
                "order-rule-v1",
                new QualifiedTableName("t_order"),
                "user_id",
                "hash_mod",
                config,
                topology
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "algorithm bucketCount must match topology bucketCount"
                );
    }

    private static ShardTopology topology() {
        ShardNode node = new ShardNode(
                "order-00",
                "ds0",
                new QualifiedTableName("t_order_00")
        );

        return new ShardTopology(
                "order-topology-v1",
                2,
                Map.of(
                        0, node.nodeId(),
                        1, node.nodeId()
                ),
                Map.of(node.nodeId(), node)
        );
    }
}
