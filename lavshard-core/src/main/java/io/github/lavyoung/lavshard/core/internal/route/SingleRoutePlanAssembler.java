package io.github.lavyoung.lavshard.core.internal.route;

import io.github.lavyoung.lavshard.core.api.route.*;

import java.util.List;
import java.util.Objects;

/**
 *
 * 将单节点路由决策和物理 SQL 组装成最终 RoutePlan。
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/09/10
 */
public final class SingleRoutePlanAssembler {

    public RoutePlan assemble(
            ShardRouteDecision decision,
            SqlRewriteResult sql
    ) {
        Objects.requireNonNull(
                decision,
                "decision must not be null"
        );
        Objects.requireNonNull(
                sql,
                "sql must not be null"
        );

        RouteUnit unit = new RouteUnit(
                decision.target(),
                sql
        );

        return new RoutePlan(
                RouteMode.SINGLE,
                decision.ruleVersion(),
                decision.topologyVersion(),
                List.of(unit)
        );
    }
}
