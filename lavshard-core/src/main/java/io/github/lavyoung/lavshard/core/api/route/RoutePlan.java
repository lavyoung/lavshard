package io.github.lavyoung.lavshard.core.api.route;

import java.util.List;
import java.util.Objects;

/**
 * 框架无关的不可变路由计划。
 *
 * @param mode            路由模式
 * @param ruleVersion     本次计算使用的规则版本
 * @param topologyVersion 本次计算使用的拓扑版本
 * @param units           可执行路由单元
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/10
 */
public record RoutePlan(
        RouteMode mode,
        String ruleVersion,
        String topologyVersion,
        List<RouteUnit> units
) {

    public RoutePlan {
        Objects.requireNonNull(mode, "mode must not be null");
        requireText(ruleVersion, "ruleVersion");
        requireText(topologyVersion, "topologyVersion");
        Objects.requireNonNull(units, "units must not be null");

        if (units.isEmpty()) {
            throw new IllegalArgumentException(
                    "route units must not be empty"
            );
        }

        if (units.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "route unit must not be null"
            );
        }

        if (mode != RouteMode.SINGLE) {
            throw new IllegalArgumentException(
                    "v0.1 only supports SINGLE route mode"
            );
        }

        if (units.size() != 1) {
            throw new IllegalArgumentException(
                    "SINGLE route plan must contain exactly one unit"
            );
        }

        units = List.copyOf(units);
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
