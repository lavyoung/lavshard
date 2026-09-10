package io.github.lavyoung.lavshard.core.internal.route;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardAlgorithm;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardBucket;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardValue;
import io.github.lavyoung.lavshard.core.api.exception.ShardAlgorithmException;
import io.github.lavyoung.lavshard.core.api.route.ShardTarget;
import io.github.lavyoung.lavshard.core.api.rule.TableRule;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.api.topology.ShardNode;
import io.github.lavyoung.lavshard.core.api.topology.ShardTopology;
import io.github.lavyoung.lavshard.core.internal.algorithm.ShardAlgorithmRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SingleShardRouterTest {

    @Test
    void shouldRouteShardValueThroughBuiltInAlgorithmAndTopology() {
        // Given
        ShardNode node = node(
                "order-00",
                "ds0",
                "t_order_00"
        );
        TableRule rule = rule(
                "hash_mod",
                topologyWithSingleNode(1024, node)
        );
        SingleShardRouter router = builtInRouter();

        // When
        ShardTarget target = router.route(
                rule,
                ShardValue.of("user-123")
        );

        // Then
        assertThat(target.bucket())
                .isEqualTo(new ShardBucket(46));
        assertThat(target.node()).isEqualTo(node);
    }

    @Test
    void shouldRouteTableShardingTopology() {
        // Given
        ShardNode first = node(
                "order-00",
                "ds0",
                "t_order_00"
        );
        ShardNode second = node(
                "order-01",
                "ds0",
                "t_order_01"
        );
        TableRule rule = rule(
                "hash_mod",
                twoNodeTopology(first, second)
        );

        // When
        ShardTarget target = builtInRouter().route(
                rule,
                ShardValue.of("user-123")
        );

        // Then
        assertThat(target.bucket().value()).isZero();
        assertThat(target.node()).isEqualTo(first);
        assertThat(target.node().dataSourceId())
                .isEqualTo("ds0");
        assertThat(target.node().actualTable().table())
                .isEqualTo("t_order_00");
    }

    @Test
    void shouldRouteDatabaseShardingTopology() {
        // Given
        ShardNode first = node(
                "order-00",
                "ds0",
                "t_order"
        );
        ShardNode second = node(
                "order-01",
                "ds1",
                "t_order"
        );
        TableRule rule = rule(
                "hash_mod",
                twoNodeTopology(first, second)
        );

        // When
        ShardTarget target = builtInRouter().route(
                rule,
                ShardValue.of("user-123")
        );

        // Then
        assertThat(target.node()).isEqualTo(first);
        assertThat(target.node().dataSourceId())
                .isEqualTo("ds0");
        assertThat(target.node().actualTable().table())
                .isEqualTo("t_order");
    }

    @Test
    void shouldRouteDatabaseAndTableShardingTopology() {
        // Given
        ShardNode first = node(
                "order-00",
                "ds0",
                "t_order_00"
        );
        ShardNode second = node(
                "order-01",
                "ds1",
                "t_order_01"
        );
        TableRule rule = rule(
                "hash_mod",
                twoNodeTopology(first, second)
        );

        // When
        ShardTarget target = builtInRouter().route(
                rule,
                ShardValue.of("user-123")
        );

        // Then
        assertThat(target.node()).isEqualTo(first);
        assertThat(target.node().dataSourceId())
                .isEqualTo("ds0");
        assertThat(target.node().actualTable().table())
                .isEqualTo("t_order_00");
    }

    @Test
    void shouldRejectUnregisteredAlgorithm() {
        // Given
        ShardNode node = node(
                "order-00",
                "ds0",
                "t_order_00"
        );
        TableRule rule = rule(
                "missing_algorithm",
                topologyWithSingleNode(2, node)
        );
        SingleShardRouter router = builtInRouter();

        // When / Then
        assertThatThrownBy(() -> router.route(
                rule,
                ShardValue.of("user-123")
        ))
                .isInstanceOf(ShardAlgorithmException.class)
                .hasMessage(
                        "shard algorithm not registered: missing_algorithm"
                );
    }

    @Test
    void shouldRejectOutOfRangeBucketReturnedByAlgorithm() {
        // Given
        ShardAlgorithm algorithm =
                new FixedResultAlgorithm(
                        "broken",
                        new ShardBucket(2)
                );
        ShardAlgorithmRegistry registry =
                new ShardAlgorithmRegistry(List.of(algorithm));
        SingleShardRouter router =
                new SingleShardRouter(registry);

        ShardNode node = node(
                "order-00",
                "ds0",
                "t_order_00"
        );
        TableRule rule = rule(
                "broken",
                topologyWithSingleNode(2, node)
        );

        // When / Then
        assertThatThrownBy(() -> router.route(
                rule,
                ShardValue.of("user-123")
        ))
                .isInstanceOf(ShardAlgorithmException.class)
                .hasMessageContaining(
                        "bucket outside configured range"
                )
                .hasMessageContaining("bucket=2")
                .hasMessageContaining("bucketCount=2");
    }

    @Test
    void shouldRejectNullBucketReturnedByAlgorithm() {
        // Given
        ShardAlgorithm algorithm =
                new FixedResultAlgorithm("broken", null);
        ShardAlgorithmRegistry registry =
                new ShardAlgorithmRegistry(List.of(algorithm));
        SingleShardRouter router =
                new SingleShardRouter(registry);

        ShardNode node = node(
                "order-00",
                "ds0",
                "t_order_00"
        );
        TableRule rule = rule(
                "broken",
                topologyWithSingleNode(2, node)
        );

        // When / Then
        assertThatThrownBy(() -> router.route(
                rule,
                ShardValue.of("user-123")
        ))
                .isInstanceOf(ShardAlgorithmException.class)
                .hasMessage(
                        "shard algorithm returned null bucket: broken"
                );
    }

    @Test
    void shouldRejectIncompleteShardTarget() {
        // Given
        ShardNode node = node(
                "order-00",
                "ds0",
                "t_order_00"
        );

        // When / Then
        assertThatThrownBy(
                () -> new ShardTarget(null, node)
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("bucket must not be null");

        assertThatThrownBy(
                () -> new ShardTarget(new ShardBucket(0), null)
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("node must not be null");
    }

    private static SingleShardRouter builtInRouter() {
        return new SingleShardRouter(
                ShardAlgorithmRegistry.withBuiltInAlgorithms()
        );
    }

    private static TableRule rule(
            String algorithmName,
            ShardTopology topology
    ) {
        return new TableRule(
                "order-rule-v1",
                new QualifiedTableName("t_order"),
                "user_id",
                algorithmName,
                new AlgorithmConfig(
                        topology.bucketCount(),
                        "murmur3_32_v1"
                ),
                topology
        );
    }

    private static ShardTopology twoNodeTopology(
            ShardNode first,
            ShardNode second
    ) {
        return new ShardTopology(
                "order-topology-v1",
                2,
                Map.of(
                        0, first.nodeId(),
                        1, second.nodeId()
                ),
                Map.of(
                        first.nodeId(), first,
                        second.nodeId(), second
                )
        );
    }

    private static ShardTopology topologyWithSingleNode(
            int bucketCount,
            ShardNode node
    ) {
        Map<Integer, String> placements = new HashMap<>();

        for (int bucket = 0; bucket < bucketCount; bucket++) {
            placements.put(bucket, node.nodeId());
        }

        return new ShardTopology(
                "order-topology-v1",
                bucketCount,
                placements,
                Map.of(node.nodeId(), node)
        );
    }

    private static ShardNode node(
            String nodeId,
            String dataSourceId,
            String table
    ) {
        return new ShardNode(
                nodeId,
                dataSourceId,
                new QualifiedTableName(table)
        );
    }

    private record FixedResultAlgorithm(
            String name,
            ShardBucket result
    ) implements ShardAlgorithm {

        @Override
        public ShardBucket calculate(
                ShardValue value,
                AlgorithmConfig config
        ) {
            return result;
        }
    }
}