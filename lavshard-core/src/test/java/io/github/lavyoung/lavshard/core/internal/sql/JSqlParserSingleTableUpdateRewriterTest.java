package io.github.lavyoung.lavshard.core.internal.sql;


import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;

class JSqlParserSingleTableUpdateRewriterTest {
    @Test
    void shouldRewriteUpdateAndPreserveParameters() {
        var rewriter =
                new JSqlParserSingleTableUpdateRewriter();

        var result = rewriter.rewrite(
                """
                        UPDATE app.t_order o
                        SET o.status = ?,
                            updated_at = NOW()
                        WHERE o.user_id = ?
                          AND status = ?
                        ORDER BY o.id
                        LIMIT 1
                        """,
                new QualifiedTableName(
                        List.of("app"),
                        "t_order"
                ),
                new QualifiedTableName(
                        List.of("order_db"),
                        "t_order_00"
                )
        );

        assertAll(
                () -> assertEquals(
                        "UPDATE order_db.t_order_00 o "
                                + "SET o.status = ?, "
                                + "updated_at = NOW() "
                                + "WHERE o.user_id = ? "
                                + "AND status = ? "
                                + "ORDER BY o.id LIMIT 1",
                        result.sql()
                ),
                () -> assertEquals(
                        List.of(0, 1, 2),
                        result.sourceParameterIndexes()
                )
        );
    }

    /**
     * Given UPDATE 包含多个 SET 赋值以及嵌套布尔条件，
     * When 改写逻辑表名，Then 物理 SQL 的全部 JDBC 参数都必须保留原始下标。
     */
    @Test
    void shouldPreserveParametersAcrossMultipleUpdateSets() {
        // Given
        var rewriter = new JSqlParserSingleTableUpdateRewriter();
        String sql = """
                UPDATE task
                SET state = ?,
                    update_time = ?
                WHERE user_id = ?
                  AND event_id = ?
                  AND (
                      state = ?
                      OR (state = ? AND next_retry_time <= ?)
                      OR (state = ? AND update_time <= ?)
                  )
                """;

        // When
        var result = rewriter.rewrite(
                sql,
                new QualifiedTableName(List.of(), "task"),
                new QualifiedTableName(List.of(), "task_00")
        );

        // Then
        assertAll(
                () -> assertEquals(9, result.sql().chars().filter(character -> character == '?').count()),
                () -> assertEquals(
                        List.of(0, 1, 2, 3, 4, 5, 6, 7, 8),
                        result.sourceParameterIndexes()
                )
        );
    }

    /**
     * Given 第二个 SET 赋值包含子查询，When 改写逻辑表名，Then 拒绝不受支持的 UPDATE。
     */
    @Test
    void shouldRejectSubqueryInLaterUpdateSet() {
        // Given
        var rewriter = new JSqlParserSingleTableUpdateRewriter();
        String sql = """
                UPDATE task
                SET state = ?,
                    update_time = (SELECT MAX(update_time) FROM task_archive)
                WHERE id = ?
                """;

        // When
        var exception = assertThrows(
                UnsupportedSqlException.class,
                () -> rewriter.rewrite(
                        sql,
                        new QualifiedTableName(List.of(), "task"),
                        new QualifiedTableName(List.of(), "task_00")
                )
        );

        // Then
        assertEquals(
                "subqueries are not supported for UPDATE in v0.1",
                exception.getMessage()
        );
    }
}
