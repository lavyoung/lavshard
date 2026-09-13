package io.github.lavyoung.lavshard.mybatis.internal.routing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 延迟连接的失败保真、清理与重试契约。
 * 使用 JDBC 动态代理注入精确失败，不连接真实数据库。
 */
class LazyRoutingConnectionFailureTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("initializationFailures")
    void shouldPreserveInitializationFailureAndSuppressCleanupFailure(FailureCase scenario) throws SQLException {
        // Given
        Throwable original = scenario.initializationKind().failure("initialization failed");
        Throwable cleanup = scenario.cleanupKind().failure("cleanup failed");
        PhysicalProbe physical = new PhysicalProbe();
        physical.failProperty = scenario.property();
        physical.propertyFailure = original;
        physical.closeFailure = cleanup;
        Connection logical = LazyRoutingConnection.create(physical::connection);
        logical.setReadOnly(true);
        logical.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        logical.setAutoCommit(false);

        // When / Then: cleanup never hides the primary JDBC or runtime exception.
        assertThatThrownBy(logical::createStatement).isSameAs(original);
        assertThat(original.getSuppressed()).containsExactly(cleanup);
        assertThat(physical.closeAttempts).isEqualTo(1);
        assertThat(physical.statementAttempts).isZero();
        assertThat(logical.isClosed()).isFalse();
        logical.close();
        assertThat(physical.closeAttempts).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(FailureKind.class)
    void shouldKeepOriginalFailureWhenCleanupThrowsSameInstance(FailureKind kind) throws SQLException {
        // Given: a driver or wrapper may reuse the same exception object.
        Throwable original = kind.failure("shared failure");
        PhysicalProbe physical = new PhysicalProbe();
        physical.failProperty = "setReadOnly";
        physical.propertyFailure = original;
        physical.closeFailure = original;
        Connection logical = LazyRoutingConnection.create(physical::connection);
        logical.setReadOnly(true);

        // When / Then: Throwable.addSuppressed must not receive itself.
        assertThatThrownBy(logical::createStatement).isSameAs(original);
        assertThat(original.getSuppressed()).isEmpty();
        assertThat(physical.closeAttempts).isEqualTo(1);
        logical.close();
    }

    @ParameterizedTest
    @EnumSource(FailureKind.class)
    void shouldRetryPhysicalCloseWhileKeepingLogicalConnectionClosed(FailureKind kind) throws SQLException {
        // Given: the connection has been successfully acquired before close fails.
        PhysicalProbe physical = new PhysicalProbe();
        Throwable failure = kind.failure("close failed");
        AtomicInteger acquisitions = new AtomicInteger();
        Connection logical = LazyRoutingConnection.create(() -> {
            acquisitions.incrementAndGet();
            return physical.connection();
        });
        logical.createStatement();
        physical.closeFailure = failure;

        // When
        assertThatThrownBy(logical::close).isSameAs(failure);

        // Then: no new SQL is allowed, but resource cleanup can be retried.
        assertThat(logical.isClosed()).isTrue();
        assertThatThrownBy(logical::createStatement)
                .isInstanceOf(SQLException.class).hasMessage("Connection is closed");
        physical.closeFailure = null;
        logical.close();
        logical.close();
        assertThat(physical.closeAttempts).isEqualTo(2);
        assertThat(physical.closed).isTrue();
        assertThat(physical.statementAttempts).isEqualTo(1);
        assertThat(acquisitions.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("properties")
    void shouldRetryInitializationWithFreshConnectionAndReplayAllProperties(String property) throws SQLException {
        // Given
        PhysicalProbe failed = new PhysicalProbe();
        failed.failProperty = property;
        failed.propertyFailure = new SQLException("property failed");
        PhysicalProbe healthy = new PhysicalProbe();
        AtomicInteger acquisitions = new AtomicInteger();
        Connection logical = LazyRoutingConnection.create(() ->
                acquisitions.incrementAndGet() == 1 ? failed.connection() : healthy.connection());
        logical.setReadOnly(true);
        logical.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        logical.setAutoCommit(false);

        // When
        assertThatThrownBy(logical::createStatement).isSameAs(failed.propertyFailure);
        logical.createStatement();
        logical.createStatement();

        // Then: failed candidate was never published; deferred state survives the failure.
        assertThat(failed.closeAttempts).isEqualTo(1);
        assertThat(failed.closed).isTrue();
        assertThat(failed.statementAttempts).isZero();
        assertThat(healthy.properties).containsExactly(
                "setReadOnly=true",
                "setTransactionIsolation=" + Connection.TRANSACTION_SERIALIZABLE,
                "setAutoCommit=false"
        );
        assertThat(healthy.statementAttempts).isEqualTo(2);
        assertThat(acquisitions.get()).isEqualTo(2);
        logical.close();
        assertThat(healthy.closeAttempts).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(FailureKind.class)
    void shouldRetryAcquisitionWithoutLosingDeferredProperties(FailureKind kind) throws SQLException {
        // Given
        Throwable failure = kind.failure("acquisition failed");
        PhysicalProbe physical = new PhysicalProbe();
        AtomicInteger attempts = new AtomicInteger();
        Connection logical = LazyRoutingConnection.create(() -> {
            if (attempts.incrementAndGet() == 1) {
                if (failure instanceof SQLException sqlException) {
                    throw sqlException;
                }
                throw (RuntimeException) failure;
            }
            return physical.connection();
        });
        logical.setReadOnly(true);
        logical.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        logical.setAutoCommit(false);

        // When
        assertThatThrownBy(logical::createStatement).isSameAs(failure);
        assertThat(physical.closeAttempts).isZero();
        logical.createStatement();

        // Then
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(physical.properties).containsExactly(
                "setReadOnly=true",
                "setTransactionIsolation=" + Connection.TRANSACTION_SERIALIZABLE,
                "setAutoCommit=false"
        );
        logical.close();
        assertThat(physical.closed).isTrue();
    }

    @Test
    void shouldAllowRepeatedCloseRetriesWithoutReopeningOrReexecutingSql() throws SQLException {
        // Given
        PhysicalProbe physical = new PhysicalProbe();
        Connection logical = LazyRoutingConnection.create(physical::connection);
        logical.createStatement();
        SQLException failure = new SQLException("close still failing");
        physical.closeFailure = failure;

        // When / Then
        assertThatThrownBy(logical::close).isSameAs(failure);
        assertThatThrownBy(logical::close).isSameAs(failure);
        assertThat(physical.closeAttempts).isEqualTo(2);
        assertThat(logical.isClosed()).isTrue();
        physical.closeFailure = null;
        logical.close();
        assertThat(physical.closeAttempts).isEqualTo(3);
        assertThat(physical.statementAttempts).isEqualTo(1);
    }

    private static Stream<String> properties() {
        return Stream.of(
                "setReadOnly",
                "setTransactionIsolation",
                "setAutoCommit"
        );
    }

    @ParameterizedTest
    @MethodSource("initializationStates")
    void shouldKeepSuccessfulAbortAndSubsequentCloseIdempotent(boolean initialized) throws SQLException {
        // Given: abort is a separate terminal operation, not a failed close.
        PhysicalProbe physical = new PhysicalProbe();
        AtomicInteger acquisitions = new AtomicInteger();
        Connection logical = LazyRoutingConnection.create(() -> {
            acquisitions.incrementAndGet();
            return physical.connection();
        });
        if (initialized) {
            logical.createStatement();
        }

        // When
        logical.abort(Runnable::run);
        logical.abort(Runnable::run);
        logical.close();

        // Then
        assertThat(logical.isClosed()).isTrue();
        assertThat(physical.abortAttempts).isEqualTo(initialized ? 1 : 0);
        assertThat(physical.closeAttempts).isZero();
        assertThat(acquisitions.get()).isEqualTo(initialized ? 1 : 0);
    }

    private static Stream<Boolean> initializationStates() {
        return Stream.of(false, true);
    }

    private static Stream<FailureCase> initializationFailures() {
        return properties().flatMap(property -> Stream.of(FailureKind.values()).flatMap(initialization ->
                Stream.of(FailureKind.values()).map(cleanup -> new FailureCase(property, initialization, cleanup))));
    }

    private record FailureCase(String property, FailureKind initializationKind, FailureKind cleanupKind) {
    }

    private enum FailureKind {
        SQL, RUNTIME;

        Throwable failure(String message) {
            return this == SQL ? new SQLException(message) : new IllegalStateException(message);
        }
    }

    /**
     * JDBC 边界探针；不支持的方法直接失败，避免默认返回值掩盖意外调用。
     */
    private static final class PhysicalProbe {
        private String failProperty;
        private Throwable propertyFailure;
        private Throwable closeFailure;
        private int closeAttempts;
        private int abortAttempts;
        private int statementAttempts;
        private boolean closed;
        private final List<String> properties = new ArrayList<>();

        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                        switch (method.getName()) {
                            case "setReadOnly", "setTransactionIsolation", "setAutoCommit":
                                properties.add(method.getName() + "=" + arguments[0]);
                                if (method.getName().equals(failProperty)) {
                                    throw propertyFailure;
                                }
                                return null;
                            case "createStatement":
                                statementAttempts++;
                                return null; // Only initialization and invocation counting are in scope.
                            case "close":
                                closeAttempts++;
                                if (closeFailure != null) {
                                    throw closeFailure;
                                }
                                closed = true;
                                return null;
                            case "isClosed":
                                return closed;
                            case "abort":
                                abortAttempts++;
                                closed = true;
                                return null;
                            default:
                                throw new AssertionError("Unexpected JDBC call: " + method.getName());
                        }
                    });
        }
    }
}
