package io.github.lavyoung.lavshard.core.api.exception;

/**
 *
 * SQL 不在当前安全支持范围内时抛出。
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/10
 */
public class UnsupportedSqlException extends LavShardException {

    public UnsupportedSqlException(String message) {
        super(LavShardErrorCode.SQL_UNSUPPORTED, message);
    }

    public UnsupportedSqlException(String message, Throwable cause) {
        super(LavShardErrorCode.SQL_UNSUPPORTED, message, cause);
    }
}
