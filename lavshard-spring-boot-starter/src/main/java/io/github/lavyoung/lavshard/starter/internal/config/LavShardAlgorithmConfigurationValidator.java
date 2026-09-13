package io.github.lavyoung.lavshard.starter.internal.config;


import io.github.lavyoung.lavshard.core.api.algorithm.ShardAlgorithm;
import io.github.lavyoung.lavshard.core.api.exception.ConfigurationException;
import io.github.lavyoung.lavshard.core.api.rule.RuleSnapshot;
import io.github.lavyoung.lavshard.core.api.rule.TableRule;
import io.github.lavyoung.lavshard.core.internal.algorithm.ShardAlgorithmRegistry;

import java.util.Objects;

/**
 * 启动期分片规则与算法注册表兼容性校验器。
 *
 * <p>该校验器验证每条规则引用的算法已经注册，并把规则中的
 * {@code AlgorithmConfig} 交给对应算法进行配置兼容性校验。</p>
 *
 * <p>校验过程不得创建虚构分片键或调用算法的 calculate 方法，
 * 因为自定义算法可能只接受特定的 ShardValue 类型。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/09/13
 */
public final class LavShardAlgorithmConfigurationValidator {

    /**
     * 校验快照中的全部规则。
     *
     * @param snapshot          已完成结构校验的规则快照
     * @param algorithmRegistry 当前不可变算法注册表
     * @throws NullPointerException   snapshot 或 algorithmRegistry 为空时抛出
     * @throws ConfigurationException 算法未注册或拒绝规则配置时抛出
     */
    public void validate(RuleSnapshot snapshot, ShardAlgorithmRegistry algorithmRegistry) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(algorithmRegistry, "algorithmRegistry must not be null");

        for (TableRule rule : snapshot.rules()) {
            validate(rule, algorithmRegistry);
        }
    }

    /**
     * 校验单张逻辑表的算法引用与算法参数。
     *
     * @param rule              当前表规则
     * @param algorithmRegistry 算法注册表
     * @throws ConfigurationException 算法未注册或配置不兼容时抛出
     */
    private static void validate(TableRule rule, ShardAlgorithmRegistry algorithmRegistry) {
        String logicalTable = rule.logicalTable().table();

        ShardAlgorithm algorithm = algorithmRegistry.find(rule.algorithmName())
                .orElseThrow(() -> new ConfigurationException(
                        "lavshard.tables."
                                + logicalTable
                                + ".algorithm.name references "
                                + "unregistered algorithm: "
                                + rule.algorithmName()
                ));

        try {
            algorithm.validate(rule.algorithmConfig());
        } catch (RuntimeException exception) {
            throw new ConfigurationException(
                    "Invalid algorithm configuration for table "
                            + logicalTable
                            + ": "
                            + exception.getMessage(),
                    exception
            );
        }
    }
}
