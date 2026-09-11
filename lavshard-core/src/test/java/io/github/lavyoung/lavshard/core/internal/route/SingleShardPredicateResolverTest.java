package io.github.lavyoung.lavshard.core.internal.route;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.exception.MissingShardKeyException;
import io.github.lavyoung.lavshard.core.api.exception.ShardRuleNotFoundException;
import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import io.github.lavyoung.lavshard.core.api.rule.TableRule;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.api.topology.ShardNode;
import io.github.lavyoung.lavshard.core.api.topology.ShardTopology;
import io.github.lavyoung.lavshard.core.internal.sql.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SingleShardPredicateResolverTest {

    private static final QualifiedTableName LOGICAL_TABLE =
            new QualifiedTableName("t_order");

    private final SingleShardPredicateResolver resolver =
            new SingleShardPredicateResolver();

    @Test
    void shouldResolveUniqueShardParameter() {
        // Given
        var shardReference =
                new ValueReference.Parameter(1);

        var analysis = analysis(
                LOGICAL_TABLE,
                List.of(
                        predicate(
                                "status",
                                new ValueReference.Parameter(0)
                        ),
                        predicate(
                                "user_id",
                                shardReference
                        )
                )
        );

        // When
        var resolved = resolver.resolve(
                analysis,
                tableRule()
        );

        // Then
        assertThat(resolved).isEqualTo(shardReference);
    }

    @Test
    void shouldResolveShardLiteral() {
        // Given
        var shardReference =
                new ValueReference.Literal("user-1001");

        var analysis = analysis(
                LOGICAL_TABLE,
                List.of(
                        predicate(
                                "user_id",
                                shardReference
                        )
                )
        );

        // When
        var resolved = resolver.resolve(
                analysis,
                tableRule()
        );

        // Then
        assertThat(resolved).isEqualTo(shardReference);
    }

    @Test
    void shouldRejectMissingShardKey() {
        // Given
        var analysis = analysis(
                LOGICAL_TABLE,
                List.of(
                        predicate(
                                "status",
                                new ValueReference.Literal(1L)
                        )
                )
        );

        // Then
        assertThatThrownBy(() ->
                resolver.resolve(analysis, tableRule())
        )
                .isInstanceOf(MissingShardKeyException.class)
                .hasMessageContaining("user_id");
    }

    @Test
    void shouldRejectMultipleShardPredicates() {
        // Given
        var analysis = analysis(
                LOGICAL_TABLE,
                List.of(
                        predicate(
                                "user_id",
                                new ValueReference.Parameter(0)
                        ),
                        predicate(
                                "user_id",
                                new ValueReference.Parameter(1)
                        )
                )
        );

        // Then
        assertThatThrownBy(() ->
                resolver.resolve(analysis, tableRule())
        )
                .isInstanceOf(UnsupportedSqlException.class)
                .hasMessageContaining("multiple");
    }

    @Test
    void shouldRejectMismatchedTableRule() {
        // Given
        var analysis = analysis(
                new QualifiedTableName("t_user"),
                List.of(
                        predicate(
                                "user_id",
                                new ValueReference.Parameter(0)
                        )
                )
        );

        // Then
        assertThatThrownBy(() ->
                resolver.resolve(analysis, tableRule())
        )
                .isInstanceOf(
                        ShardRuleNotFoundException.class
                )
                .hasMessageContaining("t_user");
    }

    @Test
    void shouldResolveReferenceFromRealSqlAnalysis() {
        // Given
        var analyzer =
                new JSqlParserSingleTableSelectAnalyzer();

        var analysis = analyzer.analyze("""
                SELECT *
                FROM t_order
                WHERE status = ?
                  AND user_id = ?
                """);

        // When
        var resolved = resolver.resolve(
                analysis,
                tableRule()
        );

        // Then
        assertThat(resolved)
                .isEqualTo(
                        new ValueReference.Parameter(1)
                );
    }

    private static SqlAnalysis analysis(
            QualifiedTableName table,
            List<ShardPredicate> predicates
    ) {
        return new SqlAnalysis(
                SqlType.SELECT,
                List.of(table),
                predicates
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

    private static TableRule tableRule() {
        var node = new ShardNode(
                "node-0",
                "ds0",
                new QualifiedTableName("t_order_00")
        );

        var topology = new ShardTopology(
                "topology-v1",
                1,
                Map.of(0, node.nodeId()),
                Map.of(node.nodeId(), node)
        );

        return new TableRule(
                "rule-v1",
                LOGICAL_TABLE,
                "user_id",
                "hash_mod",
                new AlgorithmConfig(
                        1,
                        "murmur3_32_v1"
                ),
                topology
        );
    }
}