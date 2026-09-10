package io.github.lavyoung.lavshard.core.api.route;

import java.util.Objects;

import io.github.lavyoung.lavshard.core.api.algorithm.ShardValue;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;

/**
 * 已完成参数绑定的单表路由请求。
 *
 * <p>请求只包含路由决策所需的信息，不包含 SQL AST、
 * MyBatis 参数对象或数据库连接。
 *
 * @param logicalTable 受管逻辑表
 * @param shardValue   类型明确的分片键
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/09/10
 */
public record RouteRequest(
        QualifiedTableName logicalTable,
        ShardValue shardValue
) {

    public RouteRequest {
        Objects.requireNonNull(
                logicalTable,
                "logicalTable must not be null"
        );
        Objects.requireNonNull(
                shardValue,
                "shardValue must not be null"
        );
    }
}