package io.github.lavyoung.lavshard.core.internal.sql;

import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;

import java.util.List;
import java.util.Objects;

/**
 * 与框架和具体 SQL AST 无关的 SQL 结构分析结果。
 *
 * <p>该模型记录 SQL 类型、逻辑表、候选分片条件以及 UPDATE
 * 实际修改的列，不包含数据源、规则、物理表、MyBatis 参数
 * 对象或具体解析器 AST。</p>
 *
 * @param type           SQL 类型
 * @param tables         SQL 引用的逻辑表
 * @param predicates     SQL 中提取的候选分片条件
 * @param updatedColumns UPDATE SET 修改的列，非 UPDATE 时为空
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public record SqlAnalysis(
        SqlType type,
        List<QualifiedTableName> tables,
        List<ShardPredicate> predicates,
        List<String> updatedColumns
) {

    /**
     * 构造不包含 UPDATE 修改列的分析结果。
     *
     * @param type       SQL 类型
     * @param tables     SQL 引用的逻辑表
     * @param predicates 候选分片条件
     */
    public SqlAnalysis(
            SqlType type,
            List<QualifiedTableName> tables,
            List<ShardPredicate> predicates
    ) {
        this(type, tables, predicates, List.of());
    }

    public SqlAnalysis {
        Objects.requireNonNull(
                type,
                "sql type must not be null"
        );
        Objects.requireNonNull(
                tables,
                "tables must not be null"
        );
        Objects.requireNonNull(
                predicates,
                "predicates must not be null"
        );

        tables = List.copyOf(tables);
        predicates = List.copyOf(predicates);
        updatedColumns = List.copyOf(updatedColumns);
    }
}
