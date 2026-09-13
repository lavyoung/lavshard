package io.github.lavyoung.lavshard.starter.internal.transaction;

import io.github.lavyoung.lavshard.core.api.algorithm.ShardBucket;
import io.github.lavyoung.lavshard.core.api.exception.CrossShardTransactionException;
import io.github.lavyoung.lavshard.core.api.exception.TransactionRequiredException;
import io.github.lavyoung.lavshard.core.api.route.*;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.api.topology.ShardNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spring 对路由决策中事务要求的执行前守卫契约。
 */
class SpringTransactionRequirementTest {

    private final SpringShardContext context = new SpringShardContext();

    @AfterEach
    void cleanTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization ->
                            synchronization.afterCompletion(
                                    TransactionSynchronization.STATUS_COMMITTED
                            ));
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.clear();
    }

    @Test
    void shouldRejectManagedLockingReadOutsideTransaction() {
        // Given
        ManagedRouteDecision decision = managed(
                "ds0",
                TransactionRequirement.REQUIRED
        );

        // When / Then
        assertThatThrownBy(() -> context.validate(decision))
                .isInstanceOf(TransactionRequiredException.class)
                .hasMessage("SQL requires an active local transaction")
                .extracting("errorCode")
                .isEqualTo("LAVSHARD-CORE-5002");
    }

    @Test
    void shouldRejectPassThroughLockingReadOutsideTransaction() {
        // Given
        PassThroughDecision decision = new PassThroughDecision(
                "ds-default",
                "SELECT * FROM sys_audit FOR UPDATE",
                TransactionRequirement.REQUIRED
        );

        // When / Then
        assertThatThrownBy(() -> context.validate(decision))
                .isInstanceOf(TransactionRequiredException.class);
    }

    @Test
    void shouldAllowTransactionOptionalDecisionOutsideTransaction() {
        // Given
        PassThroughDecision decision =
                new PassThroughDecision("ds-default", "SELECT 1");

        // When / Then
        assertThatCode(() -> context.validate(decision))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldBindFirstLockingReadAndRejectLaterCrossShardRoute() {
        // Given
        beginTransaction();

        // When
        context.validate(managed("ds0", TransactionRequirement.REQUIRED));

        // Then
        assertThatThrownBy(() -> context.validate(
                managed("ds1", TransactionRequirement.NONE)
        ))
                .isInstanceOf(CrossShardTransactionException.class)
                .hasMessageContaining("ds0")
                .hasMessageContaining("ds1");
    }

    @Test
    void shouldRequireSpringSynchronizationForLockingReadTransaction() {
        // Given
        TransactionSynchronizationManager.setActualTransactionActive(true);

        // When / Then
        assertThatThrownBy(() -> context.validate(
                managed("ds0", TransactionRequirement.REQUIRED)
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Transaction synchronization is not active");
    }

    private static void beginTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private static ManagedRouteDecision managed(
            String dataSourceId,
            TransactionRequirement requirement
    ) {
        ShardNode node = new ShardNode(
                "node-" + dataSourceId,
                dataSourceId,
                new QualifiedTableName("t_order_00")
        );
        RouteUnit unit = new RouteUnit(
                new ShardTarget(new ShardBucket(0), node),
                new SqlRewriteResult(
                        "SELECT * FROM t_order_00 WHERE user_id = ? FOR UPDATE",
                        List.of(0)
                )
        );
        RoutePlan plan = new RoutePlan(
                RouteMode.SINGLE,
                "rule-v1",
                "topology-v1",
                List.of(unit)
        );
        return new ManagedRouteDecision(plan, requirement);
    }
}
