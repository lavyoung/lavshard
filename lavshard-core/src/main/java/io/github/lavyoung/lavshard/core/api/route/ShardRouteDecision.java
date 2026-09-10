package io.github.lavyoung.lavshard.core.api.route;

import java.util.Objects;

/**
 * 从规则快照计算得到的单节点路由决策。
 *
 * <p>规则版本和拓扑版本随结果一起返回，供后续 RoutePlan、
 * CacheKey、事务守卫和诊断日志使用。
 *
 * @param ruleVersion     本次路由使用的规则版本
 * @param topologyVersion 本次路由使用的拓扑版本
 * @param target          唯一分片目标
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/09/10
 */
public record ShardRouteDecision(
        String ruleVersion,
        String topologyVersion,
        ShardTarget target
) {

    public ShardRouteDecision {
        requireText(ruleVersion, "ruleVersion");
        requireText(topologyVersion, "topologyVersion");
        Objects.requireNonNull(
                target,
                "target must not be null"
        );
    }

    private static void requireText(
            String value,
            String fieldName
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " must not be blank"
            );
        }
    }
}