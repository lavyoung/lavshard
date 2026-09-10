package io.github.lavyoung.lavshard.core.api.algorithm;

/**
 * 分片算法公开契约。
 *
 * <p>算法只计算固定范围内的逻辑桶，不感知数据源、
 * 物理表、分片节点和框架执行资源。
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/10
 */
public interface ShardAlgorithm {

    /**
     * 返回稳定且唯一的算法名称
     *
     * @return 算法名称
     */
    String name();

    /**
     * 根据算法分片值和算法配置计算逻辑桶
     *
     * @param value  分片键值
     * @param config 算法配置
     * @return 逻辑桶
     */
    ShardBucket calculate(ShardValue value, AlgorithmConfig config);
}
