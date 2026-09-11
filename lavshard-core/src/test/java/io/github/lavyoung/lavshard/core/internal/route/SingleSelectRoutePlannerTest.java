package io.github.lavyoung.lavshard.core.internal.route;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardBucket;
import io.github.lavyoung.lavshard.core.api.exception.MissingShardKeyException;
import io.github.lavyoung.lavshard.core.api.exception.ShardRuleNotFoundException;
import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import io.github.lavyoung.lavshard.core.api.route.RouteMode;
import io.github.lavyoung.lavshard.core.api.route.RoutePlan;
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
        String sql = """
                SELECT *
                FROM t_order
                WHERE status = ?
                  AND user_id = ?
                """;

        // When
        RoutePlan plan = planner.plan(
                snapshot(),
                sql,
                List.of(
                        "PAID",
                        "user-123"
                )
        );

        // Then
        assertThat(plan.mode())
                .isEqualTo(RouteMode.SINGLE);
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
                                + "WHERE status = ? AND user_id = ?"
                );
        assertThat(unit.sql().sourceParameterIndexes())
                .containsExactly(0, 1);
    }

    @Test
    void shouldPreserveAliasOrderByAndLimit() {
        // Given
        String sql = """
                SELECT o.id
                FROM t_order o
                WHERE o.user_id = ?
                ORDER BY o.id DESC
                LIMIT 10
                """;

        // When
        RoutePlan plan = planner.plan(
                snapshot(),
                sql,
                List.of("user-123")
        );

        // Then
        assertThat(plan.units().get(0).sql().sql())
                .contains("FROM t_order_00 o")
                .contains("ORDER BY o.id DESC")
                .contains("LIMIT 10");
    }

    @Test
    void shouldCreatePlanFromShardLiteral() {
        // Given
        String sql = """
                SELECT *
                FROM t_order
                WHERE user_id = 'user-123'
                """;

        // When
        RoutePlan plan = planner.plan(
                snapshot(),
                sql,
                List.of()
        );

        // Then
        var unit = plan.units().get(0);

        assertThat(unit.target().bucket())
                .isEqualTo(new ShardBucket(46));
        assertThat(unit.target().node().actualTable())
                .isEqualTo(
                        new QualifiedTableName("t_order_00")
                );
        assertThat(unit.sql().sql())
                .contains("FROM t_order_00")
                .contains("user_id = 'user-123'");
        assertThat(unit.sql().sourceParameterIndexes())
                .isEmpty();
    }

    @Test
    void shouldRejectSelectWithoutShardKey() {
        // Given
        String sql = """
                SELECT *
                FROM t_order
                WHERE status = ?
                """;

        // Then
        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        sql,
                        List.of("PAID")
                )
        )
                .isInstanceOf(
                        MissingShardKeyException.class
                )
                .hasMessageContaining("user_id");
    }

    @Test
    void shouldRejectJoin() {
        // Given
        String sql = """
                SELECT o.*
                FROM t_order o
                JOIN t_user u ON u.id = o.user_id
                WHERE o.user_id = ?
                """;

        // Then
        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        sql,
                        List.of("user-123")
                )
        )
                .isInstanceOf(
                        UnsupportedSqlException.class
                )
                .hasMessage(
                        "JOIN is not supported in v0.1"
                );
    }

    @Test
    void shouldRejectUnion() {
        // Given
        String sql = """
                SELECT *
                FROM t_order
                WHERE user_id = ?
                UNION ALL
                SELECT *
                FROM t_order
                WHERE user_id = ?
                """;

        // Then
        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        sql,
                        List.of(
                                "user-123",
                                "user-123"
                        )
                )
        )
                .isInstanceOf(
                        UnsupportedSqlException.class
                )
                .hasMessage(
                        "v0.1 only supports a plain single-table SELECT"
                );
    }

    @Test
    void shouldRejectSubquery() {
        // Given
        String sql = """
                SELECT *
                FROM t_order
                WHERE user_id = ?
                  AND id IN (
                      SELECT order_id
                      FROM t_order_item
                  )
                """;

        // Then
        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        sql,
                        List.of("user-123")
                )
        )
                .isInstanceOf(
                        UnsupportedSqlException.class
                )
                .hasMessage(
                        "subqueries are not supported in v0.1"
                );
    }

    @Test
    void shouldRejectTableWithoutShardRule() {
        // Given
        String sql = """
                SELECT *
                FROM t_user
                WHERE user_id = ?
                """;

        // Then
        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        sql,
                        List.of("user-123")
                )
        )
                .isInstanceOf(
                        ShardRuleNotFoundException.class
                )
                .hasMessageContaining("t_user");
    }

    @Test
    void shouldRejectNonSelectStatement() {
        // Given
        String sql =
                "DELETE FROM t_order WHERE user_id = ?";

        // Then
        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        sql,
                        List.of("user-123")
                )
        )
                .isInstanceOf(
                        UnsupportedSqlException.class
                )
                .hasMessage(
                        "v0.1 only supports a plain single-table SELECT"
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
            placements.put(
                    bucket,
                    node.nodeId()
            );
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

        return new RuleSnapshot(
                List.of(rule)
        );
    }
}