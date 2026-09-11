package io.github.lavyoung.lavshard.core.internal.sql;

import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import io.github.lavyoung.lavshard.core.api.route.SqlRewriteResult;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * 基于 JSQLParser 4.9 的严格单表 DELETE 改写器。
 *
 * <p>改写器只替换 DELETE 目标表对应的 AST 节点，保留原始
 * 表别名、WHERE、ORDER BY、LIMIT 以及 JDBC 参数顺序。</p>
 *
 * <p>不使用字符串替换，避免误改列限定名、字符串字面量、
 * 注释或其他包含逻辑表名的 SQL 内容。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public final class JSqlParserSingleTableDeleteRewriter {

    /**
     * 将逻辑 DELETE 改写为物理表 DELETE。
     *
     * @param sql          逻辑 DELETE SQL
     * @param logicalTable 预期逻辑表
     * @param actualTable  路由得到的物理表
     * @return 物理 SQL 和原始参数下标映射
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
        Delete delete = requireDelete(statement);

        validateDeleteShape(delete);

        Table sourceTable =
                requireTargetTable(delete);

        validateLogicalTable(
                sourceTable,
                logicalTable
        );

        SqlShapeInspector inspector =
                inspectStatement(statement);

        delete.setTable(
                createActualTable(
                        sourceTable,
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
                    "subqueries are not supported for DELETE in v0.1"
            );
        }

        if (referencedTables.size() != 1) {
            throw new UnsupportedSqlException(
                    "DELETE must reference exactly one table"
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