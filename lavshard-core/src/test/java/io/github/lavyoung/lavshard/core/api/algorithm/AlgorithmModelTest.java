package io.github.lavyoung.lavshard.core.api.algorithm;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class AlgorithmModelTest {

    @Test
    void shouldCreateAlgorithmConfig() {
        AlgorithmConfig config =
                new AlgorithmConfig(1024, "murmur3_32_v1");

        assertThat(config.bucketCount()).isEqualTo(1024);
        assertThat(config.hashVersion())
                .isEqualTo("murmur3_32_v1");
    }

    @Test
    void shouldRejectInvalidAlgorithmConfig() {
        assertThatThrownBy(
                () -> new AlgorithmConfig(0, "murmur3_32_v1")
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bucketCount");

        assertThatThrownBy(
                () -> new AlgorithmConfig(1024, " ")
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hashVersion");
    }

    @Test
    void shouldRejectNegativeBucket() {
        assertThatThrownBy(() -> new ShardBucket(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");
    }

    @Test
    void shouldSupportDeclaredShardValueTypes() {
        assertThat(ShardValue.of("user-123"))
                .isInstanceOf(ShardValue.TextValue.class);
        assertThat(ShardValue.of(123L))
                .isInstanceOf(ShardValue.LongValue.class);
        assertThat(ShardValue.of(UUID.randomUUID()))
                .isInstanceOf(ShardValue.UuidValue.class);
        assertThat(ShardValue.of(new byte[]{1, 2}))
                .isInstanceOf(ShardValue.BinaryValue.class);
    }

    @Test
    void shouldDefensivelyCopyBinaryValue() {
        byte[] source = {1, 2};
        ShardValue.BinaryValue value =
                (ShardValue.BinaryValue) ShardValue.of(source);

        source[0] = 9;
        byte[] returned = value.value();
        returned[1] = 9;

        assertThat(value.value()).containsExactly(1, 2);
    }

    @Test
    void shouldProvideBackwardCompatibleDefaultConfigurationValidation() {
        ShardAlgorithm algorithm = new ShardAlgorithm() {
            @Override
            public String name() {
                return "custom";
            }

            @Override
            public ShardBucket calculate(
                    ShardValue value,
                    AlgorithmConfig config
            ) {
                return new ShardBucket(0);
            }
        };

        assertThatCode(() -> algorithm.validate(
                new AlgorithmConfig(1, "custom-v1")
        )).doesNotThrowAnyException();

        assertThatThrownBy(() -> algorithm.validate(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("config must not be null");
    }
}
