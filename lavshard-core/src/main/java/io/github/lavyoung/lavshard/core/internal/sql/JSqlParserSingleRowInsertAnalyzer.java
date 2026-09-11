package io.github.lavyoung.lavshard.core.internal.sql;

import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 基于 JSQLParser 4.9 的严格单行 INSERT 分析器。
 *
 * <p>分析器只接受具有显式列清单的单表、单行 VALUES INSERT。
 * 它将列和值按照位置配对，并把能够在客户端稳定求值的字面量
 * 或 JDBC 参数转换为候选分片条件。</p>
 *
 * <p>该组件不读取分片规则，因此不会判断具体哪一列是分片键。
 * 函数、NULL、DEFAULT 等不稳定表达式不会生成候选条件，后续由
 * 分片条件解析器根据表规则执行最终校验。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public final class JSqlParserSingleRowInsertAnalyzer {

    /**
     * 分析严格的单行 INSERT。
     *
     * @param sql 待分析的逻辑 SQL
     * @return 与 JSQLParser AST 解耦的分析结果
     * @throws UnsupportedSqlException SQL 无法解析或不属于受支持的单行 INSERT
     */
    public SqlAnalysis analyze(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new UnsupportedSqlException(
                    "sql must not be blank"
            );
        }

        Statement statement = parse(sql);
        Insert insert = requireInsert(statement);

        validateInsertShape(insert);

        ExpressionList<Column> columns =
                requireExplicitColumns(insert);

        ParenthesedExpressionList<?> row =
                requireSingleValuesRow(insert);

        validateReferencedTables(statement);
        validateColumnValueCount(columns, row);

        return new SqlAnalysis(
                SqlType.INSERT,
                List.of(
                        JSqlParserTableNameMapper.from(
                                insert.getTable()
                        )
                ),
                extractPredicates(columns, row)
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

    private static void validateReferencedTables(
            Statement statement
    ) {
        SqlShapeInspector inspector =
                new SqlShapeInspector();

        Set<String> referencedTables =
                inspector.getTables(statement);

        if (inspector.containsNestedSelect()) {
            throw new UnsupportedSqlException(
                    "subqueries are not supported for INSERT in v0.1"
            );
        }

        if (referencedTables.size() != 1) {
            throw new UnsupportedSqlException(
                    "INSERT must reference exactly one table"
            );
        }
    }

    private static Insert requireInsert(
            Statement statement
    ) {
        if (statement instanceof Insert insert) {
            return insert;
        }

        throw new UnsupportedSqlException(
                "v0.1 only supports a single-row INSERT"
        );
    }

    private static void validateInsertShape(
            Insert insert
    ) {
        if (insert.getWithItemsList() != null && !insert.getWithItemsList().isEmpty()) {
            throw new UnsupportedSqlException(
                    "CTE is not supported for INSERT in v0.1"
            );
        }

        if (insert.isUseSet()) {
            throw new UnsupportedSqlException(
                    "INSERT SET is not supported in v0.1"
            );
        }

        if (insert.isUseDuplicate()) {
            throw new UnsupportedSqlException(
                    "ON DUPLICATE KEY UPDATE is not supported in v0.1"
            );
        }

        if (insert.getConflictTarget() != null
                || insert.getConflictAction() != null) {
            throw new UnsupportedSqlException(
                    "INSERT ON CONFLICT is not supported in v0.1"
            );
        }

        if (insert.getReturningClause() != null
                || insert.getOutputClause() != null) {
            throw new UnsupportedSqlException(
                    "INSERT returning clause is not supported in v0.1"
            );
        }

        if (insert.isModifierIgnore()
                || insert.getModifierPriority() != null) {
            throw new UnsupportedSqlException(
                    "INSERT modifiers are not supported in v0.1"
            );
        }
    }

    private static ExpressionList<Column> requireExplicitColumns(
            Insert insert
    ) {
        ExpressionList<Column> columns = insert.getColumns();

        if (columns == null || columns.isEmpty()) {
            throw new UnsupportedSqlException(
                    "INSERT must declare an explicit column list"
            );
        }
        return columns;
    }

    private static ParenthesedExpressionList<?> requireSingleValuesRow(
            Insert insert
    ) {
        if (!(insert.getSelect() instanceof Values values)) {
            throw new UnsupportedSqlException(
                    "INSERT SELECT is not supported in v0.1"
            );
        }

        ExpressionList<?> expressions =
                values.getExpressions();

        if (expressions
                instanceof ParenthesedExpressionList<?> row) {
            return row;
        }

        throw new UnsupportedSqlException(
                "v0.1 only supports single-row INSERT"
        );
    }

    private static void validateColumnValueCount(
            ExpressionList<Column> columns,
            ParenthesedExpressionList<?> row
    ) {
        if (columns.size() != row.size()) {
            throw new UnsupportedSqlException(
                    "INSERT column count must match value count"
            );
        }
    }

    /**
     * 按照 INSERT 列和值的位置关系生成候选条件。
     *
     * <p>函数、NULL、DEFAULT 等表达式不会被收集。分析器没有表
     * 规则，不能在这里判断这些表达式是否属于分片键；该判断留给
     * 后续的 SingleShardPredicateResolver。</p>
     *
     * @param columns INSERT 显式列清单
     * @param row     唯一的 VALUES 行
     * @return 不可变的候选条件列表
     */
    private static List<ShardPredicate> extractPredicates(
            ExpressionList<Column> columns,
            ParenthesedExpressionList<?> row
    ) {
        List<ShardPredicate> predicates = new ArrayList<>();
        for (int index = 0; index < columns.size(); index++) {
            Column column = columns.get(index);
            Expression expression = row.get(index);
            JSqlParserValueReferenceMapper
                    .from(expression)
                    .map(valueReference -> new ShardPredicate(
                            column.getColumnName(),
                            ShardOperator.EQUAL,
                            List.of(valueReference)
                    ))
                    .ifPresent(predicates::add);
        }
        return List.copyOf(predicates);
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
