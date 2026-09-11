package io.github.lavyoung.lavshard.core.internal.sql;

import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JSqlParserSingleTableSelectAnalyzerTest {

    private final JSqlParserSingleTableSelectAnalyzer analyzer =
            new JSqlParserSingleTableSelectAnalyzer();

    @Test
    void shouldAnalyzeTableAndConjunctivePredicates() {
        // Given
        var sql = """
                SELECT ? AS marker
                FROM app.t_order o
                WHERE status = ?
                  AND o.user_id = ?
                  AND enabled = 1
                """;

        // When
        var analysis = analyzer.analyze(sql);

        // Then
        assertAll(
                () -> assertEquals(
                        SqlType.SELECT,
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
                                        new ValueReference.Parameter(1)
                                ),
                                predicate(
                                        "user_id",
                                        new ValueReference.Parameter(2)
                                ),
                                predicate(
                                        "enabled",
                                        new ValueReference.Literal(1L)
                                )
                        ),
                        analysis.predicates()
                )
        );
    }

    @Test
    void shouldAnalyzeReversedStringLiteralEquality() {
        // Given
        var sql = """
                SELECT *
                FROM t_order o
                WHERE 'user-1001' = o.user_id
                """;

        // When
        var analysis = analyzer.analyze(sql);

        // Then
        assertEquals(
                List.of(
                        predicate(
                                "user_id",
                                new ValueReference.Literal("user-1001")
                        )
                ),
                analysis.predicates()
        );
    }

    @Test
    void shouldNotExtractPredicateFromOrSubtree() {
        // Given
        var sql = """
                SELECT *
                FROM t_order
                WHERE (status = ? OR status = ?)
                  AND user_id = ?
                """;

        // When
        var analysis = analyzer.analyze(sql);

        // Then
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
    void shouldAllowSelectWithoutWhereClause() {
        // When
        var analysis = analyzer.analyze(
                "SELECT * FROM t_order"
        );

        // Then
        assertAll(
                () -> assertEquals(
                        List.of(new QualifiedTableName("t_order")),
                        analysis.tables()
                ),
                () -> assertEquals(
                        List.of(),
                        analysis.predicates()
                )
        );
    }

    @Test
    void shouldRejectUnsupportedSelectShapes() {
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
                                "UPDATE t_order SET status = 1"
                        )
                ),
                () -> assertThrows(
                        UnsupportedSqlException.class,
                        () -> analyzer.analyze("""
                                SELECT *
                                FROM t_order o
                                JOIN t_user u ON u.id = o.user_id
                                """)
                ),
                () -> assertThrows(
                        UnsupportedSqlException.class,
                        () -> analyzer.analyze("""
                                SELECT * FROM t_order
                                UNION
                                SELECT * FROM t_order_history
                                """)
                ),
                () -> assertThrows(
                        UnsupportedSqlException.class,
                        () -> analyzer.analyze("""
                                SELECT *
                                FROM t_order
                                WHERE EXISTS (
                                    SELECT 1 FROM t_user
                                )
                                """)
                )
        );
    }

    @Test
    void shouldAnalyzeNegativeIntegerLiteral() {
        // Given
        String sql = """
                SELECT *
                FROM t_order
                WHERE user_id = -1001
                """;

        // When
        SqlAnalysis analysis = analyzer.analyze(sql);

        // Then
        assertEquals(
                List.of(
                        predicate(
                                "user_id",
                                new ValueReference.Literal(-1001L)
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