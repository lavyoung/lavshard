package io.github.lavyoung.lavshard.core.internal.route;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import io.github.lavyoung.lavshard.core.api.route.ManagedRouteDecision;
import io.github.lavyoung.lavshard.core.api.route.PassThroughDecision;
import io.github.lavyoung.lavshard.core.api.route.SqlRouteDecision;
import io.github.lavyoung.lavshard.core.api.route.TransactionRequirement;
import io.github.lavyoung.lavshard.core.api.rule.RuleSnapshot;
import io.github.lavyoung.lavshard.core.api.rule.TableRule;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.api.topology.ShardNode;
import io.github.lavyoung.lavshard.core.api.topology.ShardTopology;
import io.github.lavyoung.lavshard.core.internal.algorithm.ShardAlgorithmRegistry;
import io.github.lavyoung.lavshard.core.internal.sql.JSqlParserStatementClassifier;
import io.github.lavyoung.lavshard.core.internal.sql.SqlClassification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 锁定读从 SQL 分类到最终路由决策的事务要求传播契约。
 */
class SqlTransactionRequirementTest {

    private static final QualifiedTableName ORDER_TABLE =
            new QualifiedTableName("t_order");
    private static final QualifiedTableName AUDIT_TABLE =
            new QualifiedTableName("sys_audit");

    private final JSqlParserStatementClassifier classifier =
            new JSqlParserStatementClassifier(Set.of(AUDIT_TABLE));
    private final SqlRouteEngine engine = new SqlRouteEngine(
            ShardAlgorithmRegistry.withBuiltInAlgorithms(),
            Set.of(AUDIT_TABLE),
            "ds-default"
    );

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM t_order WHERE user_id = ? FOR UPDATE",
            "SELECT * FROM t_order WHERE user_id = ? FOR UPDATE NOWAIT",
            "SELECT * FROM t_order WHERE user_id = ? FOR UPDATE SKIP LOCKED"
    })
    void shouldClassifyManagedLockingReadsAsTransactionRequired(
            String sql
    ) {
        // Given / When
        SqlClassification classification =
                classifier.classify(snapshot(), sql);

        // Then
        assertThat(classification.transactionRequirement())
                .isEqualTo(TransactionRequirement.REQUIRED);
    }

    @Test
    void shouldClassifyOrdinaryLockingReadAsTransactionRequired() {
        // Given / When
        SqlClassification classification = classifier.classify(
                snapshot(),
                "SELECT * FROM sys_audit WHERE id = ? FOR UPDATE"
        );

        // Then
        assertThat(classification.transactionRequirement())
                .isEqualTo(TransactionRequirement.REQUIRED);
    }

    @Test
    void shouldKeepOrdinarySelectTransactionOptional() {
        // Given / When
        SqlClassification classification = classifier.classify(
                snapshot(),
                "SELECT * FROM sys_audit WHERE id = ?"
        );

        // Then
        assertThat(classification.transactionRequirement())
                .isEqualTo(TransactionRequirement.NONE);
    }

    @Test
    void shouldRejectLockModesOutsideV01SupportMatrix() {
        // Given / When / Then
        assertThatThrownBy(() -> classifier.classify(
                snapshot(),
                "SELECT * FROM t_order WHERE user_id = ? FOR SHARE"
        ))
                .isInstanceOf(UnsupportedSqlException.class)
                .hasMessage("v0.1 only supports FOR UPDATE locking reads");
    }

    @Test
    void shouldPropagateManagedLockingReadRequirementToDecision() {
        // Given / When
        SqlRouteDecision decision = engine.decide(
                snapshot(),
                "SELECT * FROM t_order WHERE user_id = ? FOR UPDATE",
                List.of("user-123")
        );

        // Then
        assertThat(decision)
                .isInstanceOf(ManagedRouteDecision.class);
        assertThat(decision.transactionRequirement())
                .isEqualTo(TransactionRequirement.REQUIRED);
    }

    @Test
    void shouldPropagatePassThroughLockingReadRequirementToDecision() {
        // Given / When
        SqlRouteDecision decision = engine.decide(
                snapshot(),
                "SELECT * FROM sys_audit WHERE id = ? FOR UPDATE",
                List.of(1L)
        );

        // Then
        assertThat(decision)
                .isInstanceOf(PassThroughDecision.class);
        assertThat(decision.transactionRequirement())
                .isEqualTo(TransactionRequirement.REQUIRED);
    }

    @Test
    void shouldKeepCompatibilityConstructorsTransactionOptional() {
        // Given
        ManagedRouteDecision routed = (ManagedRouteDecision) engine.decide(
                snapshot(),
                "SELECT * FROM t_order WHERE user_id = ?",
                List.of("user-123")
        );

        // When
        ManagedRouteDecision managed =
                new ManagedRouteDecision(routed.routePlan());
        PassThroughDecision passThrough =
                new PassThroughDecision("ds-default", "SELECT 1");

        // Then
        assertThat(managed.transactionRequirement())
                .isEqualTo(TransactionRequirement.NONE);
        assertThat(passThrough.transactionRequirement())
                .isEqualTo(TransactionRequirement.NONE);
    }

    @Test
    void shouldRejectNullTransactionRequirement() {
        // Given
        ManagedRouteDecision routed = (ManagedRouteDecision) engine.decide(
                snapshot(),
                "SELECT * FROM t_order WHERE user_id = ?",
                List.of("user-123")
        );

        // When / Then
        assertThatThrownBy(() -> new ManagedRouteDecision(
                routed.routePlan(),
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("transactionRequirement must not be null");
        assertThatThrownBy(() -> new PassThroughDecision(
                "ds-default",
                "SELECT 1",
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("transactionRequirement must not be null");
    }

    private static RuleSnapshot snapshot() {
        ShardNode node = new ShardNode(
                "order-node",
                "ds0",
                new QualifiedTableName("t_order_00")
        );
        ShardTopology topology = new ShardTopology(
                "topology-v1",
                1,
                Map.of(0, node.nodeId()),
                Map.of(node.nodeId(), node)
        );
        TableRule rule = new TableRule(
                "rule-v1",
                ORDER_TABLE,
                "user_id",
                "hash_mod",
                new AlgorithmConfig(1, "murmur3_32_v1"),
                topology
        );
        return new RuleSnapshot(List.of(rule));
    }
}
