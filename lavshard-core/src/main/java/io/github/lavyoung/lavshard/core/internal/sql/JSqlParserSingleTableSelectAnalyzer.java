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
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 基于 JSQLParser 4.9 的严格单表 SELECT 分析器。
 *
 * <p>分析器只输出与具体 AST 无关的 SQL 结构模型。它提取单表
 * SELECT 中位于安全 AND 路径上的等值条件，但不读取规则、
 * 不绑定 JDBC 参数，也不执行分片路由。</p>
 *
 * <p>OR 子树不会产生候选分片条件，避免把仅在部分逻辑分支中
 * 成立的条件错误地用于唯一分片路由。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public final class JSqlParserSingleTableSelectAnalyzer {

    /**
     * 分析严格的单表 SELECT。
     *
     * @param sql 待分析的逻辑 SQL
     * @return 与 JSQLParser AST 解耦的分析结果
     * @throws UnsupportedSqlException SQL 无法解析或不是受支持的单表 SELECT
     */
    public SqlAnalysis analyze(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new UnsupportedSqlException("sql must not be blank");
        }

        Statement statement = parse(sql);
        PlainSelect select = requirePlainSelect(statement);
        validateSelectShape(select);

        Table sourceTable = requireSourceTable(select);
        validateReferencedTables(statement);

        List<ShardPredicate> predicates = extractPredicates(select.getWhere(), sourceTable);

        return new SqlAnalysis(SqlType.SELECT,
                List.of(
                        JSqlParserTableNameMapper.from(sourceTable)
                ),
                predicates);
    }


    private static Statement parse(String sql) {
        try {
            return CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException exception) {
            throw new UnsupportedSqlException("failed to parse SQL", exception);
        }
    }

    private static PlainSelect requirePlainSelect(Statement statement) {
        if (statement instanceof PlainSelect select) {
            return select;
        }

        throw new UnsupportedSqlException("v0.1 only supports a plain single-table SELECT");
    }

    private static void validateSelectShape(PlainSelect select) {
        if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) {
            throw new UnsupportedSqlException("CTE is not supported in v0.1");
        }

        if (select.getJoins() != null && !select.getJoins().isEmpty()) {
            throw new UnsupportedSqlException("JOIN is not supported in v0.1");
        }
    }

    private static Table requireSourceTable(PlainSelect select) {
        if (select.getFromItem() instanceof Table table) {
            return table;
        }

        throw new UnsupportedSqlException("SELECT source must be a physical table");
    }

    private static void validateReferencedTables(Statement statement) {
        SqlShapeInspector inspector = new SqlShapeInspector();
        Set<String> referencedTables = inspector.getTables(statement);

        if (inspector.containsNestedSelect()) {
            throw new UnsupportedSqlException("subqueries are not supported in v0.1");
        }

        if (referencedTables.size() != 1) {
            throw new UnsupportedSqlException("SELECT must reference exactly one table");
        }
    }

    /**
     * 提取在整个 WHERE 条件中必然成立的等值条件。
     *
     * <p>AND 两侧继续递归；OR 子树整体忽略；其他非等式叶子条件
     * 不参与候选分片条件生成。JDBC 参数仍保留它在完整 SQL 中的
     * 原始零基位置。</p>
     *
     * @param where       WHERE 根表达式，可以为空
     * @param sourceTable SELECT 的唯一来源表
     * @return 不可变的候选分片条件列表
     */
    private static List<ShardPredicate> extractPredicates(Expression where, Table sourceTable) {
        if (where == null) {
            return List.of();
        }
        List<ShardPredicate> predicates = new ArrayList<>();
        collectConjunctivePredicates(where, sourceTable, predicates);

        return List.copyOf(predicates);
    }

    /**
     * 沿着合取路径递归收集安全等值条件。
     *
     * @param expression  当前表达式
     * @param sourceTable SELECT 的唯一来源表
     * @param predicates  结果收集器
     */
    private static void collectConjunctivePredicates(Expression expression, Table sourceTable, List<ShardPredicate> predicates) {
        if (expression instanceof Parenthesis parenthesis) {
            collectConjunctivePredicates(parenthesis.getExpression(), sourceTable, predicates);
            return;
        }

        if (expression instanceof AndExpression andExpression) {
            collectConjunctivePredicates(andExpression.getLeftExpression(), sourceTable, predicates);
            collectConjunctivePredicates(andExpression.getRightExpression(), sourceTable, predicates);
            return;
        }

        if (expression instanceof OrExpression) {
            return;
        }

        if (expression instanceof EqualsTo equalsTo) {
            toPredicate(equalsTo, sourceTable).ifPresent(predicates::add);
        }
    }

    private static Optional<ShardPredicate> toPredicate(EqualsTo equalsTo, Table sourceTable) {
        Expression left = equalsTo.getLeftExpression();
        Expression right = equalsTo.getRightExpression();

        if (left instanceof Column column) {
            return createPredicate(column, right, sourceTable);
        }

        if (right instanceof Column column) {
            return createPredicate(column, left, sourceTable);
        }

        return Optional.empty();
    }


    private static Optional<ShardPredicate> createPredicate(Column column, Expression valueExpression, Table sourceTable) {
        validateColumnQualifier(column, sourceTable);
        return JSqlParserValueReferenceMapper
                .from(valueExpression)
                .map(value -> new ShardPredicate(
                        column.getColumnName(),
                        ShardOperator.EQUAL,
                        List.of(value)
                ));
    }

    private static void validateColumnQualifier(Column column, Table sourceTable) {
        Table qualifier = column.getTable();
        if (qualifier == null || qualifier.getName() == null || qualifier.getName().isBlank()) {
            return;
        }
        String actualQualifier = qualifier.getFullyQualifiedName();
        String sourceName = sourceTable.getName();
        String sourceQualifiedName = sourceTable.getFullyQualifiedName();

        boolean matchesAlias = sourceTable.getAlias() != null && actualQualifier.equals(sourceTable.getAlias().getName());

        if (!matchesAlias && !actualQualifier.equals(sourceName) && !actualQualifier.equals(sourceQualifiedName)) {
            throw new UnsupportedSqlException("column qualifier does not match SELECT table: " + actualQualifier);
        }
    }

    private static final class SqlShapeInspector extends TablesNamesFinder {

        private boolean nestedSelect;

        @Override
        public void visit(ParenthesedSelect selectBody) {
            nestedSelect = true;
            super.visit(selectBody);
        }

        private boolean containsNestedSelect() {
            return nestedSelect;
        }
    }
}
