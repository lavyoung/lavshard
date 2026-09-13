package io.github.lavyoung.lavshard.mybatis.internal.routing;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 尚未选定物理分片时的 JDBC 保存点生命周期契约。
 */
class LazyRoutingConnectionSavepointTest {

    @Test
    void shouldExposeSavepointCapabilityWithoutPhysicalConnection() throws SQLException {
        // Given
        PhysicalProbe physical = new PhysicalProbe();
        AtomicInteger acquisitions = new AtomicInteger();
        Connection logical = LazyRoutingConnection.create(() -> {
            acquisitions.incrementAndGet();
            return physical.connection();
        });

        // When
        DatabaseMetaData metadata = logical.getMetaData();

        // Then: Spring checks this before it asks the connection for a savepoint.
        assertThat(metadata.supportsSavepoints()).isTrue();
        assertThat(metadata.getConnection()).isSameAs(logical);
        assertThat(acquisitions).hasValue(0);
        assertThat(physical.events).isEmpty();
    }

    @Test
    void shouldInitializeOnlyForMetadataThatRequiresTheDriver() throws SQLException {
        // Given
        PhysicalProbe physical = new PhysicalProbe();
        AtomicInteger acquisitions = new AtomicInteger();
        Connection logical = LazyRoutingConnection.create(() -> {
            acquisitions.incrementAndGet();
            return physical.connection();
        });
        DatabaseMetaData metadata = logical.getMetaData();
        assertThat(acquisitions).hasValue(0);

        // When
        String productName = metadata.getDatabaseProductName();

        // Then
        assertThat(productName).isEqualTo("probe-db");
        assertThat(acquisitions).hasValue(1);
        assertThat(physical.events).containsExactly(
                "getMetaData",
                "getDatabaseProductName"
        );
    }

    @Test
    void shouldMaterializeDeferredSavepointsBeforeFirstSql() throws SQLException {
        // Given
        PhysicalProbe physical = new PhysicalProbe();
        AtomicInteger acquisitions = new AtomicInteger();
        Connection logical = LazyRoutingConnection.create(() -> {
            acquisitions.incrementAndGet();
            return physical.connection();
        });

        // When
        Savepoint unnamed = logical.setSavepoint();
        Savepoint named = logical.setSavepoint("nested-order");

        // Then: creating logical savepoints must not choose a shard.
        assertThat(acquisitions).hasValue(0);
        assertThat(unnamed.getSavepointId()).isPositive();
        assertThatThrownBy(unnamed::getSavepointName)
                .isInstanceOf(SQLException.class);
        assertThat(named.getSavepointName()).isEqualTo("nested-order");
        assertThatThrownBy(named::getSavepointId)
                .isInstanceOf(SQLException.class);

        logical.createStatement();

        assertThat(acquisitions).hasValue(1);
        assertThat(physical.events).containsExactly(
                "setSavepoint",
                "setSavepoint=nested-order",
                "createStatement"
        );
    }

    @Test
    void shouldRollbackAndReleaseMaterializedSavepoint() throws SQLException {
        // Given
        PhysicalProbe physical = new PhysicalProbe();
        Connection logical = LazyRoutingConnection.create(physical::connection);
        Savepoint savepoint = logical.setSavepoint("nested-order");
        logical.createStatement();

        // When
        logical.rollback(savepoint);
        logical.releaseSavepoint(savepoint);

        // Then
        assertThat(physical.events).containsExactly(
                "setSavepoint=nested-order",
                "createStatement",
                "rollback=nested-order",
                "releaseSavepoint=nested-order"
        );
    }

    @Test
    void shouldCompleteEmptyNestedScopesWithoutPhysicalConnection() throws SQLException {
        // Given
        AtomicInteger acquisitions = new AtomicInteger();
        Connection logical = LazyRoutingConnection.create(() -> {
            acquisitions.incrementAndGet();
            return new PhysicalProbe().connection();
        });

        // When
        Savepoint rolledBack = logical.setSavepoint("rolled-back");
        logical.rollback(rolledBack);
        logical.releaseSavepoint(rolledBack);
        Savepoint released = logical.setSavepoint("released");
        logical.releaseSavepoint(released);

        // Then
        assertThat(acquisitions).hasValue(0);
    }

    @Test
    void shouldMaterializeOnlyActiveDeferredSavepoints() throws SQLException {
        // Given
        PhysicalProbe physical = new PhysicalProbe();
        Connection logical = LazyRoutingConnection.create(physical::connection);
        Savepoint released = logical.setSavepoint("released");
        Savepoint active = logical.setSavepoint("active");

        // When
        logical.releaseSavepoint(released);
        logical.createStatement();

        // Then
        assertThat(physical.events).containsExactly(
                "setSavepoint=active",
                "createStatement"
        );
        logical.rollback(active);
        assertThat(physical.events).endsWith("rollback=active");
    }

    @Test
    void shouldRejectForeignOrCompletedSavepointsWithoutOpeningConnection() throws SQLException {
        // Given
        AtomicInteger acquisitions = new AtomicInteger();
        Connection first = LazyRoutingConnection.create(() -> {
            acquisitions.incrementAndGet();
            return new PhysicalProbe().connection();
        });
        Connection second = LazyRoutingConnection.create(() -> {
            acquisitions.incrementAndGet();
            return new PhysicalProbe().connection();
        });
        Savepoint own = first.setSavepoint("own");
        Savepoint foreign = second.setSavepoint("foreign");
        first.releaseSavepoint(own);

        // When / Then
        assertThatThrownBy(() -> first.rollback(foreign))
                .isInstanceOf(SQLException.class)
                .hasMessage("Savepoint does not belong to this connection");
        assertThatThrownBy(() -> first.rollback(own))
                .isInstanceOf(SQLException.class)
                .hasMessage("Savepoint is no longer active");
        assertThat(acquisitions).hasValue(0);
    }

    @Test
    void shouldCloseFailedCandidateAndRetryDeferredSavepointOnFreshConnection() throws SQLException {
        // Given
        PhysicalProbe failed = new PhysicalProbe();
        failed.savepointFailure = new SQLException("cannot create savepoint");
        PhysicalProbe healthy = new PhysicalProbe();
        AtomicInteger acquisitions = new AtomicInteger();
        Connection logical = LazyRoutingConnection.create(() ->
                acquisitions.incrementAndGet() == 1
                        ? failed.connection()
                        : healthy.connection()
        );
        logical.setSavepoint("nested-order");

        // When / Then
        assertThatThrownBy(logical::createStatement)
                .isSameAs(failed.savepointFailure);
        assertThat(failed.closeAttempts).isEqualTo(1);
        assertThat(failed.statementAttempts).isZero();

        logical.createStatement();

        assertThat(acquisitions).hasValue(2);
        assertThat(healthy.events).containsExactly(
                "setSavepoint=nested-order",
                "createStatement"
        );
    }

    @Test
    void shouldRejectSavepointOperationsAfterConnectionClose() throws SQLException {
        // Given
        AtomicInteger acquisitions = new AtomicInteger();
        Connection logical = LazyRoutingConnection.create(() -> {
            acquisitions.incrementAndGet();
            return new PhysicalProbe().connection();
        });
        Savepoint savepoint = logical.setSavepoint();
        logical.close();

        // When / Then
        assertThatThrownBy(logical::setSavepoint)
                .isInstanceOf(SQLException.class)
                .hasMessage("Connection is closed");
        assertThatThrownBy(() -> logical.rollback(savepoint))
                .isInstanceOf(SQLException.class)
                .hasMessage("Connection is closed");
        assertThat(acquisitions).hasValue(0);
    }

    /**
     * 记录保存点物化、SQL 调用和候选连接清理顺序的 JDBC 探针。
     */
    private static final class PhysicalProbe {

        private final List<String> events = new ArrayList<>();
        private SQLException savepointFailure;
        private int savepointSequence;
        private int statementAttempts;
        private int closeAttempts;

        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "setSavepoint" -> createSavepoint(arguments);
                        case "rollback" -> {
                            PhysicalSavepoint savepoint =
                                    (PhysicalSavepoint) arguments[0];
                            events.add("rollback=" + savepoint.label());
                            yield null;
                        }
                        case "releaseSavepoint" -> {
                            PhysicalSavepoint savepoint =
                                    (PhysicalSavepoint) arguments[0];
                            events.add("releaseSavepoint=" + savepoint.label());
                            yield null;
                        }
                        case "createStatement" -> {
                            statementAttempts++;
                            events.add("createStatement");
                            yield null;
                        }
                        case "getMetaData" -> {
                            events.add("getMetaData");
                            yield metadata();
                        }
                        case "close" -> {
                            closeAttempts++;
                            yield null;
                        }
                        default -> throw new AssertionError(
                                "Unexpected JDBC call: " + method.getName()
                        );
                    }
            );
        }

        private DatabaseMetaData metadata() {
            return (DatabaseMetaData) Proxy.newProxyInstance(
                    DatabaseMetaData.class.getClassLoader(),
                    new Class<?>[]{DatabaseMetaData.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("getDatabaseProductName")) {
                            events.add("getDatabaseProductName");
                            return "probe-db";
                        }
                        throw new AssertionError(
                                "Unexpected metadata call: " + method.getName()
                        );
                    }
            );
        }

        private Savepoint createSavepoint(Object[] arguments)
                throws SQLException {
            if (savepointFailure != null) {
                throw savepointFailure;
            }

            if (arguments == null || arguments.length == 0) {
                events.add("setSavepoint");
                return new PhysicalSavepoint(
                        ++savepointSequence,
                        null
                );
            }

            String name = (String) arguments[0];
            events.add("setSavepoint=" + name);
            return new PhysicalSavepoint(
                    ++savepointSequence,
                    name
            );
        }
    }

    private record PhysicalSavepoint(
            int id,
            String name
    ) implements Savepoint {

        @Override
        public int getSavepointId() throws SQLException {
            if (name != null) {
                throw new SQLException("Named savepoint has no numeric id");
            }
            return id;
        }

        @Override
        public String getSavepointName() throws SQLException {
            if (name == null) {
                throw new SQLException("Unnamed savepoint has no name");
            }
            return name;
        }

        private String label() {
            return name == null ? Integer.toString(id) : name;
        }
    }
}
