package io.github.lavyoung.lavshard.core.internal.sql;

import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JSqlParserSingleRowInsertRewriterTest {

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

    private final JSqlParserSingleRowInsertRewriter rewriter =
            new JSqlParserSingleRowInsertRewriter();

    @Test
    void shouldRewriteQualifiedLogicalTableAndPreserveParameters() {
        // Given
        String sql = """
                INSERT INTO app.t_order (
                    id,
                    user_id,
                    status
                )
                VALUES (?, ?, CONCAT(?, 'x'))
                """;

        // When
        var result = rewriter.rewrite(
                sql,
                LOGICAL_TABLE,
                ACTUAL_TABLE
        );

        // Then
        assertAll(
                () -> assertEquals(
                        "INSERT INTO order_db.t_order_00 "
                                + "(id, user_id, status) "
                                + "VALUES (?, ?, CONCAT(?, 'x'))",
                        result.sql()
                ),
                () -> assertEquals(
                        List.of(0, 1, 2),
                        result.sourceParameterIndexes()
                )
        );
    }

    @Test
    void shouldRejectMismatchedLogicalTable() {
        // Given
        var wrongLogicalTable =
                new QualifiedTableName(
                        List.of("app"),
                        "t_payment"
                );

        // When
        UnsupportedSqlException exception =
                assertThrows(
                        UnsupportedSqlException.class,
                        () -> rewriter.rewrite(
                                """
                                        INSERT INTO app.t_order (
                                            id,
                                            user_id
                                        )
                                        VALUES (?, ?)
                                        """,
                                wrongLogicalTable,
                                ACTUAL_TABLE
                        )
                );

        // Then
        assertEquals(
                "SQL table does not match route request: app.t_order",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectUnsupportedInsertShapes() {
        assertAll(
                () -> assertThrows(
                        UnsupportedSqlException.class,
                        () -> rewriter.rewrite(
                                """
                                        INSERT INTO app.t_order (
                                            id,
                                            user_id
                                        )
                                        VALUES (?, ?), (?, ?)
                                        """,
                                LOGICAL_TABLE,
                                ACTUAL_TABLE
                        )
                ),
                () -> assertThrows(
                        UnsupportedSqlException.class,
                        () -> rewriter.rewrite(
                                """
                                        INSERT INTO app.t_order (
                                            id,
                                            user_id
                                        )
                                        SELECT id, user_id
                                        FROM app.t_order_temp
                                        """,
                                LOGICAL_TABLE,
                                ACTUAL_TABLE
                        )
                ),
                () -> assertThrows(
                        UnsupportedSqlException.class,
                        () -> rewriter.rewrite(
                                """
                                        INSERT INTO app.t_order (
                                            user_id,
                                            amount
                                        )
                                        VALUES (
                                            ?,
                                            (
                                                SELECT MAX(amount)
                                                FROM app.t_order_history
                                            )
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
                                        INSERT INTO app.t_order (
                                            id,
                                            user_id
                                        )
                                        VALUES (?, ?)
                                        ON DUPLICATE KEY UPDATE
                                            user_id = VALUES(user_id)
                                        """,
                                LOGICAL_TABLE,
                                ACTUAL_TABLE
                        )
                )
        );
    }

    @Test
    void shouldRejectTooManyActualTableQualifiers() {
        // Given
        var unsupportedActualTable =
                new QualifiedTableName(
                        List.of(
                                "server",
                                "catalog",
                                "schema"
                        ),
                        "t_order_00"
                );

        // Then
        assertThrows(
                UnsupportedSqlException.class,
                () -> rewriter.rewrite(
                        """
                                INSERT INTO app.t_order (
                                    id,
                                    user_id
                                )
                                VALUES (?, ?)
                                """,
                        LOGICAL_TABLE,
                        unsupportedActualTable
                )
        );
    }

    @Test
    void shouldRejectInvalidArguments() {
        String sql = """
                INSERT INTO app.t_order (
                    id,
                    user_id
                )
                VALUES (?, ?)
                """;

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
                () -> assertThrows(
                        NullPointerException.class,
                        () -> rewriter.rewrite(
                                sql,
                                null,
                                ACTUAL_TABLE
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> rewriter.rewrite(
                                sql,
                                LOGICAL_TABLE,
                                null
                        )
                )
        );
    }

    @Test
    void shouldRewriteSingleColumnInsert() {
        // Given
        String sql = """
                INSERT INTO app.t_order (
                    user_id
                )
                VALUES (?)
                """;

        // When
        var result = rewriter.rewrite(
                sql,
                LOGICAL_TABLE,
                ACTUAL_TABLE
        );

        // Then
        assertAll(
                () -> assertEquals(
                        "INSERT INTO order_db.t_order_00 "
                                + "(user_id) VALUES (?)",
                        result.sql()
                ),
                () -> assertEquals(
                        List.of(0),
                        result.sourceParameterIndexes()
                )
        );
    }
}