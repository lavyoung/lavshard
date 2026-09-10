package io.github.lavyoung.lavshard.core.api.algorithm;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * 算法值
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/10
 */
public sealed interface ShardValue permits ShardValue.TextValue, ShardValue.LongValue, ShardValue.UuidValue, ShardValue.BinaryValue {

    static ShardValue of(String value) {
        return new TextValue(value);
    }

    static ShardValue of(long value) {
        return new LongValue(value);
    }

    static ShardValue of(UUID value) {
        return new UuidValue(value);
    }

    static ShardValue of(byte[] value) {
        return new BinaryValue(value);
    }


    record TextValue(String value) implements ShardValue {

        public TextValue {
            Objects.requireNonNull(value, "shard value must not be null");
        }
    }


    record LongValue(long value) implements ShardValue {

    }

    record UuidValue(UUID value) implements ShardValue {
        public UuidValue {
            Objects.requireNonNull(value, "shard value must not be null");
        }
    }


    /**
     * 二进制值没有使用 record，因为数组是可变对象，而且数组默认使用引用相等。这里需要防御性复制和内容相等语义。
     */
    final class BinaryValue implements ShardValue {
        private final byte[] value;

        public BinaryValue(byte[] value) {
            Objects.requireNonNull(value, "shard value must not be null");
            this.value = value.clone();
        }

        public byte[] value() {
            return value.clone();
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }

        @Override
        public boolean equals(Object obj) {
            return this == obj || obj instanceof BinaryValue binaryValue && Arrays.equals(value, binaryValue.value);
        }

        @Override
        public String toString() {
            return "BinaryValue[length=" + value.length + "]";
        }
    }

}
