package io.github.lavyoung.lavshard.core.internal.sql;

import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JSqlParserSingleTableDeleteRewriterTest {

    private static final QualifiedTableName LOGICAL_TABLE =
            new QualifiedTableName(
                    List.of("app"),
                    "t_order"
            );

    private static final QualifiedTableName ACTUAL_TABLE =
            new QualifiedTableName(
                    List.of("order_db"),
                    "t_order_00"
            );

    private final JSqlParserSingleTableDeleteRewriter rewriter =
            new JSqlParserSingleTableDeleteRewriter();

    @Test
    void shouldRewriteQualifiedDeleteAndPreserveParameters() {
        var result = rewriter.rewrite(
                """
                        DELETE FROM app.t_order o
                        WHERE status = ?
                          AND o.user_id = ?
                        ORDER BY o.id
                        LIMIT 1
                        """,
                LOGICAL_TABLE,
                ACTUAL_TABLE
        );

        assertAll(
                () -> assertEquals(
                        "DELETE FROM order_db.t_order_00 o "
                                + "WHERE status = ? "
                                + "AND o.user_id = ? "
                                + "ORDER BY o.id LIMIT 1",
                        result.sql()
                ),
                () -> assertEquals(
                        List.of(0, 1),
                        result.sourceParameterIndexes()
                )
        );
    }

    @Test
    void shouldRejectMismatchedLogicalTable() {
        UnsupportedSqlException exception = assertThrows(
                UnsupportedSqlException.class,
                () -> rewriter.rewrite(
                        "DELETE FROM app.t_order WHERE user_id = ?",
                        new QualifiedTableName("t_order"),
                        ACTUAL_TABLE
                )
        );

        assertEquals(
                "SQL table does not match route request: app.t_order",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectActualTableWithTooManyQualifiers() {
        UnsupportedSqlException exception = assertThrows(
                UnsupportedSqlException.class,
                () -> rewriter.rewrite(
                        "DELETE FROM app.t_order WHERE user_id = ?",
                        LOGICAL_TABLE,
                        new QualifiedTableName(
                                List.of("catalog", "schema", "tenant"),
                                "t_order_00"
                        )
                )
        );

        assertEquals(
                "at most two table qualifiers are supported",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectUnsupportedDeleteShapes() {
        assertAll(
                () -> assertThrows(
                        UnsupportedSqlException.class,
                        () -> rewriter.rewrite(
                                "SELECT * FROM app.t_order",
                                LOGICAL_TABLE,
                                ACTUAL_TABLE
                        )
                ),
                () -> assertThrows(
                        UnsupportedSqlException.class,
                        () -> rewriter.rewrite(
                                """
                                        DELETE o
                                        FROM app.t_order o
                                        JOIN app.t_user u
                                          ON u.id = o.user_id
                                        WHERE o.user_id = ?
                                        """,
                                LOGICAL_TABLE,
                                ACTUAL_TABLE
                        )
                ),
                () -> assertThrows(
                        UnsupportedSqlException.class,
                        () -> rewriter.rewrite(
                                """
                                        DELETE FROM app.t_order
                                        WHERE user_id = ?
                                          AND EXISTS (
                                              SELECT 1
                                              FROM app.t_order_history
                                          )
                                        """,
                                LOGICAL_TABLE,
                                ACTUAL_TABLE
                        )
                ),
                () -> assertThrows(
                        UnsupportedSqlException.class,
                        () -> rewriter.rewrite(
                                """
                                        DELETE QUICK FROM app.t_order
                                        WHERE user_id = ?
                                        """,
                                LOGICAL_TABLE,
                                ACTUAL_TABLE
                        )
                )
        );
    }

    @Test
    void shouldRejectInvalidArguments() {
        String sql =
                "DELETE FROM app.t_order WHERE user_id = ?";

        assertAll(
                () -> assertEquals(
                        "sql must not be blank",
                        assertThrows(
                                UnsupportedSqlException.class,
                                () -> rewriter.rewrite(
                                        " ",
                                        LOGICAL_TABLE,
                                        ACTUAL_TABLE
                                )
                        ).getMessage()
                ),
                () -> assertEquals(
                        "logicalTable must not be null",
                        assertThrows(
                                NullPointerException.class,
                                () -> rewriter.rewrite(
                                        sql,
                                        null,
                                        ACTUAL_TABLE
                                )
                        ).getMessage()
                ),
                () -> assertEquals(
                        "actualTable must not be null",
                        assertThrows(
                                NullPointerException.class,
                                () -> rewriter.rewrite(
                                        sql,
                                        LOGICAL_TABLE,
                                        null
                                )
                        ).getMessage()
                )
        );
    }
}
