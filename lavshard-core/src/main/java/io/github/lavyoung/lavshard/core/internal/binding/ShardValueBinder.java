package io.github.lavyoung.lavshard.core.internal.binding;

import io.github.lavyoung.lavshard.core.api.algorithm.ShardValue;
import io.github.lavyoung.lavshard.core.api.exception.ParameterBindingException;
import io.github.lavyoung.lavshard.core.internal.sql.ValueReference;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 将未绑定的 SQL 值引用转换为类型明确的分片值。
 *
 * <p>对于字面量，直接读取分析阶段保存的标量值；对于 JDBC
 * 参数引用，按照零基 sourceIndex 从有序参数列表中读取值。</p>
 *
 * <p>类型转换采用明确白名单，不调用任意对象的 toString()，
 * 避免不同运行环境产生不稳定的分片结果。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public class ShardValueBinder {
    /**
     * 绑定并规范化分片值。
     *
     * @param reference  SQL 字面量或 JDBC 参数引用
     * @param parameters 按原始 JDBC 顺序排列的实际参数
     * @return 类型明确的分片值
     * @throws ParameterBindingException 参数缺失、值为空或类型不支持
     */
    public ShardValue bind(
            ValueReference reference,
            List<?> parameters
    ) {
        Objects.requireNonNull(
                reference,
                "reference must not be null"
        );
        Objects.requireNonNull(
                parameters,
                "parameters must not be null"
        );

        Object rawValue;

        if (reference
                instanceof ValueReference.Literal literal) {
            rawValue = literal.value();
        } else if (reference
                instanceof ValueReference.Parameter parameter) {
            rawValue = parameterValue(
                    parameter,
                    parameters
            );
        } else {
            throw new ParameterBindingException(
                    "unsupported value reference type: "
                            + reference.getClass().getName()
            );
        }

        return normalize(rawValue);
    }

    private static Object parameterValue(
            ValueReference.Parameter parameter,
            List<?> parameters
    ) {
        int index = parameter.sourceIndex();

        if (index >= parameters.size()) {
            throw new ParameterBindingException(
                    "source parameter index out of range: "
                            + index
                            + ", parameter count: "
                            + parameters.size()
            );
        }

        return parameters.get(index);
    }

    /**
     * 将允许的 Java 类型转换成稳定的 ShardValue。
     *
     * @param value 原始字面量或参数值
     * @return 规范化后的分片值
     * @throws ParameterBindingException 值为空或类型不在白名单中
     */
    private static ShardValue normalize(Object value) {
        if (value == null) {
            throw new ParameterBindingException(
                    "shard value must not be null"
            );
        }

        if (value instanceof String stringValue) {
            return ShardValue.of(stringValue);
        }

        if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long) {
            return ShardValue.of(
                    ((Number) value).longValue()
            );
        }

        if (value instanceof UUID uuidValue) {
            return ShardValue.of(uuidValue);
        }

        if (value instanceof byte[] binaryValue) {
            return ShardValue.of(binaryValue);
        }

        throw new ParameterBindingException(
                "unsupported shard value type: "
                        + value.getClass().getName()
        );
    }
}
