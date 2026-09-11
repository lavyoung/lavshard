package io.github.lavyoung.lavshard.core.api.exception;

/**
 * 分片算法无法完成桶计算时抛出。
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/10
 */
public class ShardAlgorithmException extends LavShardException {


    public ShardAlgorithmException(String message) {
        super(LavShardErrorCode.SHARD_ALGORITHM_FAILED, message);
    }

    public ShardAlgorithmException(String message, Throwable cause) {
        super(LavShardErrorCode.SHARD_ALGORITHM_FAILED, message, cause);
    }
}
