package io.github.lavyoung.lavshard.core.internal.sql;

import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JSqlParserSingleTableSelectRewriterTest {

    private final JSqlParserSingleTableSelectRewriter rewriter =
            new JSqlParserSingleTableSelectRewriter();

    @Test
    void shouldRewriteQualifiedLogicalTable() {
        // Given
        var logicalTable = new QualifiedTableName(
                List.of("app"),
                "t_order"
        );
        var actualTable = new QualifiedTableName(
                List.of("order_db"),
                "t_order_00"
        );

        // When
        var result = rewriter.rewrite(
                """
                        SELECT *
                        FROM app.t_order o
                        WHERE o.user_id = ?
                        """,
                logicalTable,
                actualTable
        );

        // Then
        assertEquals(
                "SELECT * FROM order_db.t_order_00 o "
                        + "WHERE o.user_id = ?",
                result.sql()
        );
        assertEquals(
                List.of(0),
                result.sourceParameterIndexes()
        );
    }
}