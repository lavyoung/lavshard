package io.github.lavyoung.lavshard.core.api.route;

/**
 * SQL 路由决策。
 *
 * <p>一个 SQL 经过管理边界分类后，只能产生两种结果：</p>
 *
 * <ul>
 *     <li>受管 SQL：进入分片路由，生成严格的 RoutePlan</li>
 *     <li>透传 SQL：保持原始 SQL，在默认数据源执行</li>
 * </ul>
 *
 * <p>该接口使用密封类型，防止调用方遗漏未知的决策类型。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public sealed interface SqlRouteDecision permits ManagedRouteDecision, PassThroughDecision {

}