package io.github.lavyoung.lavshard.mybatis.internal;

import io.github.lavyoung.lavshard.core.api.route.SqlRewriteResult;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 物理 BoundSql 重建契约。
 *
 * <p>物理 SQL 和参数投影必须在创建 MyBatis CacheKey 之前完成。
 * 重建过程不得丢失仍被引用的动态 SQL additional parameters。</p>
 */
class MyBatisBoundSqlRewriterTest {

    private final Configuration configuration =
            new Configuration();

    private final MyBatisBoundSqlRewriter rewriter =
            new MyBatisBoundSqlRewriter(configuration);

    @Test
    void shouldApplyPhysicalSqlAndProjectMappings() {
        ParameterMapping id = mapping("id", Long.class);
        ParameterMapping userId =
                mapping("userId", String.class);
        ParameterMapping status =
                mapping("status", String.class);
        Object parameterObject = Map.of(
                "id", 1001L,
                "userId", "user-123",
                "status", "PAID"
        );
        BoundSql original = boundSql(
                "UPDATE t_order SET status = ? "
                        + "WHERE id = ? AND user_id = ?",
                List.of(status, id, userId),
                parameterObject
        );
        SqlRewriteResult rewrite =
                new SqlRewriteResult(
                        "UPDATE t_order_00 SET status = ? "
                                + "WHERE user_id = ?",
                        List.of(0, 2)
                );

        BoundSql physical =
                rewriter.rewrite(original, rewrite);

        assertThat(physical.getSql()).isEqualTo(
                "UPDATE t_order_00 SET status = ? "
                        + "WHERE user_id = ?"
        );
        assertThat(physical.getParameterMappings())
                .hasSize(2);
        assertThat(physical.getParameterMappings().get(0))
                .isSameAs(status);
        assertThat(physical.getParameterMappings().get(1))
                .isSameAs(userId);
        assertThat(physical.getParameterObject())
                .isSameAs(parameterObject);
    }

    @Test
    void shouldSupportReorderedAndRepeatedMappings() {
        ParameterMapping first =
                mapping("first", String.class);
        ParameterMapping second =
                mapping("second", String.class);
        BoundSql original = boundSql(
                "SELECT ?, ?",
                List.of(first, second),
                Map.of("first", "A", "second", "B")
        );
        SqlRewriteResult rewrite =
                new SqlRewriteResult(
                        "SELECT ?, ?, ?",
                        List.of(1, 0, 1)
                );

        BoundSql physical =
                rewriter.rewrite(original, rewrite);

        assertThat(physical.getParameterMappings())
                .hasSize(3);
        assertThat(physical.getParameterMappings().get(0))
                .isSameAs(second);
        assertThat(physical.getParameterMappings().get(1))
                .isSameAs(first);
        assertThat(physical.getParameterMappings().get(2))
                .isSameAs(second);
    }

    @Test
    void shouldAllowEmptyParameterProjection() {
        BoundSql original = boundSql(
                "SELECT ?",
                List.of(mapping("value", Integer.class)),
                Map.of("value", 1)
        );

        BoundSql physical = rewriter.rewrite(
                original,
                new SqlRewriteResult(
                        "SELECT 1",
                        List.of()
                )
        );

        assertThat(physical.getParameterMappings())
                .isEmpty();
    }

    @Test
    void shouldCopyOnlyReferencedAdditionalParameters() {
        ParameterMapping foreach = mapping(
                "__frch_item_0.id",
                Long.class
        );
        ParameterMapping status =
                mapping("status", String.class);
        ParameterMapping unused =
                mapping("unusedBind", String.class);
        BoundSql original = boundSql(
                "SELECT ?, ?, ?",
                List.of(foreach, status, unused),
                Map.of("status", "PAID")
        );
        original.setAdditionalParameter(
                "__frch_item_0",
                Map.of("id", 99L)
        );
        original.setAdditionalParameter(
                "unusedBind",
                "not-required"
        );

        BoundSql physical = rewriter.rewrite(
                original,
                new SqlRewriteResult(
                        "SELECT ?, ?",
                        List.of(1, 0)
                )
        );

        assertThat(physical.hasAdditionalParameter(
                "__frch_item_0.id"
        )).isTrue();
        assertThat(physical.getAdditionalParameter(
                "__frch_item_0.id"
        )).isEqualTo(99L);
        assertThat(physical.hasAdditionalParameter(
                "unusedBind"
        )).isFalse();
    }

    @Test
    void shouldRejectOutOfBoundsProjectionIndex() {
        BoundSql original = boundSql(
                "SELECT ?",
                List.of(mapping("value", Integer.class)),
                Map.of("value", 1)
        );

        assertThatThrownBy(() ->
                rewriter.rewrite(
                        original,
                        new SqlRewriteResult(
                                "SELECT ?",
                                List.of(1)
                        )
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "source parameter index out of bounds: 1"
                );
    }

    @Test
    void shouldRejectInvalidArguments() {
        assertThatThrownBy(() ->
                new MyBatisBoundSqlRewriter(null)
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage(
                        "configuration must not be null"
                );

        SqlRewriteResult rewrite =
                new SqlRewriteResult(
                        "SELECT 1",
                        List.of()
                );

        assertThatThrownBy(() ->
                rewriter.rewrite(null, rewrite)
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("boundSql must not be null");

        BoundSql original = boundSql(
                "SELECT 1",
                List.of(),
                null
        );

        assertThatThrownBy(() ->
                rewriter.rewrite(original, null)
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("rewrite must not be null");
    }

    private BoundSql boundSql(
            String sql,
            List<ParameterMapping> mappings,
            Object parameterObject
    ) {
        return new BoundSql(
                configuration,
                sql,
                mappings,
                parameterObject
        );
    }

    private ParameterMapping mapping(
            String property,
            Class<?> javaType
    ) {
        return new ParameterMapping.Builder(
                configuration,
                property,
                javaType
        ).build();
    }
}
