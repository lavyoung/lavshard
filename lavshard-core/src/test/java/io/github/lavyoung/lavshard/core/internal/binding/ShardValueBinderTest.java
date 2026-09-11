package io.github.lavyoung.lavshard.core.internal.binding;

import io.github.lavyoung.lavshard.core.api.algorithm.ShardValue;
import io.github.lavyoung.lavshard.core.api.exception.ParameterBindingException;
import io.github.lavyoung.lavshard.core.internal.sql.ValueReference;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ShardValueBinderTest {

    private final ShardValueBinder binder =
            new ShardValueBinder();

    @Test
    void shouldBindSupportedLiteralTypes() {
        // Given
        var uuid = UUID.randomUUID();
        byte[] bytes = {1, 2, 3};

        // Then
        assertAll(
                () -> assertEquals(
                        ShardValue.of("user-1001"),
                        bindLiteral("user-1001")
                ),
                () -> assertEquals(
                        ShardValue.of(7L),
                        bindLiteral((byte) 7)
                ),
                () -> assertEquals(
                        ShardValue.of(8L),
                        bindLiteral((short) 8)
                ),
                () -> assertEquals(
                        ShardValue.of(9L),
                        bindLiteral(9)
                ),
                () -> assertEquals(
                        ShardValue.of(10L),
                        bindLiteral(10L)
                ),
                () -> assertEquals(
                        ShardValue.of(uuid),
                        bindLiteral(uuid)
                ),
                () -> assertEquals(
                        ShardValue.of(bytes),
                        bindLiteral(bytes)
                )
        );
    }

    @Test
    void shouldBindParameterByZeroBasedIndex() {
        // Given
        var reference =
                new ValueReference.Parameter(1);
        var parameters = List.of(
                "PAID",
                1001L
        );

        // When
        var value = binder.bind(
                reference,
                parameters
        );

        // Then
        assertEquals(
                ShardValue.of(1001L),
                value
        );
    }

    @Test
    void shouldRejectParameterIndexOutOfRange() {
        // Given
        var reference =
                new ValueReference.Parameter(2);

        // Then
        assertThrows(
                ParameterBindingException.class,
                () -> binder.bind(
                        reference,
                        List.of("PAID", 1001L)
                )
        );
    }

    @Test
    void shouldRejectNullShardValue() {
        // Given
        var reference =
                new ValueReference.Parameter(0);
        var parameters =
                Collections.singletonList(null);

        // Then
        var exception = assertThrows(
                ParameterBindingException.class,
                () -> binder.bind(
                        reference,
                        parameters
                )
        );

        assertEquals(
                "shard value must not be null",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectUnsupportedShardValueType() {
        // Given
        var reference =
                new ValueReference.Literal(1.5D);

        // Then
        var exception = assertThrows(
                ParameterBindingException.class,
                () -> binder.bind(
                        reference,
                        List.of()
                )
        );

        assertEquals(
                "unsupported shard value type: java.lang.Double",
                exception.getMessage()
        );
    }

    private ShardValue bindLiteral(Object value) {
        return binder.bind(
                new ValueReference.Literal(value),
                List.of()
        );
    }
}