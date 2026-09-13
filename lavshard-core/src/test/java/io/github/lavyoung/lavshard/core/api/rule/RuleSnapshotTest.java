package io.github.lavyoung.lavshard.core.api.rule;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.api.topology.ShardNode;
import io.github.lavyoung.lavshard.core.api.topology.ShardTopology;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 规则快照只读遍历契约。
 */
class RuleSnapshotTest {

    @Test
    void shouldExposeEveryRuleThroughImmutableCollection() {
        TableRule orderRule = rule("t_order");
        TableRule paymentRule = rule("t_payment");
        List<TableRule> source = new ArrayList<>(
                List.of(orderRule, paymentRule)
        );
        RuleSnapshot snapshot = new RuleSnapshot(source);

        source.clear();

        assertThat(snapshot.rules())
                .containsExactlyInAnyOrder(orderRule, paymentRule);
        assertThatThrownBy(() -> snapshot.rules().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldExposeEmptyCollectionForEmptySnapshot() {
        RuleSnapshot snapshot = new RuleSnapshot(List.of());

        assertThat(snapshot.rules()).isEmpty();
    }

    private static TableRule rule(String logicalTable) {
        ShardNode node = new ShardNode(
                logicalTable + "-node",
                "ds0",
                new QualifiedTableName(logicalTable + "_00")
        );
        ShardTopology topology = new ShardTopology(
                logicalTable + "-topology-v1",
                1,
                Map.of(0, node.nodeId()),
                Map.of(node.nodeId(), node)
        );
        return new TableRule(
                logicalTable + "-rule-v1",
                new QualifiedTableName(logicalTable),
                "user_id",
                "hash_mod",
                new AlgorithmConfig(1, "murmur3_32_v1"),
                topology
        );
    }
}
