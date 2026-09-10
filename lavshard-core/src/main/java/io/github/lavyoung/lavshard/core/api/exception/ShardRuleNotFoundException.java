package io.github.lavyoung.lavshard.core.api.exception;

/**
 *
 * 表没有配置分片规则时抛出。
 * <p>
 * 要点：
 * <p>
 * 场景：SQL 里的表不在 shardingRules 里
 * <p>
 * 用户可以选择忽略（走默认数据源）或抛错
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/10
 */
public class ShardRuleNotFoundException extends LavShardException {
    public ShardRuleNotFoundException(String message) {
    }
}
