package io.github.lavyoung.lavshard.core.internal.sql;

import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JSqlParserSingleTableUpdateAnalyzerTest {
    @Test
    void shouldAnalyzeUpdatedColumnsAndShardPredicate() {
        String sql = """
                UPDATE app.t_order o
                SET o.status = ?,
                    updated_at = NOW()
                WHERE o.user_id = ?
                  AND status = ?
                """;

        var analysis =
                new JSqlParserSingleTableUpdateAnalyzer()
                        .analyze(sql);

        assertAll(
                () -> assertEquals(
                        SqlType.UPDATE,
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
                                "status",
                                "updated_at"
                        ),
                        analysis.updatedColumns()
                ),
                () -> assertEquals(
                        List.of(
                                new ShardPredicate(
                                        "user_id",
                                        ShardOperator.EQUAL,
                                        List.of(
                                                new ValueReference.Parameter(1)
                                        )
                                ),
                                new ShardPredicate(
                                        "status",
                                        ShardOperator.EQUAL,
                                        List.of(
                                                new ValueReference.Parameter(2)
                                        )
                                )
                        ),
                        analysis.predicates()
                )
        );
    }
}
