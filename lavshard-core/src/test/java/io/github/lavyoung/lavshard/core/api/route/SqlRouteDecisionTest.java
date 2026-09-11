package io.github.lavyoung.lavshard.core.api.route;

import io.github.lavyoung.lavshard.core.api.algorithm.ShardBucket;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.api.topology.ShardNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlRouteDecisionTest {

    @Test
    void shouldCreateManagedDecision() {
        RoutePlan plan = routePlan();

        SqlRouteDecision decision =
                new ManagedRouteDecision(plan);

        assertThat(decision)
                .isInstanceOf(ManagedRouteDecision.class);
        assertThat(
                ((ManagedRouteDecision) decision).routePlan()
        ).isSameAs(plan);
    }

    @Test
    void shouldCreatePassThroughDecision() {
        String sql =
                "SELECT * FROM sys_dict WHERE type = ?";

        SqlRouteDecision decision =
                new PassThroughDecision("ds-default", sql);

        assertThat(decision)
                .isInstanceOf(PassThroughDecision.class);

        PassThroughDecision passThrough =
                (PassThroughDecision) decision;

        assertThat(passThrough.dataSourceId())
                .isEqualTo("ds-default");
        assertThat(passThrough.originalSql())
                .isEqualTo(sql);
    }

    @Test
    void shouldRejectNullManagedRoutePlan() {
        assertThatThrownBy(() ->
                new ManagedRouteDecision(null)
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("routePlan must not be null");
    }

    @Test
    void shouldRejectInvalidPassThroughDecision() {
        assertThatThrownBy(() ->
                new PassThroughDecision(
                        " ",
                        "SELECT 1"
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "dataSourceId must not be blank"
                );

        assertThatThrownBy(() ->
                new PassThroughDecision(
                        "ds-default",
                        " "
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "originalSql must not be blank"
                );
    }

    private static RoutePlan routePlan() {
        ShardNode node = new ShardNode(
                "order-node",
                "ds0",
                new QualifiedTableName("t_order_00")
        );

        RouteUnit unit = new RouteUnit(
                new ShardTarget(
                        new ShardBucket(46),
                        node
                ),
                new SqlRewriteResult(
                        "SELECT * FROM t_order_00 "
                                + "WHERE user_id = ?",
                        List.of(0)
                )
        );

        return new RoutePlan(
                RouteMode.SINGLE,
                "order-rule-v1",
                "order-topology-v1",
                List.of(unit)
        );
    }
}
