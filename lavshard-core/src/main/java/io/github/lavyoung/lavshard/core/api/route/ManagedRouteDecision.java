package io.github.lavyoung.lavshard.core.api.route;

import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;

import java.util.Objects;

/**
 * 受 LavShard 管理的 SQL 路由决策。
 *
 * <p>该决策表示 SQL 已命中分片规则，并且已经完成分片计算、
 * 物理节点选择和 SQL 改写。</p>
 *
 * <p>逻辑表身份用于事务内按表固定规则版本和拓扑版本。不能使用
 * 物理表作为稳定身份，因为不同逻辑表可能拥有不同的规则版本，
 * 而同一逻辑表在拓扑切换后也可能映射到不同物理表。</p>
 *
 * @param routePlan              完整的分片路由计划
 * @param logicalTable           当前路由对应的逻辑表
 * @param transactionRequirement 当前 SQL 的本地事务要求
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/14
 */
public record ManagedRouteDecision(RoutePlan routePlan, QualifiedTableName logicalTable,
                                   TransactionRequirement transactionRequirement) implements SqlRouteDecision {

    public ManagedRouteDecision {
        Objects.requireNonNull(routePlan, "routePlan must not be null");

        Objects.requireNonNull(logicalTable, "logicalTable must not be null");

        Objects.requireNonNull(transactionRequirement, "transactionRequirement must not be null");
    }

    /**
     * 创建带事务要求的兼容路由决策。
     *
     * <p>旧调用方没有提供逻辑表身份时，以当前物理表作为兼容身份。
     * Core 正常路由链路不会使用该推断，而是由 SqlRouteEngine
     * 显式传入真实逻辑表。</p>
     *
     * @param routePlan              完整的分片路由计划
     * @param transactionRequirement 当前 SQL 的本地事务要求
     */
    public ManagedRouteDecision(RoutePlan routePlan, TransactionRequirement transactionRequirement) {
        this(routePlan, inferLogicalTable(routePlan), transactionRequirement);
    }

    /**
     * 创建无强制事务要求的兼容路由决策。
     *
     * @param routePlan 完整的分片路由计划
     */
    public ManagedRouteDecision(RoutePlan routePlan) {
        this(routePlan, inferLogicalTable(routePlan), TransactionRequirement.NONE);
    }

    /**
     * 从现有路由计划推断兼容逻辑表身份。
     *
     * <p>该方法只服务旧构造器。生产路由必须由 SqlRouteEngine
     * 传入分析阶段得到的真实逻辑表。</p>
     *
     * @param routePlan 完整路由计划
     * @return 唯一路由单元的物理表，作为兼容身份
     * @throws NullPointerException routePlan 为空时抛出
     */
    private static QualifiedTableName inferLogicalTable(RoutePlan routePlan) {
        Objects.requireNonNull(routePlan, "routePlan must not be null");

        return routePlan.units().get(0).target().node().actualTable();
    }
}