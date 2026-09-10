package io.github.lavyoung.lavshard.core.api.topology;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShardTopologyTest {

    @Test
    void shouldSupportTableOnlyTopology() {
        // Given
        ShardNode node0 = node("node-00", "ds0", "t_order_00");
        ShardNode node1 = node("node-01", "ds0", "t_order_01");

        ShardTopology topology = topology(node0, node1);

        // When
        ShardNode first = topology.nodeForBucket(0);
        ShardNode second = topology.nodeForBucket(1);

        // Then
        assertThat(first.dataSourceId())
                .isEqualTo(second.dataSourceId());
        assertThat(first.actualTable())
                .isNotEqualTo(second.actualTable());
    }

    @Test
    void shouldSupportDatabaseOnlyTopology() {
        // Given
        ShardNode node0 = node("node-00", "ds0", "t_order");
        ShardNode node1 = node("node-01", "ds1", "t_order");

        ShardTopology topology = topology(node0, node1);

        // When
        ShardNode first = topology.nodeForBucket(0);
        ShardNode second = topology.nodeForBucket(1);

        // Then
        assertThat(first.dataSourceId())
                .isNotEqualTo(second.dataSourceId());
        assertThat(first.actualTable())
                .isEqualTo(second.actualTable());
    }

    @Test
    void shouldSupportDatabaseAndTableTopology() {
        // Given
        ShardNode node0 = node("node-00", "ds0", "t_order_00");
        ShardNode node1 = node("node-01", "ds1", "t_order_01");

        ShardTopology topology = topology(node0, node1);

        // When
        ShardNode first = topology.nodeForBucket(0);
        ShardNode second = topology.nodeForBucket(1);

        // Then
        assertThat(first.dataSourceId())
                .isNotEqualTo(second.dataSourceId());
        assertThat(first.actualTable())
                .isNotEqualTo(second.actualTable());
    }

    @Test
    void shouldRejectIncompleteBucketPlacements() {
        // Given
        ShardNode node = node("node-00", "ds0", "t_order");

        // When / Then
        assertThatThrownBy(() -> new ShardTopology(
                "topology-v1",
                2,
                Map.of(0, "node-00"),
                Map.of("node-00", node)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cover every bucket");
    }

    @Test
    void shouldRejectUnknownNode() {
        // Given
        ShardNode node = node("node-00", "ds0", "t_order");

        // When / Then
        assertThatThrownBy(() -> new ShardTopology(
                "topology-v1",
                1,
                Map.of(0, "unknown-node"),
                Map.of("node-00", node)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown nodeId");
    }

    @Test
    void shouldRejectOutOfRangeBucket() {
        // Given
        ShardNode node = node("node-00", "ds0", "t_order");

        // When / Then
        assertThatThrownBy(() -> new ShardTopology(
                "topology-v1",
                1,
                Map.of(1, "node-00"),
                Map.of("node-00", node)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("bucketId out of range: 1, bucketCount = 1");
    }

    @Test
    void shouldRejectOutOfRangeBucketLookup() {
        // Given
        ShardNode node0 = node("node-00", "ds0", "t_order_00");
        ShardNode node1 = node("node-01", "ds0", "t_order_01");
        ShardTopology topology = topology(node0, node1);

        // When / Then
        assertThatThrownBy(() -> topology.nodeForBucket(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bucketId out of range: -1, bucketCount = 2");

        assertThatThrownBy(() -> topology.nodeForBucket(2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bucketId out of range: 2, bucketCount = 2");
    }

    @Test
    void shouldCreateDefensiveCopies() {
        // Given
        ShardNode node = node("node-00", "ds0", "t_order");
        Map<Integer, String> placements = new HashMap<>();
        Map<String, ShardNode> nodes = new HashMap<>();
        placements.put(0, "node-00");
        nodes.put("node-00", node);

        // When
        ShardTopology topology = new ShardTopology(
                "topology-v1",
                1,
                placements,
                nodes
        );
        placements.clear();
        nodes.clear();

        // Then
        assertThat(topology.nodeForBucket(0)).isEqualTo(node);
        assertThatThrownBy(
                () -> topology.nodes().clear()
        ).isInstanceOf(UnsupportedOperationException.class);
    }

    private static ShardTopology topology(
            ShardNode node0,
            ShardNode node1
    ) {
        return new ShardTopology(
                "topology-v1",
                2,
                Map.of(
                        0, node0.nodeId(),
                        1, node1.nodeId()
                ),
                Map.of(
                        node0.nodeId(), node0,
                        node1.nodeId(), node1
                )
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
}