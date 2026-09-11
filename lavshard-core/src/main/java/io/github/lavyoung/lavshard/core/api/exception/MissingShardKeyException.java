package io.github.lavyoung.lavshard.core.api.exception;

/**
 * SQL 中缺少有效分片键条件时抛出。
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/10
 */
public class MissingShardKeyException extends LavShardException {

    public MissingShardKeyException(String message) {
        super(LavShardErrorCode.SHARD_KEY_MISSING, message);
    }

    public MissingShardKeyException(String message, Throwable cause) {
        super(LavShardErrorCode.SHARD_KEY_MISSING, message, cause);
    }
}
