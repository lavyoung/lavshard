package io.github.lavyoung.lavshard.core.internal.algorithm;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardValue;
import io.github.lavyoung.lavshard.core.api.exception.ShardAlgorithmException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Murmur3HashShardAlgorithmTest {

    private static final AlgorithmConfig CONFIG =
            new AlgorithmConfig(1024, "murmur3_32_v1");

    private final Murmur3HashShardAlgorithm algorithm =
            new Murmur3HashShardAlgorithm();

    @Test
    void shouldExposeStableAlgorithmName() {
        assertThat(algorithm.name()).isEqualTo("hash_mod");
    }

    @Test
    void shouldMatchFixedHashVectors() {
        assertThat(bucketOf(ShardValue.of("user-123")))
                .isEqualTo(46);

        assertThat(bucketOf(ShardValue.of(123456789L)))
                .isEqualTo(766);

        assertThat(bucketOf(ShardValue.of(UUID.fromString(
                "123e4567-e89b-12d3-a456-426614174000"
        )))).isEqualTo(702);

        assertThat(bucketOf(ShardValue.of(
                new byte[]{0x00, (byte) 0xff, 0x10}
        ))).isEqualTo(370);
    }

    @Test
    void shouldProduceDeterministicBucket() {
        ShardValue value = ShardValue.of("same-user");

        assertThat(algorithm.calculate(value, CONFIG))
                .isEqualTo(algorithm.calculate(value, CONFIG));
    }

    @Test
    void shouldKeepBucketWithinConfiguredRange() {
        AlgorithmConfig config =
                new AlgorithmConfig(17, "murmur3_32_v1");

        for (long value = -5000; value <= 5000; value++) {
            int bucket = algorithm
                    .calculate(ShardValue.of(value), config)
                    .value();

            assertThat(bucket)
                    .isGreaterThanOrEqualTo(0)
                    .isLessThan(17);
        }
    }

    @Test
    void shouldDistinguishShardValueTypes() {
        int textBucket = bucketOf(ShardValue.of("1"));
        int longBucket = bucketOf(ShardValue.of(1L));

        assertThat(textBucket).isNotEqualTo(longBucket);
    }

    @Test
    void shouldRejectUnsupportedHashVersion() {
        AlgorithmConfig config =
                new AlgorithmConfig(1024, "unknown");

        assertThatThrownBy(() -> algorithm.calculate(
                ShardValue.of("user-123"),
                config
        ))
                .isInstanceOf(ShardAlgorithmException.class)
                .hasMessage("unsupported hashVersion: unknown");
    }

    @Test
    void shouldMapEverythingToZeroWithOneBucket() {
        AlgorithmConfig config =
                new AlgorithmConfig(1, "murmur3_32_v1");

        assertThat(algorithm.calculate(
                ShardValue.of("user-123"),
                config
        ).value()).isZero();
    }

    private int bucketOf(ShardValue value) {
        return algorithm.calculate(value, CONFIG).value();
    }
}