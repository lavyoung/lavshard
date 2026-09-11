package io.github.lavyoung.lavshard.core.internal.sql;

import java.util.List;
import java.util.Objects;

/**
 * SQL 中可能用于分片路由的列条件。
 *
 * <p>v0.1 只支持单值等式，因此 values 必须且只能包含一个
 * 字面量或参数引用。该约束可以防止不完整模型继续进入路由阶段。</p>
 *
 * @param column   条件中的列名
 * @param operator 条件操作符
 * @param values   未绑定的条件值引用
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public record ShardPredicate(
        String column,
        ShardOperator operator,
        List<ValueReference> values
) {

    public ShardPredicate {
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException(
                    "predicate column must not be blank"
            );
        }

        Objects.requireNonNull(
                operator,
                "predicate operator must not be null"
        );
        Objects.requireNonNull(
                values,
                "predicate values must not be null"
        );

        if (values.size() != 1) {
            throw new IllegalArgumentException(
                    "EQUAL predicate must contain exactly one value"
            );
        }

        values = List.copyOf(values);
    }
}
