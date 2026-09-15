package io.github.lavyoung.lavshard.mybatis.internal.routing;

import ch.qos.logback.classic.Level;
import ch.qos.logback.core.read.ListAppender;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardBucket;
import io.github.lavyoung.lavshard.core.api.route.*;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.api.topology.ShardNode;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 延迟连接路由数据源契约。
 *
 * <p>逻辑连接允许在 Spring 事务开启时提前取得，但真实连接只能在
 * MyBatis 已绑定路由决策后按需创建。首次成功选择后，逻辑连接必须
 * 固定到同一个物理连接。</p>
 */
class LavShardRoutingDataSourceTest {

    private final MyBatisRouteContext routeContext =
            new MyBatisRouteContext();

    @Test
    void shouldNotAccessPhysicalDataSourceWhenLogicalConnectionIsCreated()
            throws SQLException {
        TrackingDataSource ds0 = new TrackingDataSource("ds0");
        DataSource routingDataSource = routingDataSource(Map.of("ds0", ds0));

        Connection connection = routingDataSource.getConnection();

        assertThat(connection).isNotNull();
        assertThat(ds0.connectionAttempts()).isZero();
    }

    @Test
    void shouldSelectManagedDecisionDataSourceOnFirstDatabaseOperation()
            throws SQLException {
        TrackingDataSource ds0 = new TrackingDataSource("ds0");
        TrackingDataSource ds1 = new TrackingDataSource("ds1");
        Connection connection = routingDataSource(
                Map.of("ds0", ds0, "ds1", ds1)
        ).getConnection();

        try (MyBatisRouteContext.Scope ignored =
                     routeContext.open(managedDecision("ds1"))) {
            assertThat(connection.getCatalog()).isEqualTo("ds1");
        }

        assertThat(ds0.connectionAttempts()).isZero();
        assertThat(ds1.connectionAttempts()).isEqualTo(1);
    }

    @Test
    void shouldSelectPassThroughDecisionDataSourceOnFirstDatabaseOperation()
            throws SQLException {
        TrackingDataSource ds0 = new TrackingDataSource("ds0");
        TrackingDataSource dsDefault = new TrackingDataSource("ds-default");
        Connection connection = routingDataSource(
                Map.of("ds0", ds0, "ds-default", dsDefault)
        ).getConnection();

        try (MyBatisRouteContext.Scope ignored = routeContext.open(
                new PassThroughDecision("ds-default", "SELECT 1")
        )) {
            assertThat(connection.getCatalog()).isEqualTo("ds-default");
        }

        assertThat(ds0.connectionAttempts()).isZero();
        assertThat(dsDefault.connectionAttempts()).isEqualTo(1);
    }

    @Test
    void shouldLogPhysicalSelectionOnlyWhenConnectionIsMaterialized()
            throws SQLException {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(
                        LavShardRoutingDataSource.class
                );
        Level originalLevel = logger.getLevel();
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);

        try {
            TrackingDataSource ds1 = new TrackingDataSource("ds1");
            Connection connection = routingDataSource(Map.of("ds1", ds1))
                    .getConnection();

            assertThat(appender.list).isEmpty();

            try (MyBatisRouteContext.Scope ignored = routeContext.open(
                    managedDecision("ds1")
            )) {
                assertThat(connection.getCatalog()).isEqualTo("ds1");
            }

            assertThat(appender.list)
                    .singleElement()
                    .satisfies(event -> assertThat(event.getFormattedMessage())
                            .contains(
                                    "physical data source selected",
                                    "decision=MANAGED",
                                    "dataSourceId=ds1"
                            ));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }
    }

    @Test
    void shouldFailBeforeDatabaseAccessWhenNoRouteDecisionIsBound()
            throws SQLException {
        TrackingDataSource ds0 = new TrackingDataSource("ds0");
        Connection connection = routingDataSource(Map.of("ds0", ds0))
                .getConnection();

        assertThatThrownBy(connection::getCatalog)
                .isInstanceOf(SQLException.class)
                .hasMessage("No route decision is bound to the current thread");
        assertThat(ds0.connectionAttempts()).isZero();
    }

    @Test
    void shouldRejectUnknownDataSourceWithoutFallingBack()
            throws SQLException {
        TrackingDataSource ds0 = new TrackingDataSource("ds0");
        Connection connection = routingDataSource(Map.of("ds0", ds0))
                .getConnection();

        try (MyBatisRouteContext.Scope ignored = routeContext.open(
                new PassThroughDecision("ds-missing", "SELECT 1")
        )) {
            assertThatThrownBy(connection::getCatalog)
                    .isInstanceOf(SQLException.class)
                    .hasMessage("Unknown dataSourceId: ds-missing");
        }

        assertThat(ds0.connectionAttempts()).isZero();
    }

    @Test
    void shouldSnapshotDataSourceRegistryAtConstruction()
            throws SQLException {
        TrackingDataSource ds0 = new TrackingDataSource("ds0");
        TrackingDataSource lateDataSource = new TrackingDataSource("ds-late");
        Map<String, DataSource> registry = new HashMap<>();
        registry.put("ds0", ds0);
        DataSource routingDataSource = routingDataSource(registry);
        registry.put("ds-late", lateDataSource);
        Connection connection = routingDataSource.getConnection();

        try (MyBatisRouteContext.Scope ignored = routeContext.open(
                new PassThroughDecision("ds-late", "SELECT 1")
        )) {
            assertThatThrownBy(connection::getCatalog)
                    .isInstanceOf(SQLException.class)
                    .hasMessage("Unknown dataSourceId: ds-late");
        }

        assertThat(lateDataSource.connectionAttempts()).isZero();
    }

    @Test
    void shouldCloseWithoutCreatingPhysicalConnection()
            throws SQLException {
        TrackingDataSource ds0 = new TrackingDataSource("ds0");
        Connection connection = routingDataSource(Map.of("ds0", ds0))
                .getConnection();

        connection.close();
        connection.close();

        assertThat(connection.isClosed()).isTrue();
        assertThat(ds0.connectionAttempts()).isZero();
    }

    @Test
    void shouldDefineNonInitializingConnectionWrapperBehavior()
            throws SQLException {
        TrackingDataSource ds0 = new TrackingDataSource("ds0");
        Connection connection = routingDataSource(Map.of("ds0", ds0))
                .getConnection();

        assertThat(connection.isClosed()).isFalse();
        assertThat(connection.isWrapperFor(Connection.class)).isTrue();
        assertThat(connection.unwrap(Connection.class)).isSameAs(connection);
        assertThat(connection.toString()).contains("LazyRoutingConnection");
        assertThat(ds0.connectionAttempts()).isZero();
    }

    @Test
    void shouldInitializeWhenUnwrappingVendorConnection()
            throws SQLException {
        TrackingDataSource ds0 = new TrackingDataSource("ds0");
        Connection connection = routingDataSource(Map.of("ds0", ds0))
                .getConnection();

        try (MyBatisRouteContext.Scope ignored = routeContext.open(
                new PassThroughDecision("ds0", "SELECT 1")
        )) {
            assertThat(connection.isWrapperFor(VendorConnection.class)).isTrue();
            assertThat(connection.unwrap(VendorConnection.class))
                    .isSameAs(ds0.lastConnection());
        }

        assertThat(ds0.connectionAttempts()).isEqualTo(1);
    }

    @Test
    void shouldPinLogicalConnectionAfterFirstSuccessfulInitialization()
            throws SQLException {
        TrackingDataSource ds0 = new TrackingDataSource("ds0");
        TrackingDataSource ds1 = new TrackingDataSource("ds1");
        Connection connection = routingDataSource(
                Map.of("ds0", ds0, "ds1", ds1)
        ).getConnection();

        try (MyBatisRouteContext.Scope ignored = routeContext.open(
                new PassThroughDecision("ds0", "SELECT 1")
        )) {
            assertThat(connection.getCatalog()).isEqualTo("ds0");
        }
        try (MyBatisRouteContext.Scope ignored = routeContext.open(
                new PassThroughDecision("ds1", "SELECT 1")
        )) {
            assertThat(connection.getCatalog()).isEqualTo("ds0");
        }

        assertThat(ds0.connectionAttempts()).isEqualTo(1);
        assertThat(ds1.connectionAttempts()).isZero();
    }

    @Test
    void shouldCloseInitializedPhysicalConnectionExactlyOnce()
            throws SQLException {
        TrackingDataSource ds0 = new TrackingDataSource("ds0");
        Connection connection = routingDataSource(Map.of("ds0", ds0))
                .getConnection();
        try (MyBatisRouteContext.Scope ignored = routeContext.open(
                new PassThroughDecision("ds0", "SELECT 1")
        )) {
            connection.getCatalog();
        }

        connection.close();
        connection.close();

        assertThat(connection.isClosed()).isTrue();
        assertThat(ds0.physicalCloseCount()).isEqualTo(1);
    }

    @Test
    void shouldRejectOperationsAfterLogicalConnectionIsClosed()
            throws SQLException {
        TrackingDataSource ds0 = new TrackingDataSource("ds0");
        Connection connection = routingDataSource(Map.of("ds0", ds0))
                .getConnection();
        connection.close();

        assertThatThrownBy(connection::getAutoCommit)
                .isInstanceOf(SQLException.class)
                .hasMessage("Connection is closed");
        assertThat(ds0.connectionAttempts()).isZero();
    }

    @Test
    void shouldRetryCleanlyAfterPhysicalConnectionAcquisitionFails()
            throws SQLException {
        TrackingDataSource ds0 = new TrackingDataSource("ds0");
        ds0.failNextConnectionAttempt();
        Connection connection = routingDataSource(Map.of("ds0", ds0))
                .getConnection();

        try (MyBatisRouteContext.Scope ignored = routeContext.open(
                new PassThroughDecision("ds0", "SELECT 1")
        )) {
            assertThatThrownBy(connection::getCatalog)
                    .isInstanceOf(SQLException.class)
                    .hasMessage("Cannot connect to ds0");
            assertThat(connection.getCatalog()).isEqualTo("ds0");
        }

        assertThat(ds0.connectionAttempts()).isEqualTo(2);
    }

    @Test
    void shouldSupportConnectionObtainedBeforeSpringTransactionRoutesSql()
            throws SQLException {
        TrackingDataSource ds1 = new TrackingDataSource("ds1");
        Connection transactionConnection = routingDataSource(Map.of("ds1", ds1))
                .getConnection();
        assertThat(ds1.connectionAttempts()).isZero();

        try (MyBatisRouteContext.Scope ignored =
                     routeContext.open(managedDecision("ds1"))) {
            assertThat(transactionConnection.getCatalog()).isEqualTo("ds1");
        }

        assertThat(ds1.connectionAttempts()).isEqualTo(1);
    }

    @Test
    void shouldDeferSpringTransactionPropertiesUntilRouteIsAvailable()
            throws SQLException {
        TrackingDataSource ds0 = new TrackingDataSource("ds0");
        Connection connection = routingDataSource(Map.of("ds0", ds0))
                .getConnection();

        assertThat(connection.getAutoCommit()).isTrue();
        assertThat(connection.isReadOnly()).isFalse();
        connection.setAutoCommit(false);
        connection.setReadOnly(true);

        assertThat(connection.getAutoCommit()).isFalse();
        assertThat(connection.isReadOnly()).isTrue();
        assertThat(ds0.connectionAttempts()).isZero();
    }

    @Test
    void shouldDeferTransactionIsolationUntilRouteIsAvailable()
            throws SQLException {
        // Given
        TrackingDataSource ds0 = new TrackingDataSource("ds0");
        Connection connection = routingDataSource(Map.of("ds0", ds0))
                .getConnection();

        // When: Spring reads the previous isolation level before a Mapper routes SQL.
        assertThat(connection.getTransactionIsolation())
                .isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

        // Then: logical state is visible without selecting a physical data source.
        assertThat(connection.getTransactionIsolation())
                .isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
        assertThat(ds0.connectionAttempts()).isZero();

        try (MyBatisRouteContext.Scope ignored = routeContext.open(
                new PassThroughDecision("ds0", "SELECT 1")
        )) {
            assertThat(connection.getCatalog()).isEqualTo("ds0");
        }

        assertThat(ds0.transactionIsolation())
                .isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
        assertThat(ds0.connectionAttempts()).isEqualTo(1);
    }

    @Test
    void shouldDelegateTransactionIsolationAfterPhysicalInitialization()
            throws SQLException {
        // Given
        TrackingDataSource ds0 = new TrackingDataSource("ds0");
        Connection connection = routingDataSource(Map.of("ds0", ds0))
                .getConnection();
        try (MyBatisRouteContext.Scope ignored = routeContext.open(
                new PassThroughDecision("ds0", "SELECT 1")
        )) {
            connection.getCatalog();
        }

        // When
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

        // Then
        assertThat(connection.getTransactionIsolation())
                .isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
        assertThat(ds0.transactionIsolation())
                .isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
        assertThat(ds0.connectionAttempts()).isEqualTo(1);
    }

    @Test
    void shouldKeepDeferredTransactionIsolationPerLogicalConnection()
            throws SQLException {
        // Given
        TrackingDataSource ds0 = new TrackingDataSource("ds0");
        DataSource routing = routingDataSource(Map.of("ds0", ds0));
        Connection serializable = routing.getConnection();
        Connection defaultIsolation = routing.getConnection();

        // When
        serializable.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

        // Then
        assertThat(serializable.getTransactionIsolation())
                .isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
        assertThat(defaultIsolation.getTransactionIsolation())
                .isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
        assertThat(ds0.connectionAttempts()).isZero();
    }

    @Test
    void shouldUseExplicitDefaultTransactionIsolationWithoutPhysicalConnection()
            throws SQLException {
        // Given
        TrackingDataSource ds0 = new TrackingDataSource("ds0");
        DataSource routing = new LavShardRoutingDataSource(
                Map.of("ds0", ds0),
                routeContext,
                Connection.TRANSACTION_READ_COMMITTED
        );

        // When
        Connection connection = routing.getConnection();

        // Then
        assertThat(connection.getTransactionIsolation())
                .isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        assertThat(ds0.connectionAttempts()).isZero();
    }

    @Test
    void shouldApplyExplicitDefaultToCredentialConnection()
            throws SQLException {
        // Given
        TrackingDataSource ds0 = new TrackingDataSource("ds0");
        DataSource routing = new LavShardRoutingDataSource(
                Map.of("ds0", ds0),
                routeContext,
                Connection.TRANSACTION_SERIALIZABLE
        );

        // When
        Connection connection = routing.getConnection("app", "secret");

        // Then
        assertThat(connection.getTransactionIsolation())
                .isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
        assertThat(ds0.connectionAttempts()).isZero();
    }

    @Test
    void shouldRejectInvalidDefaultTransactionIsolationAtConstruction() {
        // Given
        TrackingDataSource ds0 = new TrackingDataSource("ds0");

        // When / Then
        assertThatThrownBy(() -> new LavShardRoutingDataSource(
                Map.of("ds0", ds0),
                routeContext,
                999
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported transaction isolation level: 999");
        assertThat(ds0.connectionAttempts()).isZero();
    }

    @Test
    void shouldNotInitializePhysicalConnectionForEmptyTransactionCompletion()
            throws SQLException {
        TrackingDataSource ds0 = new TrackingDataSource("ds0");
        Connection connection = routingDataSource(Map.of("ds0", ds0))
                .getConnection();
        connection.setAutoCommit(false);

        connection.commit();
        connection.rollback();

        assertThat(ds0.connectionAttempts()).isZero();
    }

    @Test
    void shouldIsolateRouteAndPhysicalConnectionBetweenThreads()
            throws Exception {
        TrackingDataSource ds0 = new TrackingDataSource("ds0");
        TrackingDataSource ds1 = new TrackingDataSource("ds1");
        DataSource routingDataSource = routingDataSource(
                Map.of("ds0", ds0, "ds1", ds1)
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<String> first = executor.submit(() -> routeCatalog(
                    routingDataSource,
                    "ds0"
            ));
            Future<String> second = executor.submit(() -> routeCatalog(
                    routingDataSource,
                    "ds1"
            ));

            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("ds0");
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("ds1");
        } finally {
            executor.shutdownNow();
        }

        assertThat(ds0.connectionAttempts()).isEqualTo(1);
        assertThat(ds1.connectionAttempts()).isEqualTo(1);
    }

    private DataSource routingDataSource(
            Map<String, DataSource> dataSources
    ) {
        return new LavShardRoutingDataSource(
                dataSources,
                routeContext
        );
    }

    private String routeCatalog(
            DataSource routingDataSource,
            String dataSourceId
    ) throws SQLException {
        try (Connection connection = routingDataSource.getConnection();
             MyBatisRouteContext.Scope ignored = routeContext.open(
                     new PassThroughDecision(dataSourceId, "SELECT 1")
             )) {
            return connection.getCatalog();
        }
    }

    private static ManagedRouteDecision managedDecision(
            String dataSourceId
    ) {
        ShardNode node = new ShardNode(
                "node-" + dataSourceId,
                dataSourceId,
                new QualifiedTableName("t_order_00")
        );
        RouteUnit unit = new RouteUnit(
                new ShardTarget(new ShardBucket(0), node),
                new SqlRewriteResult(
                        "SELECT * FROM t_order_00 WHERE user_id = ?",
                        List.of(0)
                )
        );
        return new ManagedRouteDecision(new RoutePlan(
                RouteMode.SINGLE,
                "rule-v1",
                "topology-v1",
                List.of(unit)
        ));
    }

    private interface VendorConnection extends Connection {
    }

    /**
     * 记录连接获取与关闭次数的物理数据源替身。
     *
     * <p>返回的连接同时实现 VendorConnection，用于验证 JDBC Wrapper
     * 在需要驱动对象时才触发延迟初始化。</p>
     */
    private static final class TrackingDataSource implements DataSource {

        private final String id;
        private final AtomicInteger connectionAttempts = new AtomicInteger();
        private final AtomicInteger physicalCloseCount = new AtomicInteger();
        private final AtomicInteger transactionIsolation =
                new AtomicInteger(Connection.TRANSACTION_READ_COMMITTED);
        private final AtomicBoolean failNext = new AtomicBoolean();
        private volatile Connection lastConnection;

        private TrackingDataSource(String id) {
            this.id = id;
        }

        @Override
        public Connection getConnection() throws SQLException {
            connectionAttempts.incrementAndGet();
            if (failNext.compareAndSet(true, false)) {
                throw new SQLException("Cannot connect to " + id);
            }
            lastConnection = physicalConnection();
            return lastConnection;
        }

        @Override
        public Connection getConnection(
                String username,
                String password
        ) throws SQLException {
            return getConnection();
        }

        private Connection physicalConnection() {
            AtomicBoolean closed = new AtomicBoolean();
            InvocationHandler handler = (proxy, method, arguments) ->
                    invokePhysicalConnection(
                            proxy,
                            method,
                            arguments,
                            closed
                    );
            return (Connection) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{VendorConnection.class},
                    handler
            );
        }

        private Object invokePhysicalConnection(
                Object proxy,
                Method method,
                Object[] arguments,
                AtomicBoolean closed
        ) throws SQLException {
            return switch (method.getName()) {
                case "close" -> closePhysical(closed);
                case "isClosed" -> closed.get();
                case "getCatalog" -> id;
                case "getAutoCommit" -> true;
                case "getTransactionIsolation" -> transactionIsolation.get();
                case "setTransactionIsolation" -> {
                    transactionIsolation.set((Integer) arguments[0]);
                    yield null;
                }
                case "unwrap" -> unwrap(proxy, arguments);
                case "isWrapperFor" -> isWrapperFor(proxy, arguments);
                case "toString" -> "PhysicalConnection[" + id + "]";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> defaultValue(method.getReturnType());
            };
        }

        private Object closePhysical(AtomicBoolean closed) {
            if (closed.compareAndSet(false, true)) {
                physicalCloseCount.incrementAndGet();
            }
            return null;
        }

        private static Object unwrap(
                Object proxy,
                Object[] arguments
        ) throws SQLException {
            Class<?> type = (Class<?>) arguments[0];
            if (type.isInstance(proxy)) {
                return proxy;
            }
            throw new SQLException("Not a wrapper for " + type.getName());
        }

        private static boolean isWrapperFor(
                Object proxy,
                Object[] arguments
        ) {
            return ((Class<?>) arguments[0]).isInstance(proxy);
        }

        private static Object defaultValue(Class<?> returnType) {
            if (!returnType.isPrimitive()) {
                return null;
            }
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == char.class) {
                return '\0';
            }
            return 0;
        }

        private void failNextConnectionAttempt() {
            failNext.set(true);
        }

        private int connectionAttempts() {
            return connectionAttempts.get();
        }

        private int physicalCloseCount() {
            return physicalCloseCount.get();
        }

        private Connection lastConnection() {
            return lastConnection;
        }

        private int transactionIsolation() {
            return transactionIsolation.get();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger()
                throws SQLFeatureNotSupportedException {
            return java.util.logging.Logger.getLogger(
                    TrackingDataSource.class.getName()
            );
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            throw new SQLException("Not a wrapper for " + iface.getName());
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }
    }
}
