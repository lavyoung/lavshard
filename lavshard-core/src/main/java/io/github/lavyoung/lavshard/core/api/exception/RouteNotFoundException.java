package io.github.lavyoung.lavshard.core.api.exception;

/**
 *
 * 无法根据分片结果找到物理路由目标时抛出
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/10
 */
public class RouteNotFoundException extends LavShardException {

    public RouteNotFoundException(String message) {
        super(LavShardErrorCode.ROUTE_NOT_FOUND, message);
    }

    public RouteNotFoundException(String message, Throwable cause) {
        super(LavShardErrorCode.ROUTE_NOT_FOUND, message, cause);
    }
}
