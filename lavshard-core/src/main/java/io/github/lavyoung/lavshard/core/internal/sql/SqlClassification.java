package io.github.lavyoung.lavshard.core.internal.sql;

import io.github.lavyoung.lavshard.core.api.route.TransactionRequirement;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;

import java.util.List;
import java.util.Objects;

/**
 * SQL 管理边界分类结果。
 *
 * @param type   SQL 分类
 * @param tables SQL 引用的去重表集合
 * @param transactionRequirement SQL 的本地事务要求
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public record SqlClassification(
        SqlClassificationType type,
        List<QualifiedTableName> tables,
        TransactionRequirement transactionRequirement
) {

    public SqlClassification {
        Objects.requireNonNull(type, "type must not be null");

        Objects.requireNonNull(tables, "tables must not be null");

        Objects.requireNonNull(transactionRequirement, "transactionRequirement must not be null");

        for (QualifiedTableName table : tables) {
            Objects.requireNonNull(table, "table must not be null");
        }

        tables = List.copyOf(tables);
    }

    /**
     * 创建无强制事务要求的 SQL 分类结果。
     *
     * <p>保留该构造器以兼容现有分类器测试和内部调用。</p>
     *
     * @param type   SQL 分类
     * @param tables SQL 引用的去重表集合
     */
    public SqlClassification(
            SqlClassificationType type,
            List<QualifiedTableName> tables
    ) {
        this(type, tables, TransactionRequirement.NONE);
    }
}
