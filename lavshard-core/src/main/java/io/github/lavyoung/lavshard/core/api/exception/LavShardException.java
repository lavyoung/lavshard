package io.github.lavyoung.lavshard.core.api.exception;

import java.util.Objects;

/**
 * 所有 LavShard 业务异常的统一基类。
 *
 * <p>异常对外提供稳定错误码、可读消息和原始异常，
 * 便于调用方统一捕获，也便于日志检索和故障诊断。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/10
 */
public abstract class LavShardException extends RuntimeException {

    private final String errorCode;

    protected LavShardException(LavShardErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null")
                .code();
    }

    protected LavShardException(LavShardErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null")
                .code();
    }

    public String getErrorCode() {
        return errorCode;
    }
}
