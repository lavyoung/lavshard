package io.github.lavyoung.lavshard.core.internal.route;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardBucket;
import io.github.lavyoung.lavshard.core.api.exception.MissingShardKeyException;
import io.github.lavyoung.lavshard.core.api.exception.ParameterBindingException;
import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import io.github.lavyoung.lavshard.core.api.route.RouteMode;
import io.github.lavyoung.lavshard.core.api.route.RoutePlan;
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

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 四类单分片 SQL 统一入口的行为契约。
 *
 * <p>专用 Planner 已分别验证语法细节，本测试重点保证统一入口
 * 能正确识别并分派 SELECT、INSERT、UPDATE、DELETE，同时不削弱
 * 各语句原有的安全策略。</p>
 */
class SingleSqlRoutePlannerTest {

    private static final QualifiedTableName LOGICAL_TABLE =
            new QualifiedTableName("t_order");

    private final SingleSqlRoutePlanner planner =
            new SingleSqlRoutePlanner(
                    ShardAlgorithmRegistry
                            .withBuiltInAlgorithms()
            );

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("supportedStatements")
    void shouldRouteEverySupportedStatementType(
            String scenario,
            String logicalSql,
            List<?> parameters,
            String physicalSqlTemplate,
            List<Integer> parameterIndexes
    ) {
        RoutePlan plan = planner.plan(
                snapshot(),
                logicalSql,
                parameters
        );

        assertThat(plan.mode())
                .as(scenario)
                .isEqualTo(RouteMode.SINGLE);
        assertThat(plan.ruleVersion())
                .as(scenario)
                .isEqualTo("order-rule-v1");
        assertThat(plan.topologyVersion())
                .as(scenario)
                .isEqualTo("order-topology-v1");
        assertThat(plan.units()).hasSize(1);

        var unit = plan.units().get(0);

        assertThat(unit.target().bucket())
                .as(scenario)
                .isEqualTo(new ShardBucket(46));
        assertThat(unit.target().node().dataSourceId())
                .as(scenario)
                .isEqualTo("ds0");
        assertThat(unit.target().node().actualTable())
                .as(scenario)
                .isEqualTo(
                        new QualifiedTableName("t_order_00")
                );
        assertThat(unit.sql().sql())
                .as(scenario)
                .isEqualTo(
                        physicalSqlTemplate.formatted(
                                "t_order_00"
                        )
                );
        assertThat(unit.sql().sourceParameterIndexes())
                .as(scenario)
                .containsExactlyElementsOf(parameterIndexes);
    }

    private static Stream<Arguments> supportedStatements() {
        return statementCases()
                .map(testCase -> Arguments.of(
                        testCase.scenario(),
                        testCase.logicalSql(),
                        testCase.parameters(),
                        testCase.physicalSqlTemplate(),
                        testCase.parameterIndexes()
                ));
    }

    @ParameterizedTest(name = "[{index}] {0} - {1}")
    @MethodSource("allStatementsInAllDeploymentModes")
    void shouldSupportEveryDeploymentModeForEveryStatementType(
            String deployment,
            String statement,
            String dataSourceId,
            String actualTable,
            String logicalSql,
            List<?> parameters,
            String physicalSqlTemplate
    ) {
        RoutePlan plan = planner.plan(
                snapshot(dataSourceId, actualTable),
                logicalSql,
                parameters
        );

        var unit = plan.units().get(0);

        assertThat(unit.target().node().dataSourceId())
                .isEqualTo(dataSourceId);
        assertThat(unit.target().node().actualTable())
                .isEqualTo(
                        new QualifiedTableName(actualTable)
                );
        assertThat(unit.sql().sql())
                .isEqualTo(
                        physicalSqlTemplate.formatted(actualTable)
                );
    }

    private static Stream<Arguments>
    allStatementsInAllDeploymentModes() {
        return deploymentModes().flatMap(deployment ->
                statementCases().map(statement ->
                        Arguments.of(
                                deployment.scenario(),
                                statement.scenario(),
                                deployment.dataSourceId(),
                                deployment.actualTable(),
                                statement.logicalSql(),
                                statement.parameters(),
                                statement.physicalSqlTemplate()
                        )
                )
        );
    }

    private static Stream<DeploymentMode> deploymentModes() {
        return Stream.of(
                new DeploymentMode(
                        "仅分表",
                        "ds0",
                        "t_order_00"
                ),
                new DeploymentMode(
                        "仅分库",
                        "ds1",
                        "t_order"
                ),
                new DeploymentMode(
                        "分库加分表",
                        "ds1",
                        "t_order_01"
                )
        );
    }

    private static Stream<StatementCase> statementCases() {
        return Stream.of(
                new StatementCase(
                        "SELECT",
                        "SELECT * FROM t_order "
                                + "WHERE status = ? AND user_id = ?",
                        List.of("PAID", "user-123"),
                        "SELECT * FROM %s "
                                + "WHERE status = ? AND user_id = ?",
                        List.of(0, 1)
                ),
                new StatementCase(
                        "INSERT",
                        "INSERT INTO t_order "
                                + "(id, user_id, status) "
                                + "VALUES (?, ?, ?)",
                        List.of(1001L, "user-123", "CREATED"),
                        "INSERT INTO %s "
                                + "(id, user_id, status) "
                                + "VALUES (?, ?, ?)",
                        List.of(0, 1, 2)
                ),
                new StatementCase(
                        "UPDATE",
                        "UPDATE t_order SET status = ? "
                                + "WHERE user_id = ?",
                        List.of("PAID", "user-123"),
                        "UPDATE %s SET status = ? "
                                + "WHERE user_id = ?",
                        List.of(0, 1)
                ),
                new StatementCase(
                        "DELETE",
                        "DELETE FROM t_order "
                                + "WHERE user_id = ? AND status = ?",
                        List.of("user-123", "CANCELLED"),
                        "DELETE FROM %s "
                                + "WHERE user_id = ? AND status = ?",
                        List.of(0, 1)
                )
        );
    }

    @Test
    void shouldPreserveStatementSpecificSafetyPolicies() {
        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        "SELECT * FROM t_order "
                                + "WHERE user_id = ? OR status = ?",
                        List.of("user-123", "PAID")
                )
        ).isInstanceOf(MissingShardKeyException.class);

        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        "INSERT INTO t_order (id, user_id) "
                                + "VALUES (?, ?), (?, ?)",
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
                        "UPDATE t_order SET user_id = ? "
                                + "WHERE user_id = ?",
                        List.of("user-456", "user-123")
                )
        )
                .isInstanceOf(UnsupportedSqlException.class)
                .hasMessage(
                        "UPDATE must not modify sharding column: user_id"
                );

        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        "DELETE FROM t_order WHERE status = ?",
                        List.of("CANCELLED")
                )
        ).isInstanceOf(MissingShardKeyException.class);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("unsupportedStatementTypes")
    void shouldRejectUnsupportedStatementTypes(
            String scenario,
            String sql
    ) {
        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        sql,
                        List.of()
                )
        )
                .as(scenario)
                .isInstanceOf(UnsupportedSqlException.class)
                .hasMessageContaining(
                        "SELECT, INSERT, UPDATE and DELETE"
                );
    }

    private static Stream<Arguments> unsupportedStatementTypes() {
        return Stream.of(
                Arguments.of(
                        "DDL",
                        "CREATE TABLE temp_order (id BIGINT)"
                ),
                Arguments.of(
                        "存储过程",
                        "CALL rebuild_order()"
                ),
                Arguments.of(
                        "连接会话语句",
                        "SET time_zone = '+08:00'"
                )
        );
    }

    @Test
    void shouldRejectMalformedAndBlankSql() {
        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        " ",
                        List.of()
                )
        )
                .isInstanceOf(UnsupportedSqlException.class)
                .hasMessage("sql must not be blank");

        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        "SELECT * FROM (",
                        List.of()
                )
        )
                .isInstanceOf(UnsupportedSqlException.class)
                .hasMessage("failed to parse SQL");
    }

    @Test
    void shouldKeepNullShardValueForBinderValidation() {
        List<?> parameters =
                Collections.singletonList(null);

        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        "DELETE FROM t_order WHERE user_id = ?",
                        parameters
                )
        )
                .isInstanceOf(ParameterBindingException.class)
                .hasMessage("shard value must not be null");
    }

    @Test
    void shouldRejectInvalidArguments() {
        assertThatThrownBy(() ->
                new SingleSqlRoutePlanner(null)
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage(
                        "algorithmRegistry must not be null"
                );

        assertThatThrownBy(() ->
                planner.plan(
                        null,
                        "SELECT * FROM t_order WHERE user_id = ?",
                        List.of("user-123")
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("snapshot must not be null");

        assertThatThrownBy(() ->
                planner.plan(
                        snapshot(),
                        "SELECT * FROM t_order WHERE user_id = ?",
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

    private record DeploymentMode(
            String scenario,
            String dataSourceId,
            String actualTable
    ) {
    }

    private record StatementCase(
            String scenario,
            String logicalSql,
            List<?> parameters,
            String physicalSqlTemplate,
            List<Integer> parameterIndexes
    ) {
    }
}
