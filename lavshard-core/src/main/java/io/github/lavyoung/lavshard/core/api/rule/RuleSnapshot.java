package io.github.lavyoung.lavshard.core.api.rule;

import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;

import java.util.*;

/**
 * 一次路由使用的不可变规则快照。
 *
 * <p>快照构造完成后不允许增加、替换或删除规则。
 * 同一逻辑表出现多条规则时立即失败，避免静默覆盖。
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/09/10
 */
public final class RuleSnapshot {

    private final Map<QualifiedTableName, TableRule> rules;

    /**
     * 构造不可变规则快照。
     *
     * @param rules 完整规则集合
     * @throws NullPointerException     rules 或其中的规则为 null 时抛出
     * @throws IllegalArgumentException 同一逻辑表出现多条规则时抛出
     */
    public RuleSnapshot(Collection<? extends TableRule> rules) {
        Objects.requireNonNull(rules, "rules must not be null");

        Map<QualifiedTableName, TableRule> indexedRules =
                new HashMap<>();

        for (TableRule rule : rules) {
            Objects.requireNonNull(
                    rule,
                    "rule must not be null"
            );

            TableRule existing = indexedRules.putIfAbsent(
                    rule.logicalTable(),
                    rule
            );

            if (existing != null) {
                throw new IllegalArgumentException(
                        "duplicate shard rule for logical table: "
                                + rule.logicalTable()
                );
            }
        }

        this.rules = Map.copyOf(indexedRules);
    }

    /**
     * 按逻辑表精确查找规则。
     *
     * @param logicalTable 逻辑表
     * @return 对应规则；未配置时返回 Optional.empty()
     * @throws NullPointerException logicalTable 为 null 时抛出
     */
    public Optional<TableRule> find(
            QualifiedTableName logicalTable
    ) {
        Objects.requireNonNull(
                logicalTable,
                "logicalTable must not be null"
        );
        return Optional.ofNullable(rules.get(logicalTable));
    }
}
