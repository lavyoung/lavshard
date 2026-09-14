package io.github.lavyoung.lavshard.starter.internal.transaction;

import io.github.lavyoung.lavshard.core.api.exception.CrossShardTransactionException;
import io.github.lavyoung.lavshard.core.api.exception.TransactionRequiredException;
import io.github.lavyoung.lavshard.core.api.route.ManagedRouteDecision;
import io.github.lavyoung.lavshard.core.api.route.PassThroughDecision;
import io.github.lavyoung.lavshard.core.api.route.SqlRouteDecision;
import io.github.lavyoung.lavshard.core.api.route.TransactionRequirement;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Spring 本地事务的 LavShard 路由绑定与冲突守卫。
 *
 * <p>事务第一次路由时固定唯一物理数据源。受管路由的规则版本
 * 和拓扑版本按照逻辑表分别固定，因此同一事务可以访问同一
 * 数据源上的多张分片逻辑表。</p>
 *
 * <p>事务绑定存储在 Spring TransactionSynchronizationManager，
 * 并通过事务完成回调清理，不要求调用方手工清除。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/14
 */
public final class SpringShardContext {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpringShardContext.class);

    private final Object transactionResourceKey = new Object();

    /**
     * 校验并绑定当前 SQL 路由。
     *
     * <p>首先校验 SQL 声明的最低事务要求。非事务调用不会保存
     * 路由状态；事务调用第一次进入时创建绑定。后续路由必须保持
     * 数据源一致，同一逻辑表还必须保持规则和拓扑版本一致。</p>
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
            throw new IllegalStateException("Transaction synchronization is not active");
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
    private static void validateTransactionPresence(SqlRouteDecision decision) {
        boolean transactionRequired = decision.transactionRequirement() == TransactionRequirement.REQUIRED;

        boolean transactionActive = TransactionSynchronizationManager.isActualTransactionActive();

        if (transactionRequired && !transactionActive) {
            LOGGER.warn("LavShard execution rejected: reason=TRANSACTION_REQUIRED");
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
        logTransactionBinding(route);

        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

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

    private static void logTransactionBinding(TransactionRoute route) {
        if (route instanceof ManagedTransactionRoute managed) {
            LOGGER.debug(
                    "LavShard transaction route bound: decision=MANAGED, logicalTable={}, dataSourceId={}, ruleVersion={}, topologyVersion={}",
                    managed.logicalTable(),
                    managed.dataSourceId(),
                    managed.ruleVersion(),
                    managed.topologyVersion()
            );
            return;
        }

        LOGGER.debug(
                "LavShard transaction route bound: decision=PASSTHROUGH, dataSourceId={}",
                route.dataSourceId()
        );
    }

    /**
     * 将 Core 路由决策转换为事务校验所需的最小坐标。
     *
     * @param decision Core 路由决策
     * @return Managed 或 PassThrough 事务路由
     */
    private static TransactionRoute transactionRoute(SqlRouteDecision decision) {
        if (decision instanceof ManagedRouteDecision managed) {
            String dataSourceId = managed.routePlan().units().get(0).target().node().dataSourceId();

            return new ManagedTransactionRoute(managed.logicalTable(), dataSourceId, managed.routePlan().ruleVersion(), managed.routePlan().topologyVersion());
        }

        if (decision instanceof PassThroughDecision passThrough) {
            return new PassThroughTransactionRoute(passThrough.dataSourceId());
        }

        throw new IllegalArgumentException("Unsupported route decision type: " + decision.getClass().getName());
    }

    /**
     * 事务守卫使用的最小路由坐标。
     */
    private sealed interface TransactionRoute permits ManagedTransactionRoute, PassThroughTransactionRoute {

        String dataSourceId();
    }

    /**
     * 受管 SQL 的事务坐标。
     *
     * @param logicalTable    逻辑表稳定身份
     * @param dataSourceId    物理数据源
     * @param ruleVersion     当前规则版本
     * @param topologyVersion 当前拓扑版本
     */
    private record ManagedTransactionRoute(QualifiedTableName logicalTable, String dataSourceId, String ruleVersion,
                                           String topologyVersion) implements TransactionRoute {
    }

    /**
     * 普通表透传的事务坐标。
     *
     * @param dataSourceId 默认物理数据源
     */
    private record PassThroughTransactionRoute(String dataSourceId) implements TransactionRoute {
    }

    /**
     * 单张逻辑表已经固定的版本。
     *
     * @param ruleVersion     规则版本
     * @param topologyVersion 拓扑版本
     */
    private record ManagedRouteVersion(String ruleVersion, String topologyVersion) {
    }

    /**
     * 当前 Spring 事务已经固定的路由状态。
     *
     * <p>dataSourceId 在整个本地事务中唯一。managedVersions
     * 以逻辑表为键，使不同逻辑表可以拥有各自独立的规则版本和
     * 拓扑版本，同时继续阻止同一逻辑表在事务中发生版本漂移。</p>
     */
    private static final class TransactionRouteState {

        private final String dataSourceId;

        private final Map<QualifiedTableName, ManagedRouteVersion> managedVersions = new HashMap<>();

        private TransactionRouteState(TransactionRoute initialRoute) {
            dataSourceId = initialRoute.dataSourceId();

            if (initialRoute instanceof ManagedTransactionRoute managed) {
                managedVersions.put(managed.logicalTable(), versionOf(managed));
            }
        }

        /**
         * 校验后续路由并在第一次访问新逻辑表时固定其版本。
         *
         * @param route 后续事务路由
         * @throws CrossShardTransactionException 数据源或同表版本冲突时抛出
         */
        private void validate(TransactionRoute route) {
            validateDataSource(route);

            if (!(route instanceof ManagedTransactionRoute managed)) {
                return;
            }

            ManagedRouteVersion incoming = versionOf(managed);

            ManagedRouteVersion bound = managedVersions.get(managed.logicalTable());

            if (bound == null) {
                managedVersions.put(managed.logicalTable(), incoming);
                return;
            }

            if (!bound.equals(incoming)) {
                LOGGER.warn(
                        "LavShard transaction route rejected: reason=VERSION_CONFLICT, logicalTable={}, boundRuleVersion={}, requestedRuleVersion={}, boundTopologyVersion={}, requestedTopologyVersion={}",
                        managed.logicalTable(),
                        bound.ruleVersion(),
                        incoming.ruleVersion(),
                        bound.topologyVersion(),
                        incoming.topologyVersion()
                );
                throw new CrossShardTransactionException("Transaction route for logicalTable " + managed.logicalTable() + " is already bound to ruleVersion " + bound.ruleVersion() + " and topologyVersion " + bound.topologyVersion() + " but attempted ruleVersion " + incoming.ruleVersion() + " and topologyVersion " + incoming.topologyVersion());
            }
        }

        /**
         * 校验整个本地事务的唯一物理数据源。
         *
         * @param route 后续事务路由
         * @throws CrossShardTransactionException 数据源发生变化时抛出
         */
        private void validateDataSource(TransactionRoute route) {
            if (dataSourceId.equals(route.dataSourceId())) {
                return;
            }

            LOGGER.warn(
                    "LavShard transaction route rejected: reason=CROSS_DATA_SOURCE, boundDataSourceId={}, requestedDataSourceId={}",
                    dataSourceId,
                    route.dataSourceId()
            );

            throw new CrossShardTransactionException("Transaction is already bound to " + "dataSourceId " + dataSourceId + " and cannot route to " + route.dataSourceId());
        }

        private static ManagedRouteVersion versionOf(ManagedTransactionRoute managed) {
            return new ManagedRouteVersion(managed.ruleVersion(), managed.topologyVersion());
        }
    }
}
