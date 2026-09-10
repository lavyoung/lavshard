package io.github.lavyoung.lavshard.core.api.algorithm;

/**
 *
 * 算法计算出的逻辑桶
 *
 * @param value 非负数桶编号
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/10
 */
public record ShardBucket(
        int value
) {

    public ShardBucket {
        // 这里不校验上限，因为 ShardBucket 本身不知道当前 bucketCount；具体算法负责保证结果小于桶数量
        if (value < 0) {
            throw new IllegalArgumentException("bucket value must not be negative");
        }
    }
}
