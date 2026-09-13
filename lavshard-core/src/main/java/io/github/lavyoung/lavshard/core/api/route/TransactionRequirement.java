package io.github.lavyoung.lavshard.core.api.route;

/**
 * SQL 路由决策对本地事务的最低要求。
 *
 * <p>Core 只负责识别和描述事务要求，不依赖具体事务框架。
 * Spring 等执行适配器负责执行校验。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/09/13
 */
public enum TransactionRequirement {

    /**
     * SQL 可以在事务内或事务外执行。
     */
    NONE,

    /**
     * SQL 必须在活动的本地事务中执行。
     */
    REQUIRED,

    ;
}
