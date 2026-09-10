package io.github.lavyoung.lavshard.core.api.exception;

/**
 * 所有 LavShard 异常的基类，RuntimeException 子类。
 * <p>
 * 要点：
 * <p>
 * 携带 errorCode 便于国际化
 * <p>
 * 不吞异常，保留 cause
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/10
 */
public class LavShardException extends RuntimeException {

    public LavShardException() {
    }

    public LavShardException(String message) {
        super(message);
    }

    public LavShardException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
