package io.github.lavyoung.lavshard.core.internal.sql;

import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlAnalysisModelTest {

    @Test
    void shouldCreateImmutableSqlAnalysis() {
        // Given
        var table = new QualifiedTableName("t_order");
        var predicate = new ShardPredicate(
                "user_id",
                ShardOperator.EQUAL,
                List.of(new ValueReference.Parameter(0))
        );
        var sourceTables = new ArrayList<>(List.of(table));
        var sourcePredicates = new ArrayList<>(List.of(predicate));

        // When
        var analysis = new SqlAnalysis(
                SqlType.SELECT,
                sourceTables,
                sourcePredicates
        );
        sourceTables.clear();
        sourcePredicates.clear();

        // Then
        assertAll(
                () -> assertEquals(
                        SqlType.SELECT,
                        analysis.type()
                ),
                () -> assertEquals(
                        List.of(table),
                        analysis.tables()
                ),
                () -> assertEquals(
                        List.of(predicate),
                        analysis.predicates()
                ),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> analysis.tables().clear()
                ),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> analysis.predicates().clear()
                )
        );
    }

    @Test
    void shouldAllowSqlWithoutTableOrPredicate() {
        // When
        var analysis = new SqlAnalysis(
                SqlType.SELECT,
                List.of(),
                List.of()
        );

        // Then
        assertAll(
                () -> assertEquals(List.of(), analysis.tables()),
                () -> assertEquals(List.of(), analysis.predicates())
        );
    }

    @Test
    void shouldRejectInvalidShardPredicate() {
        // Given
        var value = new ValueReference.Parameter(0);

        // Then
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new ShardPredicate(
                                " ",
                                ShardOperator.EQUAL,
                                List.of(value)
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new ShardPredicate(
                                "user_id",
                                null,
                                List.of(value)
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new ShardPredicate(
                                "user_id",
                                ShardOperator.EQUAL,
                                List.of()
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new ShardPredicate(
                                "user_id",
                                ShardOperator.EQUAL,
                                List.of(value, value)
                        )
                )
        );
    }

    @Test
    void shouldValidateValueReferences() {
        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new ValueReference.Literal(null)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new ValueReference.Parameter(-1)
                ),
                () -> assertEquals(
                        "1001",
                        new ValueReference.Literal("1001").value()
                ),
                () -> assertEquals(
                        1,
                        new ValueReference.Parameter(1).sourceIndex()
                )
        );
    }
}