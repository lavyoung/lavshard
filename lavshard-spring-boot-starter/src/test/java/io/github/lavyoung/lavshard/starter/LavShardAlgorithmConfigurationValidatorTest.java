package io.github.lavyoung.lavshard.starter;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardAlgorithm;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardBucket;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardValue;
import io.github.lavyoung.lavshard.core.api.exception.ConfigurationException;
import io.github.lavyoung.lavshard.core.api.exception.ShardAlgorithmException;
import io.github.lavyoung.lavshard.core.api.rule.RuleSnapshot;
import io.github.lavyoung.lavshard.core.api.rule.TableRule;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.api.topology.ShardNode;
import io.github.lavyoung.lavshard.core.api.topology.ShardTopology;
import io.github.lavyoung.lavshard.core.internal.algorithm.ShardAlgorithmRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * 启动期规则与分片算法兼容性校验契约。
 */
class LavShardAlgorithmConfigurationValidatorTest {

    private final LavShardAlgorithmConfigurationValidator validator =
            new LavShardAlgorithmConfigurationValidator();

    @Test
    void shouldValidateEveryRuleWithItsExactAlgorithmConfiguration() {
        RecordingAlgorithm algorithm =
                new RecordingAlgorithm("recording");
        RuleSnapshot snapshot = new RuleSnapshot(List.of(
                rule("t_order", "recording", "order-v1", 2),
                rule("t_payment", "recording", "payment-v1", 4)
        ));

        validator.validate(
                snapshot,
                new ShardAlgorithmRegistry(List.of(algorithm))
        );

        assertThat(algorithm.validatedConfigurations)
                .containsExactlyInAnyOrder(
                        new AlgorithmConfig(2, "order-v1"),
                        new AlgorithmConfig(4, "payment-v1")
                );
    }

    @Test
    void shouldAllowAnEmptyRuleSnapshot() {
        assertThatCode(() -> validator.validate(
                new RuleSnapshot(List.of()),
                new ShardAlgorithmRegistry(List.of())
        )).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectUnregisteredAlgorithmWithTableContext() {
        RuleSnapshot snapshot = new RuleSnapshot(List.of(
                rule("t_order", "missing", "missing-v1", 1)
        ));

        assertThatThrownBy(() -> validator.validate(
                snapshot,
                ShardAlgorithmRegistry.withBuiltInAlgorithms()
        ))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage(
                        "lavshard.tables.t_order.algorithm.name references "
                                + "unregistered algorithm: missing"
                );
    }

    @Test
    void shouldWrapAlgorithmConfigurationFailureWithTableContext() {
        RuleSnapshot snapshot = new RuleSnapshot(List.of(
                rule("t_order", "hash_mod", "unknown", 1)
        ));

        assertThatThrownBy(() -> validator.validate(
                snapshot,
                ShardAlgorithmRegistry.withBuiltInAlgorithms()
        ))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage(
                        "Invalid algorithm configuration for table t_order: "
                                + "unsupported hashVersion: unknown"
                )
                .hasCauseInstanceOf(ShardAlgorithmException.class);
    }

    @Test
    void shouldRejectNullCollaborators() {
        RuleSnapshot snapshot = new RuleSnapshot(List.of());
        ShardAlgorithmRegistry registry =
                ShardAlgorithmRegistry.withBuiltInAlgorithms();

        assertThatThrownBy(() -> validator.validate(null, registry))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("snapshot must not be null");
        assertThatThrownBy(() -> validator.validate(snapshot, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("algorithmRegistry must not be null");
    }

    private static TableRule rule(
            String logicalTable,
            String algorithmName,
            String hashVersion,
            int bucketCount
    ) {
        ShardNode node = new ShardNode(
                logicalTable + "-node",
                "ds0",
                new QualifiedTableName(logicalTable + "_00")
        );
        Map<Integer, String> placements =
                new java.util.HashMap<>();
        for (int bucket = 0; bucket < bucketCount; bucket++) {
            placements.put(bucket, node.nodeId());
        }
        ShardTopology topology = new ShardTopology(
                logicalTable + "-topology-v1",
                bucketCount,
                placements,
                Map.of(node.nodeId(), node)
        );
        return new TableRule(
                logicalTable + "-rule-v1",
                new QualifiedTableName(logicalTable),
                "user_id",
                algorithmName,
                new AlgorithmConfig(bucketCount, hashVersion),
                topology
        );
    }

    private static final class RecordingAlgorithm
            implements ShardAlgorithm {

        private final String name;
        private final List<AlgorithmConfig> validatedConfigurations =
                new ArrayList<>();

        private RecordingAlgorithm(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void validate(AlgorithmConfig config) {
            validatedConfigurations.add(config);
        }

        @Override
        public ShardBucket calculate(
                ShardValue value,
                AlgorithmConfig config
        ) {
            throw new AssertionError(
                    "startup validation must not calculate a shard value"
            );
        }
    }
}
