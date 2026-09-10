package io.github.lavyoung.lavshard.core.api.route;

import io.github.lavyoung.lavshard.core.api.algorithm.ShardBucket;
import io.github.lavyoung.lavshard.core.api.topology.ShardNode;

import java.util.Objects;

/**
 * 单次分片计算得到的逻辑桶和物理节点。
 *
 * <p>目标只保存框架无关的路由结果，不持有 DataSource、Connection、
 * MyBatis 或 Spring 类型。
 *
 * @param bucket 算法计算得到的逻辑桶
 * @param node   拓扑映射得到的物理节点
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/10
 */
public record ShardTarget(
        ShardBucket bucket,
        ShardNode node
) {

    public ShardTarget {
        Objects.requireNonNull(
                bucket,
                "bucket must not be null"
        );
        Objects.requireNonNull(
                node,
                "node must not be null"
        );
    }
}
