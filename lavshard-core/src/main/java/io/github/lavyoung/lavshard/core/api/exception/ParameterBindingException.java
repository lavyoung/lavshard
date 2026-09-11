package io.github.lavyoung.lavshard.core.api.exception;

/**
 * JDBC 参数与 SQL 参数引用绑定失败时抛出。
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/10
 */
public class ParameterBindingException extends LavShardException {

    public ParameterBindingException(String message) {
        super(LavShardErrorCode.PARAMETER_BINDING_FAILED, message);
    }

    public ParameterBindingException(String message, Throwable cause) {
        super(LavShardErrorCode.PARAMETER_BINDING_FAILED, message, cause);
    }
}
