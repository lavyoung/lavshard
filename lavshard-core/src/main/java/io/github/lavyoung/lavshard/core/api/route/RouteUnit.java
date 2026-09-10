package io.github.lavyoung.lavshard.core.api.route;

import java.util.Objects;

/**
 * 一个物理节点上的可执行路由单元。
 *
 * @param target 唯一物理目标
 * @param sql    针对该目标生成的物理 SQL
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/10
 */
public record RouteUnit(
        ShardTarget target,
        SqlRewriteResult sql
) {

    public RouteUnit {
        Objects.requireNonNull(
                target,
                "target must not be null"
        );
        Objects.requireNonNull(
                sql,
                "sql must not be null"
        );
    }
}
