package io.github.lavyoung.lavshard.starter.internal.transaction;

import io.github.lavyoung.lavshard.core.api.algorithm.ShardBucket;
import io.github.lavyoung.lavshard.core.api.exception.CrossShardTransactionException;
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
 * 同一 Spring 本地事务中多张受管逻辑表的版本绑定契约。
 */
class SpringMultiTableTransactionTest {

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
    void shouldAllowDifferentManagedTablesOnSameDataSource() {
        // Given
        beginTransaction();

        // When / Then
        assertThatCode(() -> {
            context.validate(managed(
                    "t_order", "ds0", "order-rule-v1", "order-topology-v1"
            ));
            context.validate(managed(
                    "t_payment", "ds0", "payment-rule-v7", "payment-topology-v3"
            ));
            context.validate(managed(
                    "t_order", "ds0", "order-rule-v1", "order-topology-v1"
            ));
        }).doesNotThrowAnyException();
    }

    @Test
    void shouldBindManagedVersionsIndependentlyAfterPassThrough() {
        // Given
        beginTransaction();
        context.validate(new io.github.lavyoung.lavshard.core.api.route.PassThroughDecision(
                "ds0",
                "SELECT 1"
        ));

        // When / Then
        assertThatCode(() -> {
            context.validate(managed(
                    "t_order", "ds0", "order-rule-v1", "order-topology-v1"
            ));
            context.validate(managed(
                    "t_payment", "ds0", "payment-rule-v1", "payment-topology-v1"
            ));
        }).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectRuleVersionChangeForSameLogicalTable() {
        // Given
        beginTransaction();
        context.validate(managed(
                "t_order", "ds0", "order-rule-v1", "order-topology-v1"
        ));
        context.validate(managed(
                "t_payment", "ds0", "payment-rule-v1", "payment-topology-v1"
        ));

        // When / Then
        assertThatThrownBy(() -> context.validate(managed(
                "t_order", "ds0", "order-rule-v2", "order-topology-v1"
        )))
                .isInstanceOf(CrossShardTransactionException.class)
                .hasMessageContaining("t_order")
                .hasMessageContaining("order-rule-v1")
                .hasMessageContaining("order-rule-v2");
    }

    @Test
    void shouldRejectTopologyVersionChangeForSameLogicalTable() {
        // Given
        beginTransaction();
        context.validate(managed(
                "t_order", "ds0", "order-rule-v1", "order-topology-v1"
        ));

        // When / Then
        assertThatThrownBy(() -> context.validate(managed(
                "t_order", "ds0", "order-rule-v1", "order-topology-v2"
        )))
                .isInstanceOf(CrossShardTransactionException.class)
                .hasMessageContaining("t_order")
                .hasMessageContaining("order-topology-v1")
                .hasMessageContaining("order-topology-v2");
    }

    @Test
    void shouldStillRejectDifferentDataSourceForSecondLogicalTable() {
        // Given
        beginTransaction();
        context.validate(managed(
                "t_order", "ds0", "order-rule-v1", "order-topology-v1"
        ));

        // When / Then
        assertThatThrownBy(() -> context.validate(managed(
                "t_payment", "ds1", "payment-rule-v1", "payment-topology-v1"
        )))
                .isInstanceOf(CrossShardTransactionException.class)
                .hasMessageContaining("ds0")
                .hasMessageContaining("ds1");
    }

    private static void beginTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private static ManagedRouteDecision managed(
            String logicalTable,
            String dataSourceId,
            String ruleVersion,
            String topologyVersion
    ) {
        QualifiedTableName logical =
                new QualifiedTableName(logicalTable);
        ShardNode node = new ShardNode(
                logicalTable + "-node",
                dataSourceId,
                new QualifiedTableName(logicalTable + "_00")
        );
        RouteUnit unit = new RouteUnit(
                new ShardTarget(new ShardBucket(0), node),
                new SqlRewriteResult("SELECT 1", List.of())
        );
        RoutePlan plan = new RoutePlan(
                RouteMode.SINGLE,
                ruleVersion,
                topologyVersion,
                List.of(unit)
        );
        return new ManagedRouteDecision(
                plan,
                logical,
                TransactionRequirement.NONE
        );
    }
}
