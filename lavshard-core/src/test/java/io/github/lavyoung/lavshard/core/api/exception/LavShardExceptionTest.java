package io.github.lavyoung.lavshard.core.api.exception;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LavShardExceptionTest {

    @Test
    void shouldKeepPublishedErrorCodeValuesStable() {
        assertAll(
                () -> assertEquals(
                        "LAVSHARD-CORE-1001",
                        LavShardErrorCode.CONFIGURATION_INVALID.code()
                ),
                () -> assertEquals(
                        "LAVSHARD-CORE-2001",
                        LavShardErrorCode.SQL_UNSUPPORTED.code()
                ),
                () -> assertEquals(
                        "LAVSHARD-CORE-2002",
                        LavShardErrorCode.SHARD_KEY_MISSING.code()
                ),
                () -> assertEquals(
                        "LAVSHARD-CORE-2003",
                        LavShardErrorCode.PARAMETER_BINDING_FAILED.code()
                ),
                () -> assertEquals(
                        "LAVSHARD-CORE-3001",
                        LavShardErrorCode.SHARD_ALGORITHM_FAILED.code()
                ),
                () -> assertEquals(
                        "LAVSHARD-CORE-4001",
                        LavShardErrorCode.SHARD_RULE_NOT_FOUND.code()
                ),
                () -> assertEquals(
                        "LAVSHARD-CORE-4002",
                        LavShardErrorCode.ROUTE_NOT_FOUND.code()
                ),
                () -> assertEquals(
                        "LAVSHARD-CORE-5001",
                        LavShardErrorCode.TRANSACTION_ROUTE_CONFLICT.code()
                ),
                () -> assertEquals(
                        "LAVSHARD-CORE-5002",
                        LavShardErrorCode.TRANSACTION_REQUIRED.code()
                )
        );
    }

    @Test
    void shouldExposeCodeAndMessageForEveryBusinessException() {
        var message = "test message";
        var exceptionsByCode = Map.<LavShardErrorCode, LavShardException>of(
                LavShardErrorCode.CONFIGURATION_INVALID,
                new ConfigurationException(message),
                LavShardErrorCode.SQL_UNSUPPORTED,
                new UnsupportedSqlException(message),
                LavShardErrorCode.SHARD_KEY_MISSING,
                new MissingShardKeyException(message),
                LavShardErrorCode.PARAMETER_BINDING_FAILED,
                new ParameterBindingException(message),
                LavShardErrorCode.SHARD_ALGORITHM_FAILED,
                new ShardAlgorithmException(message),
                LavShardErrorCode.SHARD_RULE_NOT_FOUND,
                new ShardRuleNotFoundException(message),
                LavShardErrorCode.ROUTE_NOT_FOUND,
                new RouteNotFoundException(message),
                LavShardErrorCode.TRANSACTION_ROUTE_CONFLICT,
                new CrossShardTransactionException(message),
                LavShardErrorCode.TRANSACTION_REQUIRED,
                new TransactionRequiredException(message)
        );

        assertAll(
                exceptionsByCode.entrySet()
                        .stream()
                        .map(entry -> () -> assertAll(
                                () -> assertEquals(
                                        entry.getKey().code(),
                                        entry.getValue().getErrorCode()
                                ),
                                () -> assertEquals(
                                        message,
                                        entry.getValue().getMessage()
                                )
                        ))
        );
    }

    @Test
    void shouldPreserveOriginalCause() {
        var cause = new IllegalStateException("parser failure");

        var exception = new UnsupportedSqlException(
                "failed to parse SQL",
                cause
        );

        assertAll(
                () -> assertEquals(
                        LavShardErrorCode.SQL_UNSUPPORTED.code(),
                        exception.getErrorCode()
                ),
                () -> assertEquals(
                        "failed to parse SQL",
                        exception.getMessage()
                ),
                () -> assertSame(cause, exception.getCause())
        );
    }
}
