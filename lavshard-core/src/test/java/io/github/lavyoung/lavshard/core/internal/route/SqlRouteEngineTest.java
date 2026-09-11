package io.github.lavyoung.lavshard.core.internal.route;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.exception.MissingShardKeyException;
import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import io.github.lavyoung.lavshard.core.api.route.ManagedRouteDecision;
import io.github.lavyoung.lavshard.core.api.route.PassThroughDecision;
import io.github.lavyoung.lavshard.core.api.route.SqlRouteDecision;
import io.github.lavyoung.lavshard.core.api.rule.RuleSnapshot;
import io.github.lavyoung.lavshard.core.api.rule.TableRule;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.api.topology.ShardNode;
import io.github.lavyoung.lavshard.core.api.topology.ShardTopology;
import io.github.lavyoung.lavshard.core.internal.algorithm.ShardAlgorithmRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SQL 分类与物理路由的统一决策入口契约。
 *
 * <p>受管表必须得到严格 RoutePlan；普通表和无表 SELECT 必须
 * 保持原 SQL 并进入明确的默认数据源；未知或混合范围的 SQL
 * 必须在访问数据库前失败。</p>
 */
class SqlRouteEngineTest {

    private static final QualifiedTableName ORDER_TABLE =
            new QualifiedTableName("t_order");

    private final SqlRouteEngine engine =
            new SqlRouteEngine(
                    ShardAlgorithmRegistry
                            .withBuiltInAlgorithms(),
                    Set.of(
                            new QualifiedTableName("sys_dict"),
                            new QualifiedTableName("sys_config")
                    ),
                    "ds-default"
            );

    @ParameterizedTest(name = "[{index}] 受管 {0}")
    @MethodSource("managedStatements")
    void shouldCreateManagedDecisionForEveryDml(
            String statementType,
            String logicalSql,
            List<?> parameters,
            String expectedPhysicalSql
    ) {
        SqlRouteDecision decision = engine.decide(
                snapshot(),
                logicalSql,
                parameters
        );

        assertThat(decision).isInstanceOfSatisfying(
                ManagedRouteDecision.class,
                managed -> {
                    var plan = managed.routePlan();
                    var unit = plan.units().get(0);

                    assertThat(unit.target()
                            .node()
                            .dataSourceId())
                            .as(statementType)
                            .isEqualTo("ds0");
                    assertThat(unit.sql().sql())
                            .as(statementType)
                            .isEqualTo(expectedPhysicalSql);
                }
        );
    }

    private static Stream<Arguments> managedStatements() {
        return Stream.of(
                Arguments.of(
                        "SELECT",
                        "SELECT * FROM t_order WHERE user_id = ?",
                        List.of("user-123"),
                        "SELECT * FROM t_order_00 WHERE user_id = ?"
                ),
                Arguments.of(
                        "INSERT",
                        "INSERT INTO t_order "
                                + "(id, user_id) VALUES (?, ?)",
                        List.of(1001L, "user-123"),
                        "INSERT INTO t_order_00 "
                                + "(id, user_id) VALUES (?, ?)"
                ),
                Arguments.of(
                        "UPDATE",
                        "UPDATE t_order SET status = ? "
                                + "WHERE user_id = ?",
                        List.of("PAID", "user-123"),
                        "UPDATE t_order_00 SET status = ? "
                                + "WHERE user_id = ?"
                ),
                Arguments.of(
                        "DELETE",
                        "DELETE FROM t_order WHERE user_id = ?",
                        List.of("user-123"),
                        "DELETE FROM t_order_00 WHERE user_id = ?"
                )
        );
    }

    @ParameterizedTest(name = "[{index}] 透传 {0}")
    @MethodSource("passThroughStatements")
    void shouldCreatePassThroughDecision(
            String scenario,
            String sql,
            List<?> parameters
    ) {
        SqlRouteDecision decision = engine.decide(
                snapshot(),
                sql,
                parameters
        );

        assertThat(decision).isInstanceOfSatisfying(
                PassThroughDecision.class,
                passThrough -> {
                    assertThat(passThrough.dataSourceId())
                            .as(scenario)
                            .isEqualTo("ds-default");
                    assertThat(passThrough.originalSql())
                            .as(scenario)
                            .isEqualTo(sql);
                }
        );
    }

    private static Stream<Arguments> passThroughStatements() {
        return Stream.of(
                Arguments.of(
                        "普通表 SELECT",
                        "SELECT * FROM sys_dict WHERE type = ?",
                        List.of("ORDER_STATUS")
                ),
                Arguments.of(
                        "普通表 INSERT",
                        "INSERT INTO sys_dict "
                                + "(id, type) VALUES (?, ?)",
                        List.of(1L, "ORDER_STATUS")
                ),
                Arguments.of(
                        "普通表 UPDATE",
                        "UPDATE sys_dict SET value = ? WHERE id = ?",
                        List.of("PAID", 1L)
                ),
                Arguments.of(
                        "普通表 DELETE",
                        "DELETE FROM sys_dict WHERE id = ?",
                        List.of(1L)
                ),
                Arguments.of(
                        "普通表 JOIN",
                        """
                                SELECT d.id, c.value
                                FROM sys_dict d
                                JOIN sys_config c ON c.id = d.config_id
                                """,
                        List.of()
                ),
                Arguments.of(
                        "无表 SELECT",
                        "SELECT 1",
                        List.of()
                )
        );
    }

    @Test
    void shouldNotRoutePassThroughParameters() {
        Object unsupportedParameter = new Object();

        SqlRouteDecision decision = engine.decide(
                snapshot(),
                "SELECT * FROM sys_dict WHERE value = ?",
                List.of(unsupportedParameter)
        );

        assertThat(decision)
                .isInstanceOf(PassThroughDecision.class);
    }

    @Test
    void shouldPropagateManagedSqlSafetyFailure() {
        assertThatThrownBy(() ->
                engine.decide(
                        snapshot(),
                        "SELECT * FROM t_order WHERE status = ?",
                        List.of("PAID")
                )
        ).isInstanceOf(MissingShardKeyException.class);
    }

    @Test
    void shouldRejectUnknownAndMixedTables() {
        assertThatThrownBy(() ->
                engine.decide(
                        snapshot(),
                        "SELECT * FROM audit_log WHERE id = ?",
                        List.of(1L)
                )
        )
                .isInstanceOf(UnsupportedSqlException.class)
                .hasMessageContaining("audit_log");

        assertThatThrownBy(() ->
                engine.decide(
                        snapshot(),
                        """
                                SELECT o.id, d.value
                                FROM t_order o
                                JOIN sys_dict d ON d.id = o.dict_id
                                WHERE o.user_id = ?
                                """,
                        List.of("user-123")
                )
        )
                .isInstanceOf(UnsupportedSqlException.class)
                .hasMessage(
                        "managed and ordinary tables must not be mixed"
                );
    }

    @Test
    void shouldRejectInvalidConstructorArguments() {
        assertThatThrownBy(() ->
                new SqlRouteEngine(
                        null,
                        Set.of(),
                        "ds-default"
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage(
                        "algorithmRegistry must not be null"
                );

        assertThatThrownBy(() ->
                new SqlRouteEngine(
                        ShardAlgorithmRegistry
                                .withBuiltInAlgorithms(),
                        null,
                        "ds-default"
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("ordinaryTables must not be null");

        assertThatThrownBy(() ->
                new SqlRouteEngine(
                        ShardAlgorithmRegistry
                                .withBuiltInAlgorithms(),
                        Set.of(),
                        " "
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "defaultDataSourceId must not be blank"
                );
    }

    @Test
    void shouldRejectInvalidDecisionArguments() {
        assertThatThrownBy(() ->
                engine.decide(
                        null,
                        "SELECT 1",
                        List.of()
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("snapshot must not be null");

        assertThatThrownBy(() ->
                engine.decide(
                        snapshot(),
                        "SELECT 1",
                        null
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("parameters must not be null");
    }

    private static RuleSnapshot snapshot() {
        ShardNode node = new ShardNode(
                "order-node",
                "ds0",
                new QualifiedTableName("t_order_00")
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
                ORDER_TABLE,
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
