package io.github.lavyoung.lavshard.core.internal.sql;

import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 基于 JSQLParser 4.9 的严格单表 DELETE 分析器。
 *
 * <p>分析器只接受单目标表 DELETE，并提取位于安全 AND
 * 路径中的等值条件。它不读取分片规则、不绑定 JDBC 参数，
 * 也不执行实际路由。</p>
 *
 * <p>OR 子树不会产生候选条件；多表、JOIN、USING、CTE
 * 和子查询等结构在执行数据库操作前直接拒绝。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public final class JSqlParserSingleTableDeleteAnalyzer {

    /**
     * 分析严格单表 DELETE。
     *
     * @param sql 逻辑 DELETE SQL
     * @return 与 JSQLParser AST 解耦的分析结果
     * @throws UnsupportedSqlException SQL 无法解析或结构不受支持
     */
    public SqlAnalysis analyze(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new UnsupportedSqlException(
                    "sql must not be blank"
            );
        }

        Statement statement = parse(sql);
        Delete delete = requireDelete(statement);

        validateDeleteShape(delete);

        Table targetTable =
                requireTargetTable(delete);

        validateReferencedTables(statement);

        List<ShardPredicate> predicates =
                extractPredicates(
                        delete.getWhere(),
                        targetTable
                );

        return new SqlAnalysis(
                SqlType.DELETE,
                List.of(
                        JSqlParserTableNameMapper.from(
                                targetTable
                        )
                ),
                predicates
        );
    }

    private static Statement parse(String sql) {
        try {
            return CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException exception) {
            throw new UnsupportedSqlException(
                    "failed to parse SQL",
                    exception
            );
        }
    }

    private static Delete requireDelete(
            Statement statement
    ) {
        if (statement instanceof Delete delete) {
            return delete;
        }

        throw new UnsupportedSqlException(
                "v0.1 only supports a single-table DELETE"
        );
    }

    private static void validateDeleteShape(
            Delete delete
    ) {
        if (hasItems(delete.getWithItemsList())) {
            throw new UnsupportedSqlException(
                    "CTE is not supported for DELETE in v0.1"
            );
        }

        if (hasItems(delete.getTables())
                || hasItems(delete.getUsingList())
                || hasItems(delete.getJoins())) {
            throw new UnsupportedSqlException(
                    "multi-table DELETE is not supported in v0.1"
            );
        }

        if (delete.getReturningClause() != null
                || delete.getOutputClause() != null) {
            throw new UnsupportedSqlException(
                    "DELETE returning clause is not supported in v0.1"
            );
        }

        if (delete.isModifierIgnore()
                || delete.isModifierQuick()
                || delete.getModifierPriority() != null) {
            throw new UnsupportedSqlException(
                    "DELETE modifiers are not supported in v0.1"
            );
        }
    }

    private static boolean hasItems(
            List<?> values
    ) {
        return values != null && !values.isEmpty();
    }

    private static Table requireTargetTable(
            Delete delete
    ) {
        Table table = delete.getTable();

        if (table == null
                || table.getName() == null
                || table.getName().isBlank()) {
            throw new UnsupportedSqlException(
                    "DELETE target must be a physical table"
            );
        }

        return table;
    }

    private static void validateReferencedTables(
            Statement statement
    ) {
        SqlShapeInspector inspector =
                new SqlShapeInspector();

        Set<String> referencedTables =
                inspector.getTables(statement);

        if (inspector.containsNestedSelect()) {
            throw new UnsupportedSqlException(
                    "subqueries are not supported for DELETE in v0.1"
            );
        }

        if (referencedTables.size() != 1) {
            throw new UnsupportedSqlException(
                    "DELETE must reference exactly one table"
            );
        }
    }

    private static List<ShardPredicate> extractPredicates(
            Expression where,
            Table targetTable
    ) {
        if (where == null) {
            return List.of();
        }

        List<ShardPredicate> predicates =
                new ArrayList<>();

        collectConjunctivePredicates(
                where,
                targetTable,
                predicates
        );

        return List.copyOf(predicates);
    }

    private static void collectConjunctivePredicates(
            Expression expression,
            Table targetTable,
            List<ShardPredicate> predicates
    ) {
        if (expression instanceof Parenthesis parenthesis) {
            collectConjunctivePredicates(
                    parenthesis.getExpression(),
                    targetTable,
                    predicates
            );
            return;
        }

        if (expression instanceof AndExpression andExpression) {
            collectConjunctivePredicates(
                    andExpression.getLeftExpression(),
                    targetTable,
                    predicates
            );
            collectConjunctivePredicates(
                    andExpression.getRightExpression(),
                    targetTable,
                    predicates
            );
            return;
        }

        if (expression instanceof OrExpression) {
            return;
        }

        if (expression instanceof EqualsTo equalsTo) {
            toPredicate(
                    equalsTo,
                    targetTable
            ).ifPresent(predicates::add);
        }
    }

    private static Optional<ShardPredicate> toPredicate(
            EqualsTo equalsTo,
            Table targetTable
    ) {
        Expression left =
                equalsTo.getLeftExpression();

        Expression right =
                equalsTo.getRightExpression();

        if (left instanceof Column column) {
            return createPredicate(
                    column,
                    right,
                    targetTable
            );
        }

        if (right instanceof Column column) {
            return createPredicate(
                    column,
                    left,
                    targetTable
            );
        }

        return Optional.empty();
    }

    private static Optional<ShardPredicate> createPredicate(
            Column column,
            Expression valueExpression,
            Table targetTable
    ) {
        validateColumnQualifier(
                column,
                targetTable
        );

        return JSqlParserValueReferenceMapper
                .from(valueExpression)
                .map(value ->
                        new ShardPredicate(
                                column.getColumnName(),
                                ShardOperator.EQUAL,
                                List.of(value)
                        )
                );
    }

    private static void validateColumnQualifier(
            Column column,
            Table targetTable
    ) {
        Table qualifier = column.getTable();

        if (qualifier == null
                || qualifier.getName() == null
                || qualifier.getName().isBlank()) {
            return;
        }

        String actualQualifier =
                qualifier.getFullyQualifiedName();

        String targetName =
                targetTable.getName();

        String targetQualifiedName =
                targetTable.getFullyQualifiedName();

        boolean matchesAlias =
                targetTable.getAlias() != null
                        && actualQualifier.equals(
                        targetTable.getAlias()
                                .getName()
                );

        if (!matchesAlias
                && !actualQualifier.equals(targetName)
                && !actualQualifier.equals(
                targetQualifiedName
        )) {
            throw new UnsupportedSqlException(
                    "column qualifier does not match DELETE table: "
                            + actualQualifier
            );
        }
    }

    private static final class SqlShapeInspector
            extends TablesNamesFinder {

        private boolean nestedSelect;

        @Override
        public void visit(
                ParenthesedSelect selectBody
        ) {
            nestedSelect = true;
            super.visit(selectBody);
        }

        private boolean containsNestedSelect() {
            return nestedSelect;
        }
    }
}