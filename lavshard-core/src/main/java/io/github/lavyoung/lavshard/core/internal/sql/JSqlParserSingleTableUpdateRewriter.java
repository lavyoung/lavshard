package io.github.lavyoung.lavshard.core.internal.sql;

import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import io.github.lavyoung.lavshard.core.api.route.SqlRewriteResult;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * 基于 JSQLParser 4.9 的严格单表 UPDATE 改写器。
 *
 * <p>只替换 UPDATE 目标表 AST，保留别名、SET、WHERE、
 * ORDER BY、LIMIT 和所有 JDBC 参数的原始顺序。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public final class JSqlParserSingleTableUpdateRewriter {

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

        if (!(statement instanceof Update update)) {
            throw new UnsupportedSqlException(
                    "v0.1 only supports a single-table UPDATE"
            );
        }

        validateShape(update);

        Table sourceTable = update.getTable();
        validateLogicalTable(
                sourceTable,
                logicalTable
        );

        SqlShapeInspector inspector =
                inspect(statement);

        update.setTable(
                createActualTable(
                        sourceTable,
                        actualTable
                )
        );

        List<Integer> indexes =
                IntStream.range(
                                0,
                                inspector.jdbcParameterCount()
                        )
                        .boxed()
                        .toList();

        return new SqlRewriteResult(
                statement.toString(),
                indexes
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

    private static void validateShape(
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

        if (update.isModifierIgnore()
                || update.getModifierPriority() != null) {
            throw new UnsupportedSqlException(
                    "UPDATE modifiers are not supported in v0.1"
            );
        }

        if (update.getUpdateSets() == null
                || update.getUpdateSets().isEmpty()) {
            throw new UnsupportedSqlException(
                    "UPDATE must contain SET assignments"
            );
        }
    }

    private static boolean hasItems(
            List<?> values
    ) {
        return values != null && !values.isEmpty();
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

    private static SqlShapeInspector inspect(
            Statement statement
    ) {
        SqlShapeInspector inspector =
                new SqlShapeInspector();

        Set<String> tables =
                inspector.getTables(statement);

        if (inspector.containsNestedSelect()) {
            throw new UnsupportedSqlException(
                    "subqueries are not supported for UPDATE in v0.1"
            );
        }

        if (tables.size() != 1) {
            throw new UnsupportedSqlException(
                    "UPDATE must reference exactly one table"
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

        Table rewrittenTable =
                new Table(
                        JSqlParserTableNameMapper
                                .toNameParts(actualTable)
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