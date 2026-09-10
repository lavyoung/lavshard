package io.github.lavyoung.lavshard.core.internal.route;

import io.github.lavyoung.lavshard.core.api.algorithm.ShardAlgorithm;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardBucket;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardValue;
import io.github.lavyoung.lavshard.core.api.exception.ShardAlgorithmException;
import io.github.lavyoung.lavshard.core.api.route.ShardTarget;
import io.github.lavyoung.lavshard.core.api.rule.TableRule;
import io.github.lavyoung.lavshard.core.api.topology.ShardNode;
import io.github.lavyoung.lavshard.core.internal.algorithm.ShardAlgorithmRegistry;

import java.util.Objects;

/**
 * 严格单节点分片路由器。
 *
 * <p>路由器负责串联不可变规则、算法注册表、逻辑桶计算和静态拓扑映射。
 * 它不负责 SQL 解析、SQL 改写、参数绑定或数据库访问。
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/09/10
 */
public final class SingleShardRouter {

    private final ShardAlgorithmRegistry algorithmRegistry;

    public SingleShardRouter(ShardAlgorithmRegistry algorithmRegistry) {
        this.algorithmRegistry = Objects.requireNonNull(
                algorithmRegistry,
                "algorithmRegistry must not be null"
        );
    }

    /**
     * 根据分片规则和分片键计算唯一物理目标。
     *
     * <p>执行顺序固定为：查找算法、计算逻辑桶、校验算法输出、
     * 使用规则中的静态拓扑定位物理节点。
     *
     * @param rule  完整分片表规则
     * @param value 类型明确的分片键
     * @return 包含逻辑桶和物理节点的路由目标
     * @throws NullPointerException    rule 或 value 为 null 时抛出
     * @throws ShardAlgorithmException 算法未注册或输出非法时抛出
     */
    public ShardTarget route(TableRule rule, ShardValue value) {
        Objects.requireNonNull(rule, "rule must not be null");
        Objects.requireNonNull(value, "value must not be null");

        ShardAlgorithm algorithm = algorithmRegistry.find(rule.algorithmName()).orElseThrow(() -> new ShardAlgorithmException(
                "shard algorithm not registered: "
                        + rule.algorithmName()
        ));

        // 算出目标逻辑桶
        ShardBucket bucket = algorithm.calculate(value, rule.algorithmConfig());
        validateBucket(rule.algorithmName(), bucket, rule.topology().bucketCount());

        // 找到物理节点
        ShardNode node = rule.topology().nodeForBucket(bucket.value());
        return new ShardTarget(bucket, node);
    }

    private static void validateBucket(
            String algorithmName,
            ShardBucket bucket,
            int bucketCount
    ) {
        if (bucket == null) {
            throw new ShardAlgorithmException(
                    "shard algorithm returned null bucket: "
                            + algorithmName
            );
        }

        if (bucket.value() >= bucketCount) {
            throw new ShardAlgorithmException(
                    "shard algorithm returned bucket outside configured range: "
                            + "algorithm=" + algorithmName
                            + ", bucket=" + bucket.value()
                            + ", bucketCount=" + bucketCount
            );
        }
    }
}
