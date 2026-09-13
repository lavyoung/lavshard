package io.github.lavyoung.lavshard.core.api.route;

import java.util.Objects;

/**
 * 受 LavShard 管理的 SQL 路由决策。
 *
 * <p>该决策表示 SQL 已命中分片规则，并且已经完成分片计算、
 * 物理节点选择和 SQL 改写。</p>
 *
 * @param routePlan              完整的分片路由计划
 * @param transactionRequirement 当前 SQL 的本地事务要求
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public record ManagedRouteDecision(RoutePlan routePlan,
                                   TransactionRequirement transactionRequirement
) implements SqlRouteDecision {

    public ManagedRouteDecision {
        Objects.requireNonNull(routePlan, "routePlan must not be null");
        Objects.requireNonNull(transactionRequirement, "transactionRequirement must not be null");
    }

    /**
     * 创建无强制事务要求的受管路由决策。
     *
     * <p>保留该构造器是为了兼容现有测试和调用代码。普通 SQL
     * 在没有显式声明时默认不要求事务。</p>
     *
     * @param routePlan 完整的分片路由计划
     */
    public ManagedRouteDecision(RoutePlan routePlan) {
        this(routePlan, TransactionRequirement.NONE);
    }
}