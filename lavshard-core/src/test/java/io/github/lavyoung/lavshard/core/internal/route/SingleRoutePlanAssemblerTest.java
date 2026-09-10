package io.github.lavyoung.lavshard.core.internal.route;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.lavyoung.lavshard.core.api.algorithm.ShardBucket;
import io.github.lavyoung.lavshard.core.api.route.RouteMode;
import io.github.lavyoung.lavshard.core.api.route.RoutePlan;
import io.github.lavyoung.lavshard.core.api.route.RouteUnit;
import io.github.lavyoung.lavshard.core.api.route.ShardRouteDecision;
import io.github.lavyoung.lavshard.core.api.route.ShardTarget;
import io.github.lavyoung.lavshard.core.api.route.SqlRewriteResult;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.api.topology.ShardNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SingleRoutePlanAssemblerTest {

    private final SingleRoutePlanAssembler assembler =
            new SingleRoutePlanAssembler();

    @Test
    void shouldAssembleVersionedSingleRoutePlan() {
        // Given
        ShardRouteDecision decision = decision();
        SqlRewriteResult sql = new SqlRewriteResult(
                "select * from t_order_00 where user_id = ?",
                List.of(0)
        );

        // When
        RoutePlan plan = assembler.assemble(decision, sql);

        // Then
        assertThat(plan.mode()).isEqualTo(RouteMode.SINGLE);
        assertThat(plan.ruleVersion())
                .isEqualTo("order-rule-v1");
        assertThat(plan.topologyVersion())
                .isEqualTo("order-topology-v1");
        assertThat(plan.units()).hasSize(1);

        RouteUnit unit = plan.units().get(0);
        assertThat(unit.target())
                .isEqualTo(decision.target());
        assertThat(unit.sql()).isEqualTo(sql);
    }

    @Test
    void shouldDefensivelyCopyParameterProjection() {
        // Given
        List<Integer> indexes =
                new ArrayList<>(List.of(0, 2));
        SqlRewriteResult result = new SqlRewriteResult(
                "insert into t_order_00 values (?, ?)",
                indexes
        );

        // When
        indexes.clear();

        // Then
        assertThat(result.sourceParameterIndexes())
                .containsExactly(0, 2);
        assertThatThrownBy(
                () -> result.sourceParameterIndexes().add(3)
        ).isInstanceOf(
                UnsupportedOperationException.class
        );
    }

    @Test
    void shouldRejectInvalidSqlRewriteResult() {
        assertThatThrownBy(
                () -> new SqlRewriteResult(" ", List.of())
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sql must not be blank");

        assertThatThrownBy(
                () -> new SqlRewriteResult(
                        "select 1",
                        List.of(-1)
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "source parameter index must not be negative"
                );
    }

    @Test
    void shouldRejectUnsupportedRouteModesInVersionZeroOne() {
        // Given
        RouteUnit unit = new RouteUnit(
                decision().target(),
                new SqlRewriteResult("select 1", List.of())
        );

        // When / Then
        assertThatThrownBy(() -> new RoutePlan(
                RouteMode.MULTI,
                "rule-v1",
                "topology-v1",
                List.of(unit)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "v0.1 only supports SINGLE route mode"
                );

        assertThatThrownBy(() -> new RoutePlan(
                RouteMode.BROADCAST,
                "rule-v1",
                "topology-v1",
                List.of(unit)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "v0.1 only supports SINGLE route mode"
                );
    }

    @Test
    void shouldRejectInvalidSingleRouteUnitCount() {
        // Given / When / Then
        assertThatThrownBy(() -> new RoutePlan(
                RouteMode.SINGLE,
                "rule-v1",
                "topology-v1",
                List.of()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("route units must not be empty");
    }

    @Test
    void shouldRejectIncompleteAssemblerInput() {
        // Given
        SqlRewriteResult sql =
                new SqlRewriteResult("select 1", List.of());

        // When / Then
        assertThatThrownBy(
                () -> assembler.assemble(null, sql)
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("decision must not be null");

        assertThatThrownBy(
                () -> assembler.assemble(decision(), null)
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("sql must not be null");
    }

    private static ShardRouteDecision decision() {
        ShardNode node = new ShardNode(
                "order-00",
                "ds0",
                new QualifiedTableName("t_order_00")
        );

        return new ShardRouteDecision(
                "order-rule-v1",
                "order-topology-v1",
                new ShardTarget(
                        new ShardBucket(46),
                        node
                )
        );
    }
}