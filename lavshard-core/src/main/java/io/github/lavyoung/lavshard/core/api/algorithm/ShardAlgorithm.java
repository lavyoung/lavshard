package io.github.lavyoung.lavshard.core.api.algorithm;

import java.util.Objects;

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
     * 校验一张分片表提供的算法配置。
     *
     * <p>默认实现只执行非空校验，以保持已有自定义算法源码兼容。
     * 对配置版本、参数范围存在特殊要求的算法应覆盖此方法。</p>
     *
     * <p>该方法不得计算虚构的分片键，也不得访问数据库或外部服务。</p>
     *
     * @param config 待校验的算法配置
     * @throws NullPointerException config 为空时抛出
     * @throws RuntimeException     算法不接受当前配置时抛出
     */
    default void validate(AlgorithmConfig config) {
        Objects.requireNonNull(config, "config must not be null");
    }

    /**
     * 根据算法分片值和算法配置计算逻辑桶
     *
     * @param value  分片键值
     * @param config 算法配置
     * @return 逻辑桶
     */
    ShardBucket calculate(ShardValue value, AlgorithmConfig config);
}
