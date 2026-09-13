package io.github.lavyoung.lavshard.core.api.route;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.rule.RuleSnapshot;
import io.github.lavyoung.lavshard.core.api.rule.TableRule;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.api.topology.ShardNode;
import io.github.lavyoung.lavshard.core.api.topology.ShardTopology;
import io.github.lavyoung.lavshard.core.internal.algorithm.Murmur3HashShardAlgorithm;
import io.github.lavyoung.lavshard.core.internal.algorithm.ShardAlgorithmRegistry;
import io.github.lavyoung.lavshard.core.internal.route.SqlRouteEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 受管路由决策携带逻辑表身份的公共契约。
 */
class ManagedRouteDecisionIdentityTest {

    @Test
    void shouldExposeLogicalTableSelectedByRouteEngine() {
        // Given
        QualifiedTableName logicalTable =
                new QualifiedTableName("t_order");
        SqlRouteEngine engine = engine();

        // When
        ManagedRouteDecision decision = (ManagedRouteDecision) engine.decide(
                snapshot(logicalTable),
                "SELECT * FROM t_order WHERE user_id = ?",
                List.of("user-1")
        );

        // Then
        assertThat(decision.logicalTable()).isEqualTo(logicalTable);
        assertThat(decision.routePlan().units().get(0)
                .target().node().actualTable())
                .isEqualTo(new QualifiedTableName("t_order_00"));
    }

    @Test
    void shouldPreserveQualifiedLogicalTableIdentity() {
        // Given
        QualifiedTableName logicalTable = new QualifiedTableName(
                List.of("sales"),
                "t_order"
        );

        // When
        ManagedRouteDecision decision = (ManagedRouteDecision) engine().decide(
                snapshot(logicalTable),
                "SELECT * FROM sales.t_order WHERE user_id = ?",
                List.of("user-1")
        );

        // Then
        assertThat(decision.logicalTable()).isEqualTo(logicalTable);
    }

    @Test
    void shouldKeepExistingConstructorsCompatible() {
        // Given
        RoutePlan plan = routePlan();
        QualifiedTableName physicalTable = plan.units().get(0)
                .target().node().actualTable();

        // When
        ManagedRouteDecision defaultDecision =
                new ManagedRouteDecision(plan);
        ManagedRouteDecision requiredDecision =
                new ManagedRouteDecision(
                        plan,
                        TransactionRequirement.REQUIRED
                );

        // Then
        assertThat(defaultDecision.logicalTable())
                .isEqualTo(physicalTable);
        assertThat(defaultDecision.transactionRequirement())
                .isEqualTo(TransactionRequirement.NONE);
        assertThat(requiredDecision.logicalTable())
                .isEqualTo(physicalTable);
        assertThat(requiredDecision.transactionRequirement())
                .isEqualTo(TransactionRequirement.REQUIRED);
    }

    @Test
    void shouldRejectNullLogicalTable() {
        assertThatThrownBy(() -> new ManagedRouteDecision(
                routePlan(),
                null,
                TransactionRequirement.NONE
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("logicalTable must not be null");
    }

    private static SqlRouteEngine engine() {
        return new SqlRouteEngine(
                ShardAlgorithmRegistry.withBuiltInAlgorithms(),
                Set.of(),
                "ds0"
        );
    }

    private static RuleSnapshot snapshot(
            QualifiedTableName logicalTable
    ) {
        ShardNode node = new ShardNode(
                "order-node",
                "ds0",
                new QualifiedTableName(
                        logicalTable.qualifiers(),
                        "t_order_00"
                )
        );
        ShardTopology topology = new ShardTopology(
                "topology-v1",
                1,
                Map.of(0, node.nodeId()),
                Map.of(node.nodeId(), node)
        );
        TableRule rule = new TableRule(
                "rule-v1",
                logicalTable,
                "user_id",
                Murmur3HashShardAlgorithm.NAME,
                new AlgorithmConfig(
                        1,
                        Murmur3HashShardAlgorithm.HASH_VERSION
                ),
                topology
        );
        return new RuleSnapshot(List.of(rule));
    }

    private static RoutePlan routePlan() {
        ShardNode node = new ShardNode(
                "node-0",
                "ds0",
                new QualifiedTableName("t_order_00")
        );
        RouteUnit unit = new RouteUnit(
                new ShardTarget(
                        new io.github.lavyoung.lavshard.core.api.algorithm.ShardBucket(0),
                        node
                ),
                new SqlRewriteResult("SELECT 1", List.of())
        );
        return new RoutePlan(
                RouteMode.SINGLE,
                "rule-v1",
                "topology-v1",
                List.of(unit)
        );
    }
}
