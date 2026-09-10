package io.github.lavyoung.lavshard.core.api.exception;

/**
 * 受管逻辑表没有可用分片规则时抛出。
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/09/10
 */
public class ShardRuleNotFoundException
        extends LavShardException {

    public ShardRuleNotFoundException(String message) {
        super(message);
    }
}