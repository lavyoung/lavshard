package io.github.lavyoung.lavshard.starter.internal.transaction;

import io.github.lavyoung.lavshard.core.api.exception.CrossShardTransactionException;
import io.github.lavyoung.lavshard.core.api.exception.TransactionRequiredException;
import io.github.lavyoung.lavshard.core.api.route.ManagedRouteDecision;
import io.github.lavyoung.lavshard.core.api.route.PassThroughDecision;
import io.github.lavyoung.lavshard.core.api.route.SqlRouteDecision;
import io.github.lavyoung.lavshard.core.api.route.TransactionRequirement;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Objects;

/**
 * Spring 本地事务的 LavShard 路由绑定与冲突守卫。
 *
 * <p>事务第一次路由时绑定物理数据源。第一次 Managed 路由还会
 * 固定规则版本和拓扑版本。后续不兼容路由在 Executor 访问数据库
 * 前抛出 {@link CrossShardTransactionException}。</p>
 *
 * <p>事务绑定存储在 Spring TransactionSynchronizationManager，
 * 并通过事务完成回调清理，不要求调用方手工清除。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public final class SpringShardContext {

    private final Object transactionResourceKey = new Object();

    /**
     * 校验并绑定当前 SQL 路由。
     *
     * <p>首先校验 SQL 声明的最低事务要求。非事务调用不会保存
     * 路由状态；事务调用第一次进入时创建绑定，后续调用必须使用
     * 相同数据源。Managed 路由还必须保持规则版本和拓扑版本一致。</p>
     *
     * @param decision 当前 SQL 路由决策
     * @throws NullPointerException           decision 为空时抛出
     * @throws TransactionRequiredException   SQL 要求事务但当前无事务时抛出
     * @throws IllegalStateException          已标记事务活动但 Spring 事务同步
     *                                        尚未激活时抛出
     * @throws CrossShardTransactionException 当前路由与事务绑定冲突时抛出
     */
    public void validate(SqlRouteDecision decision) {
        Objects.requireNonNull(decision, "decision must not be null");

        validateTransactionPresence(decision);

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "Transaction synchronization is not active"
            );
        }

        TransactionRoute route = transactionRoute(decision);

        Object resource = TransactionSynchronizationManager.getResource(transactionResourceKey);

        if (resource == null) {
            bindFirstRoute(route);
            return;
        }

        if (!(resource instanceof TransactionRouteState state)) {
            throw new IllegalStateException("Unexpected transaction route resource: " + resource.getClass().getName());
        }

        state.validate(route);
    }

    /**
     * 校验当前 SQL 的最低事务要求。
     *
     * @param decision 当前 SQL 路由决策
     * @throws TransactionRequiredException SQL 要求事务但当前无事务时抛出
     */
    private static void validateTransactionPresence(
            SqlRouteDecision decision
    ) {
        boolean transactionRequired = decision.transactionRequirement() == TransactionRequirement.REQUIRED;

        boolean transactionActive = TransactionSynchronizationManager.isActualTransactionActive();

        if (transactionRequired && !transactionActive) {
            throw new TransactionRequiredException("SQL requires an active local transaction");
        }
    }

    /**
     * 建立事务第一次路由的绑定并注册清理回调。
     *
     * @param route 第一次事务路由
     */
    private void bindFirstRoute(TransactionRoute route) {
        TransactionRouteState state = new TransactionRouteState(route);
        TransactionSynchronizationManager.bindResource(transactionResourceKey, state);

        try {
            // 外层事务挂起时释放线程上的路由绑定，内层事务可以独立选库；恢复时重新绑定原来的状态对象，
            // 完整保留外层数据源、规则版本和拓扑版本。连接的挂起与恢复仍由 Spring 负责
            TransactionSynchronizationManager
                    .registerSynchronization(new TransactionSynchronization() {

                        @Override
                        public void suspend() {
                            TransactionSynchronizationManager.unbindResource(transactionResourceKey);
                        }

                        @Override
                        public void resume() {
                            TransactionSynchronizationManager.bindResource(transactionResourceKey, state);
                        }

                        @Override
                        public void afterCompletion(int status) {
                            TransactionSynchronizationManager.unbindResourceIfPossible(transactionResourceKey);
                        }

                    });
        } catch (RuntimeException exception) {
            TransactionSynchronizationManager.unbindResourceIfPossible(transactionResourceKey);
            throw exception;
        }
    }

    /**
     * 将 Core 路由决策转换为事务校验所需的最小坐标。
     *
     * @param decision Core 路由决策
     * @return Managed 或 PassThrough 事务路由
     */
    private static TransactionRoute transactionRoute(SqlRouteDecision decision) {
        if (decision instanceof ManagedRouteDecision managed) {
            String dataSourceId = managed.routePlan()
                    .units()
                    .get(0)
                    .target()
                    .node()
                    .dataSourceId();
            return new ManagedTransactionRoute(
                    dataSourceId,
                    managed.routePlan().ruleVersion(),
                    managed.routePlan().topologyVersion()
            );
        }

        if (decision instanceof PassThroughDecision passThrough) {
            return new PassThroughTransactionRoute(passThrough.dataSourceId());
        }

        throw new IllegalArgumentException(
                "Unsupported route decision type: "
                        + decision.getClass().getName()
        );
    }

    private sealed interface TransactionRoute permits ManagedTransactionRoute, PassThroughTransactionRoute {
        String dataSourceId();
    }

    private record ManagedTransactionRoute(
            String dataSourceId,
            String ruleVersion,
            String topologyVersion
    ) implements TransactionRoute {

    }

    private record PassThroughTransactionRoute(
            String dataSourceId
    ) implements TransactionRoute {

    }

    private record ManagedRouteVersion(
            String ruleVersion,
            String topologyVersion
    ) {
    }

    /**
     * 当前 Spring 事务已经固定的路由状态。
     *
     * <p>PassThrough 先执行时只固定数据源；事务中第一次 Managed
     * 路由再补充规则和拓扑版本。版本列表始终为空或只包含一个元素，
     * 避免用 null 或 Optional 字段表达尚未绑定。</p>
     */
    private static final class TransactionRouteState {
        private final String dataSourceId;

        private List<ManagedRouteVersion> managedVersions;

        private TransactionRouteState(TransactionRoute initialRoute) {
            dataSourceId = initialRoute.dataSourceId();
            if (initialRoute instanceof ManagedTransactionRoute managed) {
                managedVersions = List.of(versionOf(managed));
            } else {
                managedVersions = List.of();
            }
        }

        /**
         * 校验后续路由并在必要时补充首次 Managed 版本。
         *
         * @param route 后续事务路由
         * @throws CrossShardTransactionException 数据源或版本冲突时抛出
         */
        private void validate(TransactionRoute route) {
            validateDataSource(route);
            if (!(route instanceof ManagedTransactionRoute managed)) {
                return;
            }

            ManagedRouteVersion incoming = versionOf(managed);

            if (managedVersions.isEmpty()) {
                managedVersions = List.of(incoming);
            }

            ManagedRouteVersion bound = managedVersions.get(0);

            if (!bound.equals(incoming)) {
                throw new CrossShardTransactionException(
                        "Transaction is already bound to "
                                + "ruleVersion "
                                + bound.ruleVersion()
                                + " and topologyVersion "
                                + bound.topologyVersion()
                                + " but attempted ruleVersion "
                                + incoming.ruleVersion()
                                + " and topologyVersion "
                                + incoming.topologyVersion()
                );
            }
        }

        private void validateDataSource(TransactionRoute route) {
            if (dataSourceId.equals(route.dataSourceId())) {
                return;
            }
            throw new CrossShardTransactionException(
                    "Transaction is already bound to "
                            + "dataSourceId "
                            + dataSourceId
                            + " and cannot route to "
                            + route.dataSourceId()
            );
        }

        private static ManagedRouteVersion versionOf(ManagedTransactionRoute managed) {
            return new ManagedRouteVersion(managed.ruleVersion, managed.topologyVersion);
        }
    }
}
