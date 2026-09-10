package io.github.lavyoung.lavshard.core.api.route;

/**
 * 路由引擎，比 ShardRouter 更抽象一层，负责路由策略选择（单库单表 / 多库多表 / 广播）。
 * <p>
 * 要点：
 * <p>
 * v0.1.0 DO，用 ShardRouter 直接搞定
 * <p>
 * v0.2.0 DO，支持复杂查询（IN 命中多分片、无分片键全路由）
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @data 2026/9/10
 */
public class RouteEngine {
}
