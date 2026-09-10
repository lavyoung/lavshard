package io.github.lavyoung.lavshard.core.internal.route;

import io.github.lavyoung.lavshard.core.api.exception.ShardRuleNotFoundException;
import io.github.lavyoung.lavshard.core.api.route.RouteRequest;
import io.github.lavyoung.lavshard.core.api.route.ShardRouteDecision;
import io.github.lavyoung.lavshard.core.api.route.ShardTarget;
import io.github.lavyoung.lavshard.core.api.rule.RuleSnapshot;
import io.github.lavyoung.lavshard.core.api.rule.TableRule;

import java.util.Objects;

/**
 * 基于不可变规则快照执行单节点路由。
 *
 * <p>每次调用显式接收一个 RuleSnapshot，确保规则查找、算法计算
 * 和拓扑映射都基于同一次读取的快照。
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/09/10
 */
public class RuleBasedShardRouter {

    private final SingleShardRouter singleShardRouter;

    public RuleBasedShardRouter(
            SingleShardRouter singleShardRouter
    ) {
        this.singleShardRouter = Objects.requireNonNull(
                singleShardRouter,
                "singleShardRouter must not be null"
        );
    }


    public ShardRouteDecision route(
            RuleSnapshot snapshot,
            RouteRequest request
    ) {
        Objects.requireNonNull(
                snapshot,
                "snapshot must not be null"
        );
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        TableRule rule = snapshot
                .find(request.logicalTable())
                .orElseThrow(() ->
                        new ShardRuleNotFoundException(
                                "shard rule not found for logical table: "
                                        + request.logicalTable()
                        )
                );

        ShardTarget target = singleShardRouter.route(
                rule,
                request.shardValue()
        );

        return new ShardRouteDecision(
                rule.ruleVersion(),
                rule.topology().version(),
                target
        );
    }
}
