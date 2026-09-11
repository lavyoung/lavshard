package io.github.lavyoung.lavshard.core.api.route;

import java.util.Objects;

/**
 * 受 LavShard 管理的 SQL 路由决策。
 *
 * <p>该决策表示 SQL 已命中分片规则，并且已经完成分片计算、
 * 物理节点选择和 SQL 改写。</p>
 *
 * @param routePlan 完整的分片路由计划
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public record ManagedRouteDecision(RoutePlan routePlan) implements SqlRouteDecision {

    public ManagedRouteDecision {
        Objects.requireNonNull(
                routePlan,
                "routePlan must not be null"
        );
    }
}