package io.github.lavyoung.lavshard.core.internal.algorithm;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardAlgorithm;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardBucket;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

/**
 * 测试
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/09/10
 */
class ShardAlgorithmRegistryTest {

    @Test
    @DisplayName("验证是否能找到期望的内置算法")
    void shouldFindBuiltInAlgorithmByExactName() {
        // Given
        ShardAlgorithmRegistry registry = ShardAlgorithmRegistry.withBuiltInAlgorithms();
        // When
        Optional<ShardAlgorithm> shardAlgorithm = registry.find(Murmur3HashShardAlgorithm.NAME);

        // Then
        assertThat(shardAlgorithm).isPresent()
                .get()
                .isInstanceOf(Murmur3HashShardAlgorithm.class);
    }

    @Test
    void shouldReturnEmptyWhenAlgorithmIsNotRegistered() {
        // Given
        ShardAlgorithmRegistry registry =
                ShardAlgorithmRegistry.withBuiltInAlgorithms();

        // When
        Optional<ShardAlgorithm> algorithm =
                registry.find("HASH_MOD");

        // Then
        assertThat(algorithm).isEmpty();
    }

    @Test
    void shouldRejectDuplicateAlgorithmName() {
        // Given
        List<ShardAlgorithm> algorithms = List.of(
                new Murmur3HashShardAlgorithm(),
                new NamedShardAlgorithm(
                        Murmur3HashShardAlgorithm.NAME
                )
        );

        // When / Then
        assertThatThrownBy(
                () -> new ShardAlgorithmRegistry(algorithms)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "duplicate shard algorithm name: hash_mod"
                );
    }

    @Test
    void shouldRejectNullRegisteredAlgorithmName() {
        // Given
        ShardAlgorithm algorithm =
                new NamedShardAlgorithm(null);

        // When / Then
        assertThatThrownBy(
                () -> new ShardAlgorithmRegistry(List.of(algorithm))
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("algorithm name must not be null");
    }

    @Test
    void shouldRejectBlankRegisteredAlgorithmName() {
        // Given
        ShardAlgorithm algorithm =
                new NamedShardAlgorithm(" ");

        // When / Then
        assertThatThrownBy(
                () -> new ShardAlgorithmRegistry(List.of(algorithm))
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("algorithm name must not be blank");
    }

    @Test
    void shouldRejectNullOrBlankLookupName() {
        // Given
        ShardAlgorithmRegistry registry =
                ShardAlgorithmRegistry.withBuiltInAlgorithms();

        // When / Then
        assertThatThrownBy(() -> registry.find(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("algorithm name must not be null");

        assertThatThrownBy(() -> registry.find(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("algorithm name must not be blank");
    }

    @Test
    void shouldNotBeAffectedBySourceCollectionChanges() {
        // Given
        ShardAlgorithm builtIn =
                new Murmur3HashShardAlgorithm();
        List<ShardAlgorithm> source = new ArrayList<>();
        source.add(builtIn);

        ShardAlgorithmRegistry registry =
                new ShardAlgorithmRegistry(source);

        // When
        source.clear();
        source.add(new NamedShardAlgorithm("added_later"));

        // Then
        assertThat(registry.find(
                Murmur3HashShardAlgorithm.NAME
        )).contains(builtIn);

        assertThat(registry.find("added_later")).isEmpty();
    }

    @Test
    void shouldCalculateFixedBucketWithRegisteredAlgorithm() {
        // Given
        ShardAlgorithmRegistry registry =
                ShardAlgorithmRegistry.withBuiltInAlgorithms();
        AlgorithmConfig config =
                new AlgorithmConfig(1024, "murmur3_32_v1");

        ShardAlgorithm algorithm = registry
                .find(Murmur3HashShardAlgorithm.NAME)
                .orElseThrow();

        // When
        ShardBucket bucket = algorithm.calculate(
                ShardValue.of("user-123"),
                config
        );

        // Then
        assertThat(bucket).isEqualTo(new ShardBucket(46));
    }

    private record NamedShardAlgorithm(
            String name
    ) implements ShardAlgorithm {

        @Override
        public ShardBucket calculate(
                ShardValue value,
                AlgorithmConfig config
        ) {
            return new ShardBucket(0);
        }
    }
}
