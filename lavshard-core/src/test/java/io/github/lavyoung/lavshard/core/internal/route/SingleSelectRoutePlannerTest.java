package io.github.lavyoung.lavshard.core.internal.route;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardBucket;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardValue;
import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import io.github.lavyoung.lavshard.core.api.route.RouteMode;
import io.github.lavyoung.lavshard.core.api.route.RoutePlan;
import io.github.lavyoung.lavshard.core.api.route.RouteRequest;
import io.github.lavyoung.lavshard.core.api.rule.RuleSnapshot;
import io.github.lavyoung.lavshard.core.api.rule.TableRule;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.api.topology.ShardNode;
import io.github.lavyoung.lavshard.core.api.topology.ShardTopology;
import io.github.lavyoung.lavshard.core.internal.algorithm.ShardAlgorithmRegistry;
import io.github.lavyoung.lavshard.core.internal.sql.JSqlParserSingleTableSelectRewriter;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SingleSelectRoutePlannerTest {

    private static final QualifiedTableName LOGICAL_TABLE =
            new QualifiedTableName("t_order");

    private final SingleSelectRoutePlanner planner =
            new SingleSelectRoutePlanner(
                    new RuleBasedShardRouter(
                            new SingleShardRouter(
                                    ShardAlgorithmRegistry
                                            .withBuiltInAlgorithms()
                            )
                    ),
                    new JSqlParserSingleTableSelectRewriter(),
                    new SingleRoutePlanAssembler()
            );

    @Test
    void shouldCreateExecutablePlanFromLogicalSelect() {
        // Given
        RuleSnapshot snapshot = snapshot();
        RouteRequest request = new RouteRequest(
                LOGICAL_TABLE,
                ShardValue.of("user-123")
        );

        String sql = """
                select *
                from t_order
                where user_id = ?
                  and status = ?
                """;

        // When
        RoutePlan plan = planner.plan(
                snapshot,
                request,
                sql
        );

        // Then
        assertThat(plan.mode()).isEqualTo(RouteMode.SINGLE);
        assertThat(plan.ruleVersion())
                .isEqualTo("order-rule-v1");
        assertThat(plan.topologyVersion())
                .isEqualTo("order-topology-v1");
        assertThat(plan.units()).hasSize(1);

        var unit = plan.units().get(0);

        assertThat(unit.target().bucket())
                .isEqualTo(new ShardBucket(46));
        assertThat(unit.target().node().dataSourceId())
                .isEqualTo("ds0");
        assertThat(unit.target().node().actualTable())
                .isEqualTo(
                        new QualifiedTableName("t_order_00")
                );
        assertThat(unit.sql().sql())
                .isEqualTo(
                        "SELECT * FROM t_order_00 "
                                + "WHERE user_id = ? AND status = ?"
                );
        assertThat(unit.sql().sourceParameterIndexes())
                .containsExactly(0, 1);
    }

    @Test
    void shouldPreserveAliasOrderByAndLimit() {
        // Given
        String sql = """
                select o.id
                from t_order o
                where o.user_id = ?
                order by o.id desc
                limit 10
                """;

        // When
        RoutePlan plan = planner.plan(
                snapshot(),
                request(),
                sql
        );

        // Then
        assertThat(plan.units().get(0).sql().sql())
                .contains("FROM t_order_00 o")
                .contains("ORDER BY o.id DESC")
                .contains("LIMIT 10");
    }

    @Test
    void shouldRejectJoin() {
        String sql = """
                select o.*
                from t_order o
                join t_user u on u.id = o.user_id
                where o.user_id = ?
                """;

        assertThatThrownBy(() -> planner.plan(
                snapshot(),
                request(),
                sql
        ))
                .isInstanceOf(UnsupportedSqlException.class)
                .hasMessage("JOIN is not supported in v0.1");
    }

    @Test
    void shouldRejectUnion() {
        String sql = """
                select * from t_order where user_id = ?
                union all
                select * from t_order where user_id = ?
                """;

        assertThatThrownBy(() -> planner.plan(
                snapshot(),
                request(),
                sql
        ))
                .isInstanceOf(UnsupportedSqlException.class)
                .hasMessage(
                        "v0.1 only supports a plain single-table SELECT"
                );
    }

    @Test
    void shouldRejectSubquery() {
        String sql = """
                select *
                from t_order
                where user_id = ?
                  and id in (select order_id from t_order_item)
                """;

        assertThatThrownBy(() -> planner.plan(
                snapshot(),
                request(),
                sql
        ))
                .isInstanceOf(UnsupportedSqlException.class)
                .hasMessage(
                        "subqueries are not supported in v0.1"
                );
    }

    @Test
    void shouldRejectMismatchedLogicalTable() {
        String sql = """
                select *
                from t_user
                where user_id = ?
                """;

        assertThatThrownBy(() -> planner.plan(
                snapshot(),
                request(),
                sql
        ))
                .isInstanceOf(UnsupportedSqlException.class)
                .hasMessageContaining(
                        "SQL table does not match route request"
                );
    }

    @Test
    void shouldRejectNonSelectStatement() {
        String sql =
                "delete from t_order where user_id = ?";

        assertThatThrownBy(() -> planner.plan(
                snapshot(),
                request(),
                sql
        ))
                .isInstanceOf(UnsupportedSqlException.class)
                .hasMessage(
                        "v0.1 only supports a plain single-table SELECT"
                );
    }

    private static RouteRequest request() {
        return new RouteRequest(
                LOGICAL_TABLE,
                ShardValue.of("user-123")
        );
    }

    private static RuleSnapshot snapshot() {
        ShardNode node = new ShardNode(
                "order-00",
                "ds0",
                new QualifiedTableName("t_order_00")
        );

        Map<Integer, String> placements =
                new HashMap<>();

        for (int bucket = 0; bucket < 1024; bucket++) {
            placements.put(bucket, node.nodeId());
        }

        ShardTopology topology = new ShardTopology(
                "order-topology-v1",
                1024,
                placements,
                Map.of(node.nodeId(), node)
        );

        TableRule rule = new TableRule(
                "order-rule-v1",
                LOGICAL_TABLE,
                "user_id",
                "hash_mod",
                new AlgorithmConfig(
                        1024,
                        "murmur3_32_v1"
                ),
                topology
        );

        return new RuleSnapshot(List.of(rule));
    }
}