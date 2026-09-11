package io.github.lavyoung.lavshard.core.internal.sql;

/**
 * SQL 在 LavShard 管理范围内的分类结果。
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public enum SqlClassificationType {
    /**
     * 命中分片规则，需要进入严格路由流程。
     */
    MANAGED,

    /**
     * 只引用明确声明的普通表，保持原 SQL 执行。
     */
    PASSTHROUGH
}
