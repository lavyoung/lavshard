package io.github.lavyoung.lavshard.core.internal.sql;

import java.util.Objects;

/**
 * SQL 条件值的未绑定引用。
 *
 * <p>字面量保存解析器提取的标量值；参数引用只保存它在原始
 * JDBC 参数列表中的下标。该模型不读取 MyBatis 参数对象，
 * 也不直接产生最终的分片值。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public sealed interface ValueReference permits
        ValueReference.Literal, ValueReference.Parameter {

    /**
     * SQL 中直接出现的非空字面量。
     *
     * @param value 解析器提取的字面量值
     */
    record Literal(Object value) implements ValueReference {
        public Literal {
            Objects.requireNonNull(
                    value,
                    "literal value must not be null"
            );
        }
    }

    /**
     * SQL 中 JDBC 占位符对应的原始参数下标。
     *
     * @param sourceIndex 从零开始的原始参数下标
     */
    record Parameter(int sourceIndex) implements ValueReference {

        public Parameter {
            if (sourceIndex < 0) {
                throw new IllegalArgumentException(
                        "source parameter index must not be negative"
                );
            }
        }
    }
}
