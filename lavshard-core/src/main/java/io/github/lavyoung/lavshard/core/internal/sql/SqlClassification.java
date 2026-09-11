package io.github.lavyoung.lavshard.core.internal.sql;

import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;

import java.util.List;
import java.util.Objects;

/**
 * SQL 管理边界分类结果。
 *
 * @param type   SQL 分类
 * @param tables SQL 引用的去重表集合
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public record SqlClassification(
        SqlClassificationType type,
        List<QualifiedTableName> tables
) {

    public SqlClassification {
        Objects.requireNonNull(
                type,
                "type must not be null"
        );

        Objects.requireNonNull(
                tables,
                "tables must not be null"
        );

        for (QualifiedTableName table : tables) {
            Objects.requireNonNull(
                    table,
                    "table must not be null"
            );
        }

        tables = List.copyOf(tables);
    }
}
