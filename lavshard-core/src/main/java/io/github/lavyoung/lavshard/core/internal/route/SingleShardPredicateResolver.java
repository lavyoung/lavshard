package io.github.lavyoung.lavshard.core.internal.route;

import io.github.lavyoung.lavshard.core.api.exception.MissingShardKeyException;
import io.github.lavyoung.lavshard.core.api.exception.ShardRuleNotFoundException;
import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import io.github.lavyoung.lavshard.core.api.rule.TableRule;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.internal.sql.ShardPredicate;
import io.github.lavyoung.lavshard.core.internal.sql.SqlAnalysis;
import io.github.lavyoung.lavshard.core.internal.sql.ValueReference;

import java.util.List;
import java.util.Objects;

/**
 * 根据表规则解析单分片 SQL 的唯一分片键引用。
 *
 * <p>该组件连接 SQL 分析和参数绑定两个阶段，但本身不读取
 * JDBC 或 MyBatis 参数，也不把原始值转换成 ShardValue。</p>
 *
 * <p>v0.1 要求每条受管 SQL 必须具有且仅具有一个安全的
 * 分片键等值条件。缺失或存在多个条件时，必须在执行 SQL
 * 之前失败。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public final class SingleShardPredicateResolver {

    /**
     * 从分析结果中解析唯一分片键引用。
     *
     * @param analysis SQL 结构分析结果
     * @param rule     SQL 对应的分片表规则
     * @return 唯一的字面量或 JDBC 参数引用
     * @throws ShardRuleNotFoundException 分析结果与规则表不匹配
     * @throws MissingShardKeyException   没有安全的分片键条件
     * @throws UnsupportedSqlException    表数量或分片键条件数量不受支持
     */
    public ValueReference resolve(SqlAnalysis analysis, TableRule rule) {
        Objects.requireNonNull(
                analysis,
                "analysis must not be null"
        );
        Objects.requireNonNull(
                rule,
                "rule must not be null"
        );

        QualifiedTableName analyzedTable = requireSingleTable(analysis);
        validateRuleTable(analyzedTable, rule);

        List<ShardPredicate> shardPredicates = analysis.predicates()
                .stream()
                .filter(shardPredicate -> shardPredicate.column().equals(rule.shardingColumn()))
                .toList();

        if (shardPredicates.isEmpty()) {
            throw new MissingShardKeyException(
                    "safe equality predicate not found "
                            + "for sharding column: "
                            + rule.shardingColumn()
            );
        }

        if (shardPredicates.size() != 1) {
            throw new UnsupportedSqlException(
                    "multiple equality predicates found "
                            + "for sharding column: "
                            + rule.shardingColumn()
            );
        }

        return shardPredicates.get(0)
                .values()
                .get(0);
    }

    private static QualifiedTableName requireSingleTable(SqlAnalysis analysis) {
        if (analysis.tables().size() != 1) {
            throw new UnsupportedSqlException(
                    "managed SQL must reference exactly one table"
            );
        }
        return analysis.tables().get(0);
    }

    private static void validateRuleTable(QualifiedTableName analyzedTable,
                                          TableRule rule) {
        if (!analyzedTable.equals(rule.logicalTable())) {
            throw new ShardRuleNotFoundException(
                    "shard rule does not match SQL table: "
                            + analyzedTable
            );
        }
    }
}
