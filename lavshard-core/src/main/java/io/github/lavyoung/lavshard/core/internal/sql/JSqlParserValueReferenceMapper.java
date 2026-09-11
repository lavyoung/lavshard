package io.github.lavyoung.lavshard.core.internal.sql;

import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import net.sf.jsqlparser.expression.*;

import java.util.Optional;

/**
 * 将 JSQLParser 值表达式转换为未绑定 ValueReference。
 *
 * <p>只识别 JDBC 占位符以及当前能够稳定表达的字符串和数值
 * 字面量。函数、NULL、列引用等表达式返回空结果，由上层根据
 * 分片规则决定是否拒绝 SQL。</p>
 *
 * <p>JDBC 参数下标从 JSQLParser 的一基编号转换成 LavShard
 * 使用的零基 sourceIndex。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
class JSqlParserValueReferenceMapper {

    private JSqlParserValueReferenceMapper() {
    }

    /**
     * 尝试转换一个 SQL 值表达式。
     *
     * @param expression JSQLParser 值表达式
     * @return 可以安全表达的值引用；不支持时为空
     */
    static Optional<ValueReference> from(
            Expression expression
    ) {
        if (expression instanceof Parenthesis parenthesis) {
            return from(parenthesis.getExpression());
        }

        if (expression instanceof JdbcParameter parameter) {
            return Optional.of(
                    parameterReference(parameter)
            );
        }

        if (expression instanceof StringValue stringValue) {
            return Optional.of(
                    new ValueReference.Literal(
                            stringValue.getValue()
                    )
            );
        }

        if (expression instanceof LongValue longValue) {
            return Optional.of(
                    new ValueReference.Literal(
                            longValue.getValue()
                    )
            );
        }

        if (expression instanceof DoubleValue doubleValue) {
            return Optional.of(
                    new ValueReference.Literal(
                            doubleValue.getValue()
                    )
            );
        }

        if (expression instanceof SignedExpression signed) {
            return signedLiteral(signed);
        }

        return Optional.empty();
    }

    private static ValueReference.Parameter parameterReference(
            JdbcParameter parameter
    ) {
        Integer oneBasedIndex = parameter.getIndex();

        if (oneBasedIndex == null || oneBasedIndex <= 0) {
            throw new UnsupportedSqlException(
                    "JDBC parameter index is unavailable"
            );
        }

        return new ValueReference.Parameter(
                oneBasedIndex - 1
        );
    }

    private static Optional<ValueReference> signedLiteral(
            SignedExpression signed
    ) {
        Optional<ValueReference> unsigned =
                from(signed.getExpression());

        if (unsigned.isEmpty()
                || !(unsigned.get()
                instanceof ValueReference.Literal literal)
                || !(literal.value() instanceof Number number)) {
            return Optional.empty();
        }

        if (signed.getSign() == '+') {
            return unsigned;
        }

        if (signed.getSign() == '-'
                && number instanceof Long longValue) {
            try {
                return Optional.of(
                        new ValueReference.Literal(
                                Math.negateExact(longValue)
                        )
                );
            } catch (ArithmeticException exception) {
                throw new UnsupportedSqlException(
                        "integer literal is out of range",
                        exception
                );
            }
        }

        if (signed.getSign() == '-'
                && number instanceof Double doubleValue) {
            return Optional.of(
                    new ValueReference.Literal(-doubleValue)
            );
        }

        return Optional.empty();
    }
}
