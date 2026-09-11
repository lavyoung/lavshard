package io.github.lavyoung.lavshard.core.api.exception;

/**
 * LavShard 对外公开的稳定错误码。
 *
 * <p>错误码一旦发布，只允许新增，不允许修改既有编号的含义，
 * 以便日志检索、监控告警以及上层框架进行稳定识别。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public enum LavShardErrorCode {

    // 配置
    CONFIGURATION_INVALID("LAVSHARD-CORE-1001"),

    // SQL 与参数
    SQL_UNSUPPORTED("LAVSHARD-CORE-2001"),
    SHARD_KEY_MISSING("LAVSHARD-CORE-2002"),
    PARAMETER_BINDING_FAILED("LAVSHARD-CORE-2003"),

    // 分片算法
    SHARD_ALGORITHM_FAILED("LAVSHARD-CORE-3001"),

    // 规则与路由
    SHARD_RULE_NOT_FOUND("LAVSHARD-CORE-4001"),
    ROUTE_NOT_FOUND("LAVSHARD-CORE-4002"),

    // 本地事务
    TRANSACTION_ROUTE_CONFLICT("LAVSHARD-CORE-5001"),
    ;

    private final String code;

    LavShardErrorCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
