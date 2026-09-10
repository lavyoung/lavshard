package io.github.lavyoung.lavshard.core.api.route;

import java.util.List;
import java.util.Objects;

/**
 * 框架无关的 SQL 改写结果。
 *
 * @param sql                    可执行物理 SQL
 * @param sourceParameterIndexes 物理 SQL 参数引用的原始参数下标
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/10
 */
public record SqlRewriteResult(
        String sql,
        List<Integer> sourceParameterIndexes
) {

    public SqlRewriteResult {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException(
                    "sql must not be blank"
            );
        }

        Objects.requireNonNull(
                sourceParameterIndexes,
                "sourceParameterIndexes must not be null"
        );

        for (Integer index : sourceParameterIndexes) {
            if (index == null || index < 0) {
                throw new IllegalArgumentException(
                        "source parameter index must not be negative"
                );
            }
        }

        sourceParameterIndexes =
                List.copyOf(sourceParameterIndexes);
    }
}
