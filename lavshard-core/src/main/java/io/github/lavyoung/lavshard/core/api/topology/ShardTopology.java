package io.github.lavyoung.lavshard.core.api.topology;

import java.util.Map;
import java.util.Objects;

/**
 * 不可变的分片拓扑快照
 *
 * <p>拓扑负责维护完整的“逻辑桶-物理节点”映射。路由时不得再根据当前节点梳理进行取模
 * ，否则增加节点会改变已有数据的位置
 * </p>
 *
 * @param version          拓扑版本
 * @param bucketCount      固定逻辑桶数量
 * @param bucketPlacements 逻辑桶到节点 ID 的完整映射
 * @param nodes            节点 ID 到物理节点的映射
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @data 2026/9/10
 */
public record ShardTopology(
        String version,
        int bucketCount,
        Map<Integer, String> bucketPlacements,
        Map<String, ShardNode> nodes
) {

    public ShardTopology {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException(
                    "version must not be blank"
            );
        }

        if (bucketCount <= 0) {
            throw new IllegalArgumentException(
                    "bucketCount must be greater than zero"
            );
        }

        Objects.requireNonNull(
                bucketPlacements,
                "bucketPlacements must not be null"
        );
        Objects.requireNonNull(
                nodes,
                "nodes must not be null"
        );

        validateNodes(nodes);
        validatePlacements(bucketCount, bucketPlacements, nodes);

        bucketPlacements = Map.copyOf(bucketPlacements);
        nodes = Map.copyOf(nodes);
    }

    /**
     * 根据逻辑桶编号定位物理节点。
     *
     * @param bucketId 逻辑桶编号
     * @return 对应的物理节点
     * @throws IllegalArgumentException 当桶编号越界时
     */
    public ShardNode nodeForBucket(int bucketId) {
        if (bucketId < 0 || bucketId > bucketCount) {
            throw new IllegalArgumentException("bucketId must be between 0 and " + (bucketCount - 1));
        }
        return nodes.get(bucketPlacements.get(bucketId));
    }

    private static void validateNodes(
            Map<String, ShardNode> nodes
    ) {
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "nodes must not be empty"
            );
        }

        for (Map.Entry<String, ShardNode> entry : nodes.entrySet()) {
            String nodeId = entry.getKey();
            ShardNode node = entry.getValue();

            if (nodeId == null || nodeId.isBlank()) {
                throw new IllegalArgumentException(
                        "node map key must not be blank"
                );
            }

            if (node == null) {
                throw new IllegalArgumentException(
                        "node must not be null: " + nodeId
                );
            }

            if (!nodeId.equals(node.nodeId())) {
                throw new IllegalArgumentException(
                        "node map key must match node.nodeId: " + nodeId
                );
            }
        }
    }

    private static void validatePlacements(int bucketCount, Map<Integer, String> bucketPlacements, Map<String, ShardNode> nodes) {
        for (Map.Entry<Integer, String> entry : bucketPlacements.entrySet()) {
            Integer bucketId = entry.getKey();
            String nodeId = entry.getValue();

            if (bucketId == null || bucketId < 0 || bucketId > bucketCount) {
                throw new IllegalArgumentException("bucketId must between 0 and bucketCount, bucketId = " + bucketId);
            }

            if (nodeId == null || nodeId.isBlank()) {
                throw new IllegalArgumentException("placement nodeId must not be blank");
            }

            if (!nodes.containsKey(nodeId)) {
                throw new IllegalArgumentException("placement nodes must contain nodeId, unknown nodeId = " + nodeId);
            }
        }

        // 验证逻辑的数量和数量和 id 对的上
        if (bucketPlacements.size() != bucketCount) {
            throw new IllegalArgumentException("bucket placements must cover every bucket");
        }

        for (int bucketId = 0; bucketId < bucketCount; bucketId++) {
            if (!bucketPlacements.containsKey(bucketId)) {
                throw new IllegalArgumentException(
                        "missing placement for bucket: " + bucketId
                );
            }
        }
    }
}
