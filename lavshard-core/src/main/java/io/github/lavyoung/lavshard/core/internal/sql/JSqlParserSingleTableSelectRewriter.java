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
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;


/**
 * 基于 JSQLParser 4.9 的严格单表 SELECT 改写器。
 *
 * <p>只修改 AST 中的主表节点，不使用字符串替换。
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/09/10
 */
public final class JSqlParserSingleTableSelectRewriter {

    public SqlRewriteResult rewrite(String sql, QualifiedTableName logicalTable, QualifiedTableName actualTable) {
        if (sql == null || sql.isBlank()) {
            throw new UnsupportedSqlException("sql must not be blank");
        }

        if (logicalTable == null) {
            throw new NullPointerException(
                    "logicalTable must not be null"
            );
        }
        if (actualTable == null) {
            throw new NullPointerException(
                    "actualTable must not be null"
            );
        }

        Statement statement = parse(sql);
        PlainSelect select = requirePlainSelect(statement);

        validateSelectShape(select);

        Table sourceTable = requireSourceTable(select);
        validateLogicalTable(sourceTable, logicalTable);

        SqlShapeInspector inspector = new SqlShapeInspector();
        Set<String> referencedTables = inspector.getTables(statement);

        if (inspector.containsNestedSelect()) {
            throw new UnsupportedSqlException(
                    "subqueries are not supported in v0.1"
            );
        }

        if (referencedTables.size() != 1) {
            throw new UnsupportedSqlException(
                    "SELECT must reference exactly one table"
            );
        }

        select.setFromItem(createActualTable(sourceTable, actualTable));


        List<Integer> parameterIndexes = IntStream.range(0, inspector.jdbcParameterCount())
                .boxed()
                .toList();

        return new SqlRewriteResult(statement.toString(), parameterIndexes);
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

    private static PlainSelect requirePlainSelect(Statement statement) {
        if (statement instanceof PlainSelect select) {
            return select;
        }
        throw new UnsupportedSqlException(
                "v0.1 only supports a plain single-table SELECT"
        );
    }

    private static void validateSelectShape(PlainSelect select) {
        if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) {
            throw new UnsupportedSqlException(
                    "CTE is not supported in v0.1"
            );
        }

        if (select.getJoins() != null && !select.getJoins().isEmpty()) {
            throw new UnsupportedSqlException(
                    "JOIN is not supported in v0.1"
            );
        }
    }

    private static Table requireSourceTable(PlainSelect select) {
        if (select.getFromItem() instanceof Table table) {
            return table;
        }
        throw new UnsupportedSqlException(
                "SELECT source must be a physical table"
        );
    }

    private static void validateLogicalTable(
            Table parsedTable,
            QualifiedTableName logicalTable
    ) {
        List<String> expectedParts = qualifiedNameParts(logicalTable);
        if (!parsedTable.getNameParts().equals(expectedParts)) {
            throw new UnsupportedSqlException(
                    "SQL table does not match route request: "
                            + parsedTable.getFullyQualifiedName()
            );
        }
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
                qualifiedNameParts(actualTable)
        );

        rewrittenTable.setAlias(sourceTable.getAlias());

        return rewrittenTable;
    }

    private static List<String> qualifiedNameParts(QualifiedTableName name) {
        List<String> parts =
                new ArrayList<>(name.qualifiers());
        parts.add(name.table());
        return List.copyOf(parts);
    }

    private static final class SqlShapeInspector extends TablesNamesFinder {
        private int jdbcParameterCount;
        private boolean nestedSelect;

        @Override
        public void visit(JdbcParameter jdbcParameter) {
            jdbcParameterCount++;
        }

        @Override
        public void visit(ParenthesedSelect selectBody) {
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
