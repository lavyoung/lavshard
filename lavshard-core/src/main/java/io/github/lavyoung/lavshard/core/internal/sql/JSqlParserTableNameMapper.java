package io.github.lavyoung.lavshard.core.internal.sql;

import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import net.sf.jsqlparser.schema.Table;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 在 JSQLParser 表节点和 LavShard 表名模型之间转换。
 *
 * <p>JSQLParser 的 getNameParts() 使用 table、schema、catalog
 * 顺序，而构造 Table 时使用 catalog、schema、table 顺序。
 * 该组件集中隔离这种不对称约定。</p>
 *
 * <p>类保持包级可见，避免把具体解析器类型暴露为 core 公共 API。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
final class JSqlParserTableNameMapper {

    private JSqlParserTableNameMapper() {
    }

    /**
     * 将 JSQLParser 表节点转换为领域表名。
     *
     * @param table JSQLParser 表节点
     * @return 与解析器类型解耦的限定表名
     */
    static QualifiedTableName from(Table table) {
        Objects.requireNonNull(
                table,
                "table must not be null"
        );

        List<String> reversedParts = table.getNameParts();

        if (reversedParts.isEmpty()) {
            throw new UnsupportedSqlException(
                    "table name must not be empty"
            );
        }

        List<String> qualifiers = new ArrayList<>(
                reversedParts.subList(
                        1,
                        reversedParts.size()
                )
        );
        Collections.reverse(qualifiers);

        return new QualifiedTableName(
                qualifiers,
                reversedParts.get(0)
        );
    }

    /**
     * 生成 JSQLParser Table 构造器需要的正序名称列表。
     *
     * @param name LavShard 限定表名
     * @return catalog、schema、table 顺序的名称列表
     */
    static List<String> toNameParts(
            QualifiedTableName name
    ) {
        Objects.requireNonNull(
                name,
                "name must not be null"
        );

        List<String> parts =
                new ArrayList<>(name.qualifiers());
        parts.add(name.table());

        return List.copyOf(parts);
    }
}
