package io.github.lavyoung.lavshard.core.internal.route;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardBucket;
import io.github.lavyoung.lavshard.core.api.exception.MissingShardKeyException;
import io.github.lavyoung.lavshard.core.api.exception.ParameterBindingException;
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
import io.github.lavyoung.lavshard.core.internal.sql.JSqlParserSingleTableDeleteRewriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SingleDeleteRoutePlannerTest {

    private static final QualifiedTableName LOGICAL_TABLE =
            new QualifiedTableName("t_order");

    private final SingleDeleteRoutePlanner planner =
            new SingleDeleteRoutePlanner(
                    new RuleBasedShardRouter(
                            new SingleShardRouter(
                                    ShardAlgorithmRegistry
                                            .withBuiltInAlgorithms()
                            )
                    ),
                    new JSqlParserSingleTableDeleteRewriter(),
                    new SingleRoutePlanAssembler()
            );

    @Test
    void shouldCreateExecutableDeletePlan() {
        String sql = """
                DELETE FROM t_order
                WHERE status = ?
                  AND user_id = ?
                ORDER BY id
                LIMIT 10
                """;

        RoutePlan plan = planner.plan(
                snapshot(),
                sql,
                List.of("PAID", "user-123")
        );

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
                        "DELETE FROM t_order_00 "
                                + "WHERE status = ? "
                                + "AND user_id = ? "
                                + "ORDER BY id LIMIT 10"
                );
        assertThat(unit.sql().sourceParameterIndexes())
                .containsExactly(0, 1);
    }

    @Test
    void shouldCreatePlanFromShardLiteral() {
        RoutePlan plan = planner.plan(
                snapshot(),
                "DELETE FROM t_order "
                        + "WHERE user_id = 'user-123'",
                List.of()
        );

        var unit = plan.units().get(0);

        assertThat(unit.target().bucket())
                .isEqualTo(new ShardBucket(46));
        assertThat(unit.target().node().actualTable())
                .isEqualTo(
                        new QualifiedTableName("t_order_00")
                );
        assertThat(unit.sql().sql())
                .isEqualTo(
                        "DELETE FROM t_order_00 "
                                + "WHERE user_id = 'user-123'"
                );
        assertThat(unit.sql().sourceParameterIndexes())
                .isEmpty();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("deploymentModes")
    void shouldSupportAllDeploymentModes(
            String scenario,
            String dataSourceId,
            String actualTable
    ) {
        RoutePlan plan = planner.plan(
                snapshot(dataSourceId, actualTable),
                "DELETE FROM t_order WHERE user_id = ?",
                List.of("user-123")
        );

        var unit = plan.units().get(0);

        assertThat(unit.target().node().dataSourceId())
                .as(scenario)
                .isEqualTo(dataSourceId);
        assertThat(unit.target().node().actualTable())
                .as(scenario)
                .isEqualTo(
                        new QualifiedTableName(actualTable)
                );
        assertThat(unit.sql().sql())
                .as(scenario)
                .isEqualTo(
                        "DELETE FROM " + actualTable
                                + " WHERE user_id = ?"
                );
    }

    private static Stream<Arguments> deploymentModes() {
        return Stream.of(
                Arguments.of(
                        "仅分表",
                        "ds0",
                        "t_order_00"
                ),
                Arguments.of(
                        "仅分库",
                        "ds1",
                        "t_order"
                ),
                Arguments.of(
                        "分库加分表",
                        "ds1",
                        "t_order_01"
                )
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("unsafeShardPredicates")
    void shouldRejectUnsafeShardPredicate(
            String scenario,
            String sql,
            List<?> parameters
    ) {
        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        sql,
                        parameters
                )
        )
                .as(scenario)
                .isInstanceOf(
                        MissingShardKeyException.class
                )
                .hasMessageContaining("user_id");
    }

    private static Stream<Arguments> unsafeShardPredicates() {
        return Stream.of(
                Arguments.of(
                        "没有 WHERE",
                        "DELETE FROM t_order",
                        List.of()
                ),
                Arguments.of(
                        "WHERE 没有分片键",
                        "DELETE FROM t_order WHERE id = ?",
                        List.of(1001L)
                ),
                Arguments.of(
                        "分片键位于 OR 子树",
                        """
                                DELETE FROM t_order
                                WHERE user_id = ? OR status = ?
                                """,
                        List.of("user-123", "PAID")
                ),
                Arguments.of(
                        "分片键使用 IN",
                        """
                                DELETE FROM t_order
                                WHERE user_id IN (?, ?)
                                """,
                        List.of("user-123", "user-456")
                ),
                Arguments.of(
                        "分片键使用 BETWEEN",
                        """
                                DELETE FROM t_order
                                WHERE user_id BETWEEN ? AND ?
                                """,
                        List.of("user-100", "user-200")
                ),
                Arguments.of(
                        "分片键使用函数",
                        """
                                DELETE FROM t_order
                                WHERE user_id = UPPER(?)
                                """,
                        List.of("user-123")
                )
        );
    }

    @Test
    void shouldRejectMultipleShardPredicates() {
        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        """
                                DELETE FROM t_order
                                WHERE user_id = ?
                                  AND user_id = ?
                                """,
                        List.of("user-123", "user-456")
                )
        )
                .isInstanceOf(UnsupportedSqlException.class)
                .hasMessage(
                        "multiple equality predicates found "
                                + "for sharding column: user_id"
                );
    }

    @Test
    void shouldRejectUnsupportedDeleteShapes() {
        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        """
                                DELETE o
                                FROM t_order o
                                JOIN t_user u
                                  ON u.id = o.user_id
                                WHERE o.user_id = ?
                                """,
                        List.of("user-123")
                )
        ).isInstanceOf(UnsupportedSqlException.class);

        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        """
                                DELETE FROM t_order
                                WHERE user_id = ?
                                  AND EXISTS (
                                      SELECT 1
                                      FROM t_order_history
                                  )
                                """,
                        List.of("user-123")
                )
        ).isInstanceOf(UnsupportedSqlException.class);

        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        """
                                DELETE QUICK FROM t_order
                                WHERE user_id = ?
                                """,
                        List.of("user-123")
                )
        ).isInstanceOf(UnsupportedSqlException.class);

        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        "UPDATE t_order SET status = ? "
                                + "WHERE user_id = ?",
                        List.of("PAID", "user-123")
                )
        ).isInstanceOf(UnsupportedSqlException.class);
    }

    @Test
    void shouldRejectTableWithoutShardRule() {
        RuleSnapshot emptySnapshot =
                new RuleSnapshot(List.of());

        assertThatThrownBy(() ->
                planner.plan(
                        emptySnapshot,
                        "DELETE FROM t_order WHERE user_id = ?",
                        List.of("user-123")
                )
        )
                .isInstanceOf(
                        ShardRuleNotFoundException.class
                )
                .hasMessageContaining("t_order");
    }

    @Test
    void shouldRejectMissingJdbcParameter() {
        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        """
                                DELETE FROM t_order
                                WHERE status = ?
                                  AND user_id = ?
                                """,
                        List.of("PAID")
                )
        )
                .isInstanceOf(
                        ParameterBindingException.class
                )
                .hasMessageContaining(
                        "source parameter index out of range"
                );
    }

    @Test
    void shouldRejectNullShardParameter() {
        List<?> parameters =
                Collections.singletonList(null);

        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        "DELETE FROM t_order WHERE user_id = ?",
                        parameters
                )
        )
                .isInstanceOf(
                        ParameterBindingException.class
                )
                .hasMessage(
                        "shard value must not be null"
                );
    }

    @Test
    void shouldRejectUnsupportedShardParameterType() {
        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        "DELETE FROM t_order WHERE user_id = ?",
                        List.of(1.5D)
                )
        )
                .isInstanceOf(
                        ParameterBindingException.class
                )
                .hasMessage(
                        "unsupported shard value type: java.lang.Double"
                );
    }

    @Test
    void shouldRejectInvalidArguments() {
        String sql =
                "DELETE FROM t_order WHERE user_id = ?";

        assertThatThrownBy(() ->
                planner.plan(
                        null,
                        sql,
                        List.of("user-123")
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("snapshot must not be null");

        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        sql,
                        null
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("parameters must not be null");
    }

    private static RuleSnapshot snapshot() {
        return snapshot(
                "ds0",
                "t_order_00"
        );
    }

    private static RuleSnapshot snapshot(
            String dataSourceId,
            String actualTable
    ) {
        ShardNode node = new ShardNode(
                "order-node",
                dataSourceId,
                new QualifiedTableName(actualTable)
        );

        Map<Integer, String> placements =
                new HashMap<>();

        for (int bucket = 0;
             bucket < 1024;
             bucket++) {
            placements.put(
                    bucket,
                    node.nodeId()
            );
        }

        ShardTopology topology =
                new ShardTopology(
                        "order-topology-v1",
                        1024,
                        placements,
                        Map.of(
                                node.nodeId(),
                                node
                        )
                );

        TableRule rule =
                new TableRule(
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
