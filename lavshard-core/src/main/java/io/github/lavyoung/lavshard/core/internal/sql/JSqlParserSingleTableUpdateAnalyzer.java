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
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 基于 JSQLParser 4.9 的严格单表 UPDATE 分析器。
 *
 * <p>分析器提取安全 AND 路径中的等值条件，并记录 SET 子句
 * 实际修改的列。它不读取分片规则，因此只描述 SQL 结构，
 * 不在这里判断哪一列是分片键。</p>
 *
 * <p>JOIN、FROM、多表 UPDATE、子查询和复杂赋值结构全部在
 * 路由之前拒绝，避免产生跨节点更新或不完整路由。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public final class JSqlParserSingleTableUpdateAnalyzer {

    /**
     * 分析严格的单表 UPDATE。
     *
     * @param sql 逻辑 UPDATE SQL
     * @return SQL 结构分析结果
     * @throws UnsupportedSqlException SQL 无法解析或不满足安全边界
     */
    public SqlAnalysis analyze(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new UnsupportedSqlException(
                    "sql must not be blank"
            );
        }

        Statement statement = parse(sql);
        Update update = requireUpdate(statement);

        validateUpdateShape(update);

        Table targetTable = requireTargetTable(update);

        List<String> updatedColumns =
                extractUpdatedColumns(
                        update,
                        targetTable
                );

        validateReferencedTables(statement);

        List<ShardPredicate> predicates =
                extractPredicates(
                        update.getWhere(),
                        targetTable
                );

        return new SqlAnalysis(
                SqlType.UPDATE,
                List.of(
                        JSqlParserTableNameMapper.from(
                                targetTable
                        )
                ),
                predicates,
                updatedColumns
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

    private static Update requireUpdate(
            Statement statement
    ) {
        if (statement instanceof Update update) {
            return update;
        }

        throw new UnsupportedSqlException(
                "v0.1 only supports a single-table UPDATE"
        );
    }

    private static void validateUpdateShape(
            Update update
    ) {
        if (update.getWithItemsList() != null
                && !update.getWithItemsList().isEmpty()) {
            throw new UnsupportedSqlException(
                    "CTE is not supported for UPDATE in v0.1"
            );
        }

        if (update.getFromItem() != null
                || hasItems(update.getJoins())
                || hasItems(update.getStartJoins())) {
            throw new UnsupportedSqlException(
                    "multi-table UPDATE is not supported in v0.1"
            );
        }

        if (update.getSelect() != null
                || update.isUseSelect()) {
            throw new UnsupportedSqlException(
                    "subqueries are not supported for UPDATE in v0.1"
            );
        }

        if (update.isUseColumnsBrackets()) {
            throw new UnsupportedSqlException(
                    "tuple assignment is not supported in v0.1"
            );
        }

        if (update.getReturningClause() != null
                || update.getOutputClause() != null) {
            throw new UnsupportedSqlException(
                    "UPDATE returning clause is not supported in v0.1"
            );
        }

        if (update.isModifierIgnore() || update.getModifierPriority() != null) {
            throw new UnsupportedSqlException(
                    "UPDATE modifiers are not supported in v0.1"
            );
        }
    }

    private static boolean hasItems(
            List<?> values
    ) {
        return values != null && !values.isEmpty();
    }

    private static Table requireTargetTable(
            Update update
    ) {
        Table table = update.getTable();

        if (table == null
                || table.getName() == null
                || table.getName().isBlank()) {
            throw new UnsupportedSqlException(
                    "UPDATE target must be a physical table"
            );
        }

        return table;
    }

    private static List<String> extractUpdatedColumns(
            Update update,
            Table targetTable
    ) {
        List<UpdateSet> updateSets =
                update.getUpdateSets();

        if (updateSets == null || updateSets.isEmpty()) {
            throw new UnsupportedSqlException(
                    "UPDATE must contain SET assignments"
            );
        }

        List<String> columns =
                new ArrayList<>();

        for (UpdateSet updateSet : updateSets) {
            if (updateSet.getColumns() == null
                    || updateSet.getValues() == null
                    || updateSet.getColumns().size() != 1
                    || updateSet.getValues().size() != 1) {
                throw new UnsupportedSqlException(
                        "v0.1 only supports simple UPDATE assignments"
                );
            }

            Column column =
                    updateSet.getColumn(0);

            validateColumnQualifier(
                    column,
                    targetTable
            );

            columns.add(
                    column.getColumnName()
            );
        }

        return List.copyOf(columns);
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
                    "subqueries are not supported for UPDATE in v0.1"
            );
        }

        if (referencedTables.size() != 1) {
            throw new UnsupportedSqlException(
                    "UPDATE must reference exactly one table"
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
                    "column qualifier does not match UPDATE table: "
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