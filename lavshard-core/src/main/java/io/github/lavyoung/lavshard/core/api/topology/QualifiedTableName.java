package io.github.lavyoung.lavshard.core.api.topology;

import java.util.List;
import java.util.Objects;

/**
 *
 * 物理表限定名称。
 *
 * <p>qualifiers 用于表达 catalog、schema 等限定部分。具体的大小写、
 * 引用符和方言转换由后续 SQL 方言层处理，本对象不直接拼接 SQL。
 *
 * @param qualifiers 表名前的限定部分，可以为空集合
 * @param table      物理表名称
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @data 2026/9/10
 */
public record QualifiedTableName(
        List<String> qualifiers,
        String table
) {

    public QualifiedTableName {
        Objects.requireNonNull(qualifiers, "qualifiers must not be null");
        for (String qualifier : qualifiers) {
            if (qualifier == null || qualifier.isBlank()) {
                throw new IllegalArgumentException("qualifier must not be null or empty");
            }
        }
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("table must not be null or empty");
        }
        qualifiers = List.copyOf(qualifiers);
    }

    /**
     * 创建不包含catalog schema限定部分的物理表名称
     *
     * @param table 物理表名称
     */
    public QualifiedTableName(String table) {
        this(List.of(), table);
    }
}
