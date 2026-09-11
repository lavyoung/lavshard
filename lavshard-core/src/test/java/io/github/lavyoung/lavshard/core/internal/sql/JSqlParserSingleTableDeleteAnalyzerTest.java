package io.github.lavyoung.lavshard.core.internal.sql;

import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JSqlParserSingleTableDeleteAnalyzerTest {

    private final JSqlParserSingleTableDeleteAnalyzer analyzer =
            new JSqlParserSingleTableDeleteAnalyzer();

    @Test
    void shouldAnalyzeQualifiedDeleteAndConjunctivePredicates() {
        String sql = """
                DELETE FROM app.t_order o
                WHERE status = ?
                  AND o.user_id = ?
                  AND enabled = 1
                ORDER BY o.id
                LIMIT 1
                """;

        SqlAnalysis analysis = analyzer.analyze(sql);

        assertAll(
                () -> assertEquals(
                        SqlType.DELETE,
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
                                        "status",
                                        new ValueReference.Parameter(0)
                                ),
                                predicate(
                                        "user_id",
                                        new ValueReference.Parameter(1)
                                ),
                                predicate(
                                        "enabled",
                                        new ValueReference.Literal(1L)
                                )
                        ),
                        analysis.predicates()
                ),
                () -> assertEquals(
                        List.of(),
                        analysis.updatedColumns()
                )
        );
    }

    @Test
    void shouldAnalyzeReversedLiteralEquality() {
        SqlAnalysis analysis = analyzer.analyze(
                "DELETE FROM t_order o "
                        + "WHERE 'user-123' = o.user_id"
        );

        assertEquals(
                List.of(
                        predicate(
                                "user_id",
                                new ValueReference.Literal(
                                        "user-123"
                                )
                        )
                ),
                analysis.predicates()
        );
    }

    @Test
    void shouldIgnoreOrSubtreeAndKeepSafePredicate() {
        SqlAnalysis analysis = analyzer.analyze("""
                DELETE FROM t_order
                WHERE (status = ? OR status = ?)
                  AND user_id = ?
                """);

        assertEquals(
                List.of(
                        predicate(
                                "user_id",
                                new ValueReference.Parameter(2)
                        )
                ),
                analysis.predicates()
        );
    }

    @Test
    void shouldAllowDeleteWithoutWhereAtAnalysisStage() {
        SqlAnalysis analysis = analyzer.analyze(
                "DELETE FROM t_order"
        );

        assertEquals(
                List.of(),
                analysis.predicates()
        );
    }

    @Test
    void shouldIgnoreUnsupportedValueExpressions() {
        SqlAnalysis analysis = analyzer.analyze("""
                DELETE FROM t_order
                WHERE user_id = UPPER(?)
                  AND status = ?
                """);

        assertEquals(
                List.of(
                        predicate(
                                "status",
                                new ValueReference.Parameter(1)
                        )
                ),
                analysis.predicates()
        );
    }

    @Test
    void shouldRejectMismatchedColumnQualifier() {
        UnsupportedSqlException exception = assertThrows(
                UnsupportedSqlException.class,
                () -> analyzer.analyze("""
                        DELETE FROM t_order o
                        WHERE other.user_id = ?
                        """)
        );

        assertEquals(
                "column qualifier does not match DELETE table: other",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectUnsupportedDeleteShapes() {
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
                                DELETE o
                                FROM t_order o
                                JOIN t_user u
                                  ON u.id = o.user_id
                                WHERE o.user_id = ?
                                """)
                ),
                () -> assertThrows(
                        UnsupportedSqlException.class,
                        () -> analyzer.analyze("""
                                DELETE FROM t_order
                                WHERE user_id = ?
                                  AND EXISTS (
                                      SELECT 1
                                      FROM t_order_history
                                  )
                                """)
                ),
                () -> assertThrows(
                        UnsupportedSqlException.class,
                        () -> analyzer.analyze("""
                                DELETE QUICK FROM t_order
                                WHERE user_id = ?
                                """)
                )
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
