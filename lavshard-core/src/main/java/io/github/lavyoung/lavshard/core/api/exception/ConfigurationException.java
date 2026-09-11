package io.github.lavyoung.lavshard.core.api.exception;

/**
 *
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/10
 */
public class ConfigurationException extends LavShardException {

    public ConfigurationException(String message) {
        super(LavShardErrorCode.CONFIGURATION_INVALID, message);
    }

    public ConfigurationException(String message, Throwable cause) {
        super(LavShardErrorCode.CONFIGURATION_INVALID, message, cause);
    }
}
