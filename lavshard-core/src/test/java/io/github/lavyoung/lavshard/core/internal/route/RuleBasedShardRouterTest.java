package io.github.lavyoung.lavshard.core.internal.route;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardBucket;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardValue;
import io.github.lavyoung.lavshard.core.api.exception.ShardRuleNotFoundException;
import io.github.lavyoung.lavshard.core.api.route.RouteRequest;
import io.github.lavyoung.lavshard.core.api.route.ShardRouteDecision;
import io.github.lavyoung.lavshard.core.api.rule.RuleSnapshot;
import io.github.lavyoung.lavshard.core.api.rule.TableRule;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.api.topology.ShardNode;
import io.github.lavyoung.lavshard.core.api.topology.ShardTopology;
import io.github.lavyoung.lavshard.core.internal.algorithm.ShardAlgorithmRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleBasedShardRouterTest {

    private static final QualifiedTableName LOGICAL_TABLE =
            new QualifiedTableName("t_order");

    private final RuleBasedShardRouter router =
            new RuleBasedShardRouter(
                    new SingleShardRouter(
                            ShardAlgorithmRegistry
                                    .withBuiltInAlgorithms()
                    )
            );

    @Test
    void shouldRouteRequestFromSnapshotToVersionedDecision() {
        // Given
        ShardNode node = new ShardNode(
                "order-00",
                "ds0",
                new QualifiedTableName("t_order_00")
        );
        TableRule rule = rule(node);
        RuleSnapshot snapshot =
                new RuleSnapshot(List.of(rule));
        RouteRequest request = new RouteRequest(
                LOGICAL_TABLE,
                ShardValue.of("user-123")
        );

        // When
        ShardRouteDecision decision =
                router.route(snapshot, request);

        // Then
        assertThat(decision.ruleVersion())
                .isEqualTo("order-rule-v1");
        assertThat(decision.topologyVersion())
                .isEqualTo("order-topology-v1");
        assertThat(decision.target().bucket())
                .isEqualTo(new ShardBucket(46));
        assertThat(decision.target().node())
                .isEqualTo(node);
    }

    @Test
    void shouldReturnEmptyWhenSnapshotLookupMisses() {
        // Given
        RuleSnapshot snapshot =
                new RuleSnapshot(List.of());

        // When
        var result = snapshot.find(LOGICAL_TABLE);

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void shouldRejectRouteWhenRuleIsMissing() {
        // Given
        RuleSnapshot snapshot =
                new RuleSnapshot(List.of());
        RouteRequest request = new RouteRequest(
                LOGICAL_TABLE,
                ShardValue.of("user-123")
        );

        // When / Then
        assertThatThrownBy(
                () -> router.route(snapshot, request)
        )
                .isInstanceOf(
                        ShardRuleNotFoundException.class)
                .hasMessageContaining(
                        "shard rule not found"
                )
                .hasMessageContaining("t_order");
    }

    @Test
    void shouldRejectDuplicateLogicalTableRules() {
        // Given
        ShardNode node = new ShardNode(
                "order-00",
                "ds0",
                new QualifiedTableName("t_order_00")
        );
        TableRule first = rule(node);
        TableRule second = rule(node);

        // When / Then
        assertThatThrownBy(
                () -> new RuleSnapshot(
                        List.of(first, second)
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "duplicate shard rule"
                );
    }

    @Test
    void shouldNotBeAffectedBySourceCollectionChanges() {
        // Given
        ShardNode node = new ShardNode(
                "order-00",
                "ds0",
                new QualifiedTableName("t_order_00")
        );
        TableRule rule = rule(node);
        List<TableRule> source = new ArrayList<>();
        source.add(rule);

        RuleSnapshot snapshot =
                new RuleSnapshot(source);

        // When
        source.clear();

        // Then
        assertThat(snapshot.find(LOGICAL_TABLE))
                .contains(rule);
    }

    @Test
    void shouldRejectIncompleteRouteRequest() {
        // Given / When / Then
        assertThatThrownBy(() -> new RouteRequest(
                null,
                ShardValue.of("user-123")
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("logicalTable must not be null");

        assertThatThrownBy(() -> new RouteRequest(
                LOGICAL_TABLE,
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("shardValue must not be null");
    }

    private static TableRule rule(ShardNode node) {
        return new TableRule(
                "order-rule-v1",
                LOGICAL_TABLE,
                "user_id",
                "hash_mod",
                new AlgorithmConfig(
                        1024,
                        "murmur3_32_v1"
                ),
                topology(node)
        );
    }

    private static ShardTopology topology(
            ShardNode node
    ) {
        Map<Integer, String> placements =
                new HashMap<>();

        for (int bucket = 0; bucket < 1024; bucket++) {
            placements.put(bucket, node.nodeId());
        }

        return new ShardTopology(
                "order-topology-v1",
                1024,
                placements,
                Map.of(node.nodeId(), node)
        );
    }
}