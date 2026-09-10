package io.github.lavyoung.lavshard.core.api.exception;

/**
 * 算法执行失败
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/10
 */
public class ShardAlgorithmException extends LavShardException {

    public ShardAlgorithmException(String message) {
        super(message);
    }

    public ShardAlgorithmException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
