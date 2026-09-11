package io.github.lavyoung.lavshard.core.internal.sql;


import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
