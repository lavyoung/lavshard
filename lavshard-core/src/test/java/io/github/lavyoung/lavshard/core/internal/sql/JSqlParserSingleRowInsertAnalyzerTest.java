package io.github.lavyoung.lavshard.core.internal.sql;

import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JSqlParserSingleRowInsertAnalyzerTest {

    private final JSqlParserSingleRowInsertAnalyzer analyzer =
            new JSqlParserSingleRowInsertAnalyzer();

    @Test
    void shouldAnalyzeQualifiedSingleRowInsert() {
        // Given
        String sql = """
                INSERT INTO app.t_order (
                    id,
                    user_id,
                    status
                )
                VALUES (?, ?, 'CREATED')
                """;

        // When
        SqlAnalysis analysis = analyzer.analyze(sql);

        // Then
        assertAll(
                () -> assertEquals(
                        SqlType.INSERT,
                        analysis.type()
                ),
                () -> assertEquals(
                        List.of(
                                new QualifiedTableName(
                                        List.of("app"),
                                        "t_order"
                                )
                        ),
                        analysis.tables()
                ),
                () -> assertEquals(
                        List.of(
                                predicate(
                                        "id",
                                        new ValueReference.Parameter(0)
                                ),
                                predicate(
                                        "user_id",
                                        new ValueReference.Parameter(1)
                                ),
                                predicate(
                                        "status",
                                        new ValueReference.Literal(
                                                "CREATED"
                                        )
                                )
                        ),
                        analysis.predicates()
                )
        );
    }

    @Test
    void shouldAnalyzeLiteralValues() {
        // Given
        String sql = """
                INSERT INTO t_order (
                    id,
                    user_id,
                    amount
                )
                VALUES (?, -1001, 12.5)
                """;

        // When
        SqlAnalysis analysis = analyzer.analyze(sql);

        // Then
        assertEquals(
                List.of(
                        predicate(
                                "id",
                                new ValueReference.Parameter(0)
                        ),
                        predicate(
                                "user_id",
                                new ValueReference.Literal(-1001L)
                        ),
                        predicate(
                                "amount",
                                new ValueReference.Literal(12.5D)
                        )
                ),
                analysis.predicates()
        );
    }

    @Test
    void shouldIgnoreUnsupportedValueExpressions() {
        // Given
        String sql = """
                INSERT INTO t_order (
                    id,
                    user_id,
                    created_at
                )
                VALUES (?, NOW(), NOW())
                """;

        // When
        SqlAnalysis analysis = analyzer.analyze(sql);

        // Then
        assertEquals(
                List.of(
                        predicate(
                                "id",
                                new ValueReference.Parameter(0)
                        )
                ),
                analysis.predicates()
        );
    }

    @Test
    void shouldRejectInsertWithoutExplicitColumns() {
        UnsupportedSqlException exception =
                assertThrows(
                        UnsupportedSqlException.class,
                        () -> analyzer.analyze(
                                "INSERT INTO t_order VALUES (?, ?)"
                        )
                );

        assertEquals(
                "INSERT must declare an explicit column list",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectMultiRowInsert() {
        UnsupportedSqlException exception =
                assertThrows(
                        UnsupportedSqlException.class,
                        () -> analyzer.analyze("""
                                INSERT INTO t_order (
                                    id,
                                    user_id
                                )
                                VALUES (?, ?), (?, ?)
                                """)
                );

        assertEquals(
                "v0.1 only supports single-row INSERT",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectInsertSelect() {
        UnsupportedSqlException exception =
                assertThrows(
                        UnsupportedSqlException.class,
                        () -> analyzer.analyze("""
                                INSERT INTO t_order (
                                    id,
                                    user_id
                                )
                                SELECT id, user_id
                                FROM t_order_temp
                                """)
                );

        assertEquals(
                "INSERT SELECT is not supported in v0.1",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectDuplicateKeyUpdate() {
        assertThrows(
                UnsupportedSqlException.class,
                () -> analyzer.analyze("""
                        INSERT INTO t_order (
                            id,
                            user_id
                        )
                        VALUES (?, ?)
                        ON DUPLICATE KEY UPDATE
                            user_id = VALUES(user_id)
                        """)
        );
    }

    @Test
    void shouldRejectUnsupportedStatements() {
        assertAll(
                () -> assertEquals(
                        "sql must not be blank",
                        assertThrows(
                                UnsupportedSqlException.class,
                                () -> analyzer.analyze(" ")
                        ).getMessage()
                ),
                () -> assertThrows(
                        UnsupportedSqlException.class,
                        () -> analyzer.analyze(
                                "SELECT * FROM t_order"
                        )
                ),
                () -> assertThrows(
                        UnsupportedSqlException.class,
                        () -> analyzer.analyze("""
                                INSERT INTO t_order
                                SET user_id = ?
                                """)
                )
        );
    }

    @Test
    void shouldRejectScalarSubqueryValue() {
        // Given
        String sql = """
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
                """;

        // When
        UnsupportedSqlException exception =
                assertThrows(
                        UnsupportedSqlException.class,
                        () -> analyzer.analyze(sql)
                );

        // Then
        assertEquals(
                "subqueries are not supported for INSERT in v0.1",
                exception.getMessage()
        );
    }

    @Test
    void shouldAnalyzeSingleColumnInsert() {
        // Given
        String sql = """
                INSERT INTO t_order (
                    user_id
                )
                VALUES (?)
                """;

        // When
        SqlAnalysis analysis =
                analyzer.analyze(sql);

        // Then
        assertEquals(
                List.of(
                        predicate(
                                "user_id",
                                new ValueReference.Parameter(0)
                        )
                ),
                analysis.predicates()
        );
    }

    private static ShardPredicate predicate(
            String column,
            ValueReference value
    ) {
        return new ShardPredicate(
                column,
                ShardOperator.EQUAL,
                List.of(value)
        );
    }
}