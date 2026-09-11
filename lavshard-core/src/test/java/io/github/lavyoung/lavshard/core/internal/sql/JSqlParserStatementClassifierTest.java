package io.github.lavyoung.lavshard.core.internal.sql;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import io.github.lavyoung.lavshard.core.api.rule.RuleSnapshot;
import io.github.lavyoung.lavshard.core.api.rule.TableRule;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.api.topology.ShardNode;
import io.github.lavyoung.lavshard.core.api.topology.ShardTopology;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v0.1 SQL 管理边界分类契约。
 *
 * <p>分类器只判断 SQL 应进入严格分片路由还是普通表透传，
 * 不绑定参数、不计算分片、不改写 SQL。无法安全分类的语句
 * 必须在访问数据库前失败。</p>
 */
class JSqlParserStatementClassifierTest {

    private static final QualifiedTableName ORDER_TABLE =
            new QualifiedTableName("t_order");

    private static final QualifiedTableName DICTIONARY_TABLE =
            new QualifiedTableName("sys_dict");

    private static final QualifiedTableName CONFIG_TABLE =
            new QualifiedTableName("sys_config");

    private final JSqlParserStatementClassifier classifier =
            new JSqlParserStatementClassifier(
                    Set.of(
                            DICTIONARY_TABLE,
                            CONFIG_TABLE
                    )
            );

    @ParameterizedTest(name = "[{index}] 受管 {0}")
    @MethodSource("managedStatements")
    void shouldClassifyEveryManagedDmlStatement(
            String statementType,
            String sql
    ) {
        SqlClassification classification =
                classifier.classify(
                        snapshot(ORDER_TABLE),
                        sql
                );

        assertThat(classification.type())
                .as(statementType)
                .isEqualTo(SqlClassificationType.MANAGED);
        assertThat(classification.tables())
                .as(statementType)
                .containsExactly(ORDER_TABLE);
    }

    private static Stream<Arguments> managedStatements() {
        return Stream.of(
                Arguments.of(
                        "SELECT",
                        "SELECT * FROM t_order "
                                + "WHERE user_id = ?"
                ),
                Arguments.of(
                        "INSERT",
                        "INSERT INTO t_order "
                                + "(id, user_id) VALUES (?, ?)"
                ),
                Arguments.of(
                        "UPDATE",
                        "UPDATE t_order SET status = ? "
                                + "WHERE user_id = ?"
                ),
                Arguments.of(
                        "DELETE",
                        "DELETE FROM t_order "
                                + "WHERE user_id = ?"
                )
        );
    }

    @ParameterizedTest(name = "[{index}] 普通表 {0}")
    @MethodSource("ordinaryStatements")
    void shouldPassThroughEveryOrdinaryTableStatement(
            String scenario,
            String sql,
            List<QualifiedTableName> expectedTables
    ) {
        SqlClassification classification =
                classifier.classify(
                        snapshot(ORDER_TABLE),
                        sql
                );

        assertThat(classification.type())
                .as(scenario)
                .isEqualTo(
                        SqlClassificationType.PASSTHROUGH
                );
        assertThat(classification.tables())
                .as(scenario)
                .containsExactlyInAnyOrderElementsOf(
                        expectedTables
                );
    }

    private static Stream<Arguments> ordinaryStatements() {
        return Stream.of(
                Arguments.of(
                        "单表 SELECT",
                        "SELECT * FROM sys_dict WHERE type = ?",
                        List.of(DICTIONARY_TABLE)
                ),
                Arguments.of(
                        "单表 INSERT",
                        "INSERT INTO sys_dict "
                                + "(id, type) VALUES (?, ?)",
                        List.of(DICTIONARY_TABLE)
                ),
                Arguments.of(
                        "单表 UPDATE",
                        "UPDATE sys_dict SET value = ? WHERE id = ?",
                        List.of(DICTIONARY_TABLE)
                ),
                Arguments.of(
                        "单表 DELETE",
                        "DELETE FROM sys_dict WHERE id = ?",
                        List.of(DICTIONARY_TABLE)
                ),
                Arguments.of(
                        "普通表 JOIN",
                        """
                                SELECT d.id, c.value
                                FROM sys_dict d
                                JOIN sys_config c ON c.id = d.config_id
                                """,
                        List.of(
                                DICTIONARY_TABLE,
                                CONFIG_TABLE
                        )
                )
        );
    }

    @ParameterizedTest(name = "[{index}] 无表只读 {0}")
    @MethodSource("tablelessSelectStatements")
    void shouldPassThroughTablelessSelect(
            String scenario,
            String sql
    ) {
        SqlClassification classification =
                classifier.classify(
                        snapshot(ORDER_TABLE),
                        sql
                );

        assertThat(classification.type())
                .as(scenario)
                .isEqualTo(
                        SqlClassificationType.PASSTHROUGH
                );
        assertThat(classification.tables()).isEmpty();
    }

    private static Stream<Arguments> tablelessSelectStatements() {
        return Stream.of(
                Arguments.of(
                        "常量",
                        "SELECT 1"
                ),
                Arguments.of(
                        "数据库函数",
                        "SELECT CURRENT_TIMESTAMP"
                )
        );
    }

    @Test
    void shouldPreserveQualifiedTableNames() {
        QualifiedTableName qualifiedOrder =
                new QualifiedTableName(
                        List.of("app"),
                        "t_order"
                );

        SqlClassification classification =
                new JSqlParserStatementClassifier(Set.of())
                        .classify(
                                snapshot(qualifiedOrder),
                                "SELECT * FROM app.t_order "
                                        + "WHERE user_id = ?"
                        );

        assertThat(classification.type())
                .isEqualTo(SqlClassificationType.MANAGED);
        assertThat(classification.tables())
                .containsExactly(qualifiedOrder);
    }

    @Test
    void shouldRejectManagedAndOrdinaryTableMix() {
        assertThatThrownBy(() ->
                classifier.classify(
                        snapshot(ORDER_TABLE),
                        """
                                SELECT o.id, d.value
                                FROM t_order o
                                JOIN sys_dict d ON d.id = o.dict_id
                                WHERE o.user_id = ?
                                """
                )
        )
                .isInstanceOf(UnsupportedSqlException.class)
                .hasMessage(
                        "managed and ordinary tables must not be mixed"
                );
    }

    @Test
    void shouldRejectMultipleManagedTables() {
        QualifiedTableName userTable =
                new QualifiedTableName("t_user");

        assertThatThrownBy(() ->
                classifier.classify(
                        snapshot(ORDER_TABLE, userTable),
                        """
                                SELECT o.id
                                FROM t_order o
                                JOIN t_user u ON u.id = o.user_id
                                WHERE o.user_id = ?
                                """
                )
        )
                .isInstanceOf(UnsupportedSqlException.class)
                .hasMessage(
                        "v0.1 only supports one managed table per SQL"
                );
    }

    @Test
    void shouldRejectUnknownTable() {
        assertThatThrownBy(() ->
                classifier.classify(
                        snapshot(ORDER_TABLE),
                        "SELECT * FROM audit_log WHERE id = ?"
                )
        )
                .isInstanceOf(UnsupportedSqlException.class)
                .hasMessageContaining("audit_log")
                .hasMessageContaining("not configured");
    }

    @ParameterizedTest(name = "[{index}] 拒绝 {0}")
    @MethodSource("unsupportedStatements")
    void shouldRejectNonDmlAndMalformedStatements(
            String scenario,
            String sql
    ) {
        assertThatThrownBy(() ->
                classifier.classify(
                        snapshot(ORDER_TABLE),
                        sql
                )
        )
                .as(scenario)
                .isInstanceOf(UnsupportedSqlException.class);
    }

    private static Stream<Arguments> unsupportedStatements() {
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
                ),
                Arguments.of(
                        "损坏 SQL",
                        "SELECT * FROM ("
                )
        );
    }

    @Test
    void shouldCreateImmutableClassification() {
        List<QualifiedTableName> source =
                new java.util.ArrayList<>(
                        List.of(ORDER_TABLE)
                );

        SqlClassification classification =
                new SqlClassification(
                        SqlClassificationType.MANAGED,
                        source
                );

        source.clear();

        assertThat(classification.tables())
                .containsExactly(ORDER_TABLE);
        assertThatThrownBy(() ->
                classification.tables().clear()
        ).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRejectInvalidClassificationArguments() {
        assertThatThrownBy(() ->
                new SqlClassification(
                        null,
                        List.of()
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("type must not be null");

        assertThatThrownBy(() ->
                new SqlClassification(
                        SqlClassificationType.MANAGED,
                        null
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("tables must not be null");
    }

    @Test
    void shouldRejectInvalidClassifierArguments() {
        assertThatThrownBy(() ->
                new JSqlParserStatementClassifier(null)
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("ordinaryTables must not be null");

        Set<QualifiedTableName> tablesWithNull =
                new HashSet<>();
        tablesWithNull.add(null);

        assertThatThrownBy(() ->
                new JSqlParserStatementClassifier(
                        tablesWithNull
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("ordinary table must not be null");

        assertThatThrownBy(() ->
                classifier.classify(
                        null,
                        "SELECT 1"
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("snapshot must not be null");

        assertThatThrownBy(() ->
                classifier.classify(
                        snapshot(ORDER_TABLE),
                        " "
                )
        )
                .isInstanceOf(UnsupportedSqlException.class)
                .hasMessage("sql must not be blank");
    }

    private static RuleSnapshot snapshot(
            QualifiedTableName... logicalTables
    ) {
        return new RuleSnapshot(
                Stream.of(logicalTables)
                        .map(
                                JSqlParserStatementClassifierTest
                                        ::rule
                        )
                        .toList()
        );
    }

    private static TableRule rule(
            QualifiedTableName logicalTable
    ) {
        ShardNode node = new ShardNode(
                logicalTable.table() + "-node",
                "ds0",
                new QualifiedTableName(
                        logicalTable.table() + "_00"
                )
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
                        logicalTable.table()
                                + "-topology-v1",
                        1024,
                        placements,
                        Map.of(
                                node.nodeId(),
                                node
                        )
                );

        return new TableRule(
                logicalTable.table() + "-rule-v1",
                logicalTable,
                "user_id",
                "hash_mod",
                new AlgorithmConfig(
                        1024,
                        "murmur3_32_v1"
                ),
                topology
        );
    }
}
