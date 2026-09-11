package io.github.lavyoung.lavshard.core.internal.sql;

import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import io.github.lavyoung.lavshard.core.api.route.SqlRewriteResult;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * 基于 JSQLParser 4.9 的严格单行 INSERT 改写器。
 *
 * <p>改写器只接受显式列清单的单表、单行 VALUES INSERT，
 * 并且只替换 INSERT 目标表对应的 AST 节点，不执行字符串替换。</p>
 *
 * <p>改写不会增加、删除或调整 JDBC 参数，因此改写结果中的
 * 参数映射保持为原始参数下标的连续序列。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public final class JSqlParserSingleRowInsertRewriter {

    /**
     * 将逻辑 INSERT 改写为单个物理表 INSERT。
     *
     * @param sql          待改写的逻辑 SQL
     * @param logicalTable SQL 中预期出现的逻辑表
     * @param actualTable  路由得到的物理表
     * @return 物理 SQL 及其原始参数下标映射
     * @throws UnsupportedSqlException SQL 结构或表名不符合 v0.1 约束
     */
    public SqlRewriteResult rewrite(
            String sql,
            QualifiedTableName logicalTable,
            QualifiedTableName actualTable
    ) {
        if (sql == null || sql.isBlank()) {
            throw new UnsupportedSqlException(
                    "sql must not be blank"
            );
        }

        Objects.requireNonNull(
                logicalTable,
                "logicalTable must not be null"
        );
        Objects.requireNonNull(
                actualTable,
                "actualTable must not be null"
        );

        Statement statement = parse(sql);
        Insert insert = requireInsert(statement);

        validateInsertShape(insert);

        ExpressionList<Column> columns =
                requireExplicitColumns(insert);

        List<? extends Expression> row =
                requireSingleValuesRow(insert);

        validateColumnValueCount(columns, row);
        validateLogicalTable(
                insert.getTable(),
                logicalTable
        );

        SqlShapeInspector inspector =
                inspectStatement(statement);

        insert.setTable(
                createActualTable(
                        insert.getTable(),
                        actualTable
                )
        );

        List<Integer> parameterIndexes =
                IntStream.range(
                                0,
                                inspector.jdbcParameterCount()
                        )
                        .boxed()
                        .toList();

        return new SqlRewriteResult(
                statement.toString(),
                parameterIndexes
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
        if (insert.getWithItemsList() != null
                && !insert.getWithItemsList().isEmpty()) {
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
        ExpressionList<Column> columns =
                insert.getColumns();

        if (columns == null || columns.isEmpty()) {
            throw new UnsupportedSqlException(
                    "INSERT must declare an explicit column list"
            );
        }

        return columns;
    }

    /**
     * 获取并规范化唯一的 VALUES 数据行。
     *
     * @param insert INSERT AST
     * @return 唯一数据行中的值表达式
     */
    private static List<? extends Expression> requireSingleValuesRow(
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

        if (expressions.size() == 1
                && expressions.get(0)
                instanceof Parenthesis parenthesis) {
            return List.of(
                    parenthesis.getExpression()
            );
        }

        throw new UnsupportedSqlException(
                "v0.1 only supports single-row INSERT"
        );
    }

    private static void validateColumnValueCount(
            ExpressionList<Column> columns,
            List<? extends Expression> row
    ) {
        if (columns.size() != row.size()) {
            throw new UnsupportedSqlException(
                    "INSERT column count must match value count"
            );
        }
    }

    private static void validateLogicalTable(
            Table parsedTable,
            QualifiedTableName logicalTable
    ) {
        QualifiedTableName parsedName =
                JSqlParserTableNameMapper.from(
                        parsedTable
                );

        if (!parsedName.equals(logicalTable)) {
            throw new UnsupportedSqlException(
                    "SQL table does not match route request: "
                            + parsedTable.getFullyQualifiedName()
            );
        }
    }

    private static SqlShapeInspector inspectStatement(
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

        return inspector;
    }

    private static Table createActualTable(
            Table sourceTable,
            QualifiedTableName actualTable
    ) {
        if (actualTable.qualifiers().size() > 2) {
            throw new UnsupportedSqlException(
                    "at most two table qualifiers are supported"
            );
        }

        Table rewrittenTable = new Table(
                JSqlParserTableNameMapper.toNameParts(
                        actualTable
                )
        );

        rewrittenTable.setAlias(
                sourceTable.getAlias()
        );

        return rewrittenTable;
    }

    private static final class SqlShapeInspector
            extends TablesNamesFinder {

        private int jdbcParameterCount;
        private boolean nestedSelect;

        @Override
        public void visit(
                JdbcParameter jdbcParameter
        ) {
            jdbcParameterCount++;
        }

        @Override
        public void visit(
                ParenthesedSelect selectBody
        ) {
            nestedSelect = true;
            super.visit(selectBody);
        }

        private int jdbcParameterCount() {
            return jdbcParameterCount;
        }

        private boolean containsNestedSelect() {
            return nestedSelect;
        }
    }
}