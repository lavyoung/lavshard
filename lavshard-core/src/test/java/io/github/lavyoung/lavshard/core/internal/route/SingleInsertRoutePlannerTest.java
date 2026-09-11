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
import io.github.lavyoung.lavshard.core.internal.sql.JSqlParserSingleRowInsertRewriter;
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

class SingleInsertRoutePlannerTest {

    private static final QualifiedTableName LOGICAL_TABLE =
            new QualifiedTableName("t_order");

    private final SingleInsertRoutePlanner planner =
            new SingleInsertRoutePlanner(
                    new RuleBasedShardRouter(
                            new SingleShardRouter(
                                    ShardAlgorithmRegistry
                                            .withBuiltInAlgorithms()
                            )
                    ),
                    new JSqlParserSingleRowInsertRewriter(),
                    new SingleRoutePlanAssembler()
            );

    @Test
    void shouldCreateExecutablePlanFromLogicalInsert() {
        // Given：id 由数据库生成，普通列允许使用数据库函数
        String sql = """
                INSERT INTO t_order (
                    status,
                    user_id,
                    created_at
                )
                VALUES (?, ?, CONCAT(?, '-created'))
                """;

        // When
        RoutePlan plan = planner.plan(
                snapshot(),
                sql,
                List.of(
                        "PAID",
                        "user-123",
                        "audit"
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
                        "INSERT INTO t_order_00 "
                                + "(status, user_id, created_at) "
                                + "VALUES (?, ?, "
                                + "CONCAT(?, '-created'))"
                );

        assertThat(
                unit.sql().sourceParameterIndexes()
        ).containsExactly(0, 1, 2);
    }

    @Test
    void shouldCreatePlanFromShardLiteral() {
        // Given
        String sql = """
                INSERT INTO t_order (
                    user_id,
                    status
                )
                VALUES ('user-123', ?)
                """;

        // When
        RoutePlan plan = planner.plan(
                snapshot(),
                sql,
                List.of("PAID")
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
                .contains("INSERT INTO t_order_00")
                .contains("VALUES ('user-123', ?)");

        assertThat(
                unit.sql().sourceParameterIndexes()
        ).containsExactly(0);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("deploymentModes")
    void shouldSupportAllDeploymentModes(
            String scenario,
            String dataSourceId,
            String actualTable
    ) {
        // Given
        RuleSnapshot snapshot =
                snapshot(
                        dataSourceId,
                        actualTable
                );

        // When
        RoutePlan plan = planner.plan(
                snapshot,
                """
                        INSERT INTO t_order (
                            user_id
                        )
                        VALUES (?)
                        """,
                List.of("user-123")
        );

        // Then
        var target = plan.units()
                .get(0)
                .target();

        assertThat(target.node().dataSourceId())
                .as(scenario)
                .isEqualTo(dataSourceId);

        assertThat(target.node().actualTable())
                .as(scenario)
                .isEqualTo(
                        new QualifiedTableName(actualTable)
                );

        assertThat(plan.units().get(0).sql().sql())
                .as(scenario)
                .contains("INSERT INTO " + actualTable);
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
    @MethodSource("unsafeShardValues")
    void shouldRejectUnsafeShardValues(
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

    private static Stream<Arguments> unsafeShardValues() {
        return Stream.of(
                Arguments.of(
                        "缺少分片键列",
                        """
                                INSERT INTO t_order (
                                    status
                                )
                                VALUES (?)
                                """,
                        List.of("PAID")
                ),
                Arguments.of(
                        "分片键使用数据库默认值",
                        """
                                INSERT INTO t_order (
                                    id,
                                    user_id
                                )
                                VALUES (?, DEFAULT)
                                """,
                        List.of(1001L)
                ),
                Arguments.of(
                        "分片键使用 NULL 字面量",
                        """
                                INSERT INTO t_order (
                                    id,
                                    user_id
                                )
                                VALUES (?, NULL)
                                """,
                        List.of(1001L)
                ),
                Arguments.of(
                        "分片键使用数据库函数",
                        """
                                INSERT INTO t_order (
                                    id,
                                    user_id
                                )
                                VALUES (?, UUID())
                                """,
                        List.of(1001L)
                )
        );
    }

    @Test
    void shouldRejectUnsupportedInsertShapes() {
        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        "INSERT INTO t_order VALUES (?, ?)",
                        List.of(1001L, "user-123")
                )
        ).isInstanceOf(UnsupportedSqlException.class);

        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        """
                                INSERT INTO t_order (
                                    id,
                                    user_id
                                )
                                VALUES (?, ?), (?, ?)
                                """,
                        List.of(
                                1001L,
                                "user-123",
                                1002L,
                                "user-456"
                        )
                )
        ).isInstanceOf(UnsupportedSqlException.class);

        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        """
                                INSERT INTO t_order (
                                    id,
                                    user_id
                                )
                                SELECT id, user_id
                                FROM t_order_temp
                                """,
                        List.of()
                )
        ).isInstanceOf(UnsupportedSqlException.class);

        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        """
                                INSERT INTO t_order (
                                    id,
                                    user_id
                                )
                                VALUES (?, ?)
                                ON DUPLICATE KEY UPDATE
                                    user_id = VALUES(user_id)
                                """,
                        List.of(1001L, "user-123")
                )
        ).isInstanceOf(UnsupportedSqlException.class);

        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        """
                                INSERT INTO t_order (
                                    user_id,
                                    amount
                                )
                                VALUES (
                                    ?,
                                    (
                                        SELECT MAX(amount)
                                        FROM t_order_history
                                    )
                                )
                                """,
                        List.of("user-123")
                )
        ).isInstanceOf(UnsupportedSqlException.class);
    }

    @Test
    void shouldRejectTableWithoutShardRule() {
        // Given
        RuleSnapshot emptySnapshot =
                new RuleSnapshot(List.of());

        // Then
        assertThatThrownBy(() ->
                planner.plan(
                        emptySnapshot,
                        """
                                INSERT INTO t_order (
                                    user_id
                                )
                                VALUES (?)
                                """,
                        List.of("user-123")
                )
        )
                .isInstanceOf(
                        ShardRuleNotFoundException.class
                )
                .hasMessageContaining("t_order");
    }

    @Test
    void shouldRejectMultipleShardKeyValues() {
        String sql = """
                INSERT INTO t_order (
                    user_id,
                    user_id
                )
                VALUES (?, ?)
                """;

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
                .hasMessageContaining(
                        "multiple equality predicates"
                );
    }

    @Test
    void shouldRejectMissingJdbcParameter() {
        String sql = """
                INSERT INTO t_order (
                    status,
                    user_id
                )
                VALUES (?, ?)
                """;

        // 只提供 status，没有提供 user_id
        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        sql,
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
        String sql = """
                INSERT INTO t_order (
                    user_id
                )
                VALUES (?)
                """;

        List<?> parameters =
                Collections.singletonList(null);

        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        sql,
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
        String sql = """
                INSERT INTO t_order (
                    user_id
                )
                VALUES (?)
                """;

        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        sql,
                        List.of(1.5D)
                )
        )
                .isInstanceOf(
                        ParameterBindingException.class
                )
                .hasMessage(
                        "unsupported shard value type: "
                                + "java.lang.Double"
                );
    }

    @Test
    void shouldRejectInvalidArguments() {
        String sql = """
                INSERT INTO t_order (
                    user_id
                )
                VALUES (?)
                """;

        assertThatThrownBy(() ->
                planner.plan(
                        null,
                        sql,
                        List.of("user-123")
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage(
                        "snapshot must not be null"
                );

        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        sql,
                        null
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage(
                        "parameters must not be null"
                );
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