package io.github.lavyoung.lavshard.core.internal.sql;

import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import io.github.lavyoung.lavshard.core.api.rule.RuleSnapshot;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 基于 JSQLParser 的 SQL 管理边界分类器。
 *
 * <p>分类器只识别 SQL 引用的表，并判断它们属于分片规则、
 * 普通表允许列表还是未知范围。它不负责参数绑定、分片计算、
 * SQL 改写或数据库访问。</p>
 *
 * <p>无法安全分类的 SQL 必须在访问数据库之前失败，不能因为
 * 没找到分片规则就自动降级到默认数据源。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public final class JSqlParserStatementClassifier {

    private final Set<QualifiedTableName> ordinaryTables;

    /**
     * 创建 SQL 分类器。
     *
     * @param ordinaryTables 明确允许透传的普通表
     * @throws NullPointerException 集合或其中的表为空时抛出
     */
    public JSqlParserStatementClassifier(
            Set<QualifiedTableName> ordinaryTables
    ) {
        Objects.requireNonNull(
                ordinaryTables,
                "ordinaryTables must not be null"
        );

        for (QualifiedTableName table : ordinaryTables) {
            Objects.requireNonNull(
                    table,
                    "ordinary table must not be null"
            );
        }

        this.ordinaryTables = Set.copyOf(ordinaryTables);
    }

    /**
     * 根据规则快照和普通表允许列表对 SQL 分类。
     *
     * @param snapshot 当前不可变分片规则快照
     * @param sql      待分类 SQL
     * @return MANAGED 或 PASSTHROUGH 分类结果
     * @throws NullPointerException    snapshot 为空时抛出
     * @throws UnsupportedSqlException SQL 无法安全分类时抛出
     */
    public SqlClassification classify(
            RuleSnapshot snapshot,
            String sql
    ) {
        Objects.requireNonNull(
                snapshot,
                "snapshot must not be null"
        );

        Statement statement = parse(sql);

        validateStatementType(statement);

        List<QualifiedTableName> tables =
                collectTables(statement);

        if (tables.isEmpty()) {
            return classifyTablelessStatement(statement);
        }

        List<QualifiedTableName> unknownTables =
                findUnknownTables(
                        snapshot,
                        tables
                );

        if (!unknownTables.isEmpty()) {
            throw new UnsupportedSqlException(
                    "table is not configured as managed or ordinary: "
                            + unknownTables.get(0)
            );
        }

        long managedTableCount = tables.stream()
                .filter(table ->
                        snapshot.find(table).isPresent()
                )
                .count();

        long ordinaryTableCount = tables.stream()
                .filter(ordinaryTables::contains)
                .count();

        if (managedTableCount > 0
                && ordinaryTableCount > 0) {
            throw new UnsupportedSqlException(
                    "managed and ordinary tables must not be mixed"
            );
        }

        if (managedTableCount > 1) {
            throw new UnsupportedSqlException(
                    "v0.1 only supports one managed table per SQL"
            );
        }

        if (managedTableCount == 1) {
            return new SqlClassification(
                    SqlClassificationType.MANAGED,
                    tables
            );
        }

        return new SqlClassification(
                SqlClassificationType.PASSTHROUGH,
                tables
        );
    }

    /**
     * 解析 SQL。
     *
     * @param sql 原始 SQL
     * @return JSQLParser Statement
     * @throws UnsupportedSqlException SQL 为空或无法解析时抛出
     */
    private static Statement parse(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new UnsupportedSqlException(
                    "sql must not be blank"
            );
        }

        try {
            return CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException exception) {
            throw new UnsupportedSqlException(
                    "failed to parse SQL",
                    exception
            );
        }
    }

    private static void validateStatementType(
            Statement statement
    ) {
        boolean supported =
                statement instanceof Select
                        || statement instanceof Insert
                        || statement instanceof Update
                        || statement instanceof Delete;

        if (!supported) {
            throw new UnsupportedSqlException(
                    "v0.1 only supports SELECT, INSERT, "
                            + "UPDATE and DELETE"
            );
        }
    }

    private static List<QualifiedTableName> collectTables(
            Statement statement
    ) {
        TableCollector collector = new TableCollector();

        return collector.collect(statement);
    }

    private List<QualifiedTableName> findUnknownTables(
            RuleSnapshot snapshot,
            List<QualifiedTableName> tables
    ) {
        return tables.stream()
                .filter(table ->
                        snapshot.find(table).isEmpty()
                )
                .filter(table ->
                        !ordinaryTables.contains(table)
                )
                .toList();
    }

    private static SqlClassification
    classifyTablelessStatement(
            Statement statement
    ) {
        if (statement instanceof Select) {
            return new SqlClassification(
                    SqlClassificationType.PASSTHROUGH,
                    List.of()
            );
        }

        throw new UnsupportedSqlException(
                "DML statement must reference a table"
        );
    }

    /**
     * 收集 SQL AST 中真实出现的物理表节点。
     *
     * <p>直接收集 Table AST，而不是拆分字符串形式的完整表名，
     * 避免把 schema、catalog 与 table 的顺序处理错误。</p>
     */
    private static final class TableCollector
            extends TablesNamesFinder {

        private final Set<QualifiedTableName> tables =
                new LinkedHashSet<>();

        private List<QualifiedTableName> collect(
                Statement statement
        ) {
            getTables(statement);
            return List.copyOf(tables);
        }

        @Override
        public void visit(Table table) {
            tables.add(
                    JSqlParserTableNameMapper.from(table)
            );

            super.visit(table);
        }
    }
}