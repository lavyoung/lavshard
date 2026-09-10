package io.github.lavyoung.lavshard.core.api.algorithm;

/**
 * 分片算法配置
 *
 * @param bucketCount 固定逻辑桶数量
 * @param hashVersion Hash 持久化语义版本
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/10
 */
public record AlgorithmConfig(
        int bucketCount,
        String hashVersion
) {

    public AlgorithmConfig {
        if (bucketCount <= 0) {
            throw new IllegalArgumentException("bucketCount must be greater than zero");
        }

        if (hashVersion == null || hashVersion.isBlank()) {
            throw new IllegalArgumentException("hashVersion must not be blank");
        }
    }
}
