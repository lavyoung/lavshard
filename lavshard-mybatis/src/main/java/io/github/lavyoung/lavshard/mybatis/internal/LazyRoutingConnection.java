package io.github.lavyoung.lavshard.mybatis.internal;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * 延迟初始化物理 JDBC Connection 的动态代理处理器。
 *
 * <p>代理在首次需要真实数据库连接时调用物理连接工厂。首次成功
 * 初始化后永久固定该物理连接，不再读取后续路由上下文。</p>
 *
 * <p>Spring 在 Mapper 执行前设置的 autoCommit 和 readOnly 属性
 * 会先保存在逻辑连接中，并在物理连接成功创建后应用。</p>
 *
 * <p>物理连接获取或初始化失败不会写入半初始化状态，因此后续
 * JDBC 操作可以重新尝试。逻辑连接关闭后不会再次访问数据源。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/12
 */
public final class LazyRoutingConnection implements InvocationHandler {

    private final PhysicalConnectionFactory connectionFactory;

    private Connection physicalConnection;
    private boolean closed;

    private boolean autoCommit = true;
    private boolean autoCommitConfigured;

    private boolean readOnly;
    private boolean readOnlyConfigured;

    private LazyRoutingConnection(PhysicalConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory must not be null");
    }

    /**
     * 创建延迟 Connection 代理。
     *
     * @param connectionFactory 物理连接工厂
     * @return 尚未初始化物理连接的逻辑 Connection
     */
    public static Connection create(PhysicalConnectionFactory connectionFactory) {
        LazyRoutingConnection handler = new LazyRoutingConnection(connectionFactory);

        return (Connection) Proxy.newProxyInstance(LazyRoutingConnection.class.getClassLoader(), new Class<?>[]{Connection.class}, handler);
    }

    /**
     * 分发 Connection 调用。
     *
     * @param proxy     逻辑 Connection 代理
     * @param method    被调用的方法
     * @param arguments 方法参数
     * @return JDBC 方法结果
     * @throws Throwable JDBC 调用失败或反射调用失败时抛出
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
        return switch (method.getName()) {
            case "close" -> {
                closeConnection();
                yield null;
            }
            case "isClosed" -> isClosedConnection();
            case "getAutoCommit" -> getAutoCommit();
            case "setAutoCommit" -> {
                setAutoCommit((Boolean) arguments[0]);
                yield null;
            }
            case "isReadOnly" -> isReadOnly();
            case "setReadOnly" -> {
                setReadOnly((Boolean) arguments[0]);
                yield null;
            }
            case "commit" -> invokeIfInitialized(method, arguments);
            case "rollback" -> {
                if (arguments == null || arguments.length == 0) {
                    yield invokeIfInitialized(method, arguments);
                }

                yield invokePhysical(method, arguments);
            }
            case "unwrap" -> unwrap(proxy, arguments);
            case "isWrapperFor" -> isWrapperFor(proxy, arguments);
            case "abort" -> {
                abortConnection((Executor) arguments[0]);
                yield null;
            }
            case "toString" -> description();
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == arguments[0];
            default -> invokePhysical(method, arguments);
        };
    }

    /**
     * 执行必须使用物理连接的 JDBC 方法。
     *
     * @param method    JDBC 方法
     * @param arguments JDBC 参数
     * @return 物理连接的调用结果
     * @throws Throwable 底层 JDBC 方法抛出的原始异常
     */
    private Object invokePhysical(Method method, Object[] arguments) throws Throwable {
        ensureOpen();

        return invokeConnection(physicalConnection(), method, arguments);
    }

    /**
     * 只在物理连接已经存在时执行 JDBC 方法。
     *
     * <p>用于空事务的 commit 和无参数 rollback。没有执行任何 SQL
     * 的事务不应为了完成事务而创建物理连接。</p>
     *
     * @param method    JDBC 方法
     * @param arguments JDBC 参数
     * @return 底层方法结果；未初始化时返回 null
     * @throws Throwable 底层 JDBC 方法抛出的原始异常
     */
    private Object invokeIfInitialized(Method method, Object[] arguments) throws Throwable {
        Connection connection;

        synchronized (this) {
            ensureOpen();
            connection = physicalConnection;
        }

        if (connection == null) {
            return null;
        }

        return invokeConnection(connection, method, arguments);
    }

    /**
     * 反射调用物理连接并解包底层异常。
     *
     * @param connection 已初始化的物理连接
     * @param method     JDBC 方法
     * @param arguments  JDBC 参数
     * @return JDBC 方法结果
     * @throws Throwable JDBC 驱动抛出的原始异常
     */
    private static Object invokeConnection(Connection connection, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(connection, arguments);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        } catch (IllegalAccessException exception) {
            throw new SQLException("Cannot invoke Connection method: " + method.getName(), exception);
        }
    }

    /**
     * 返回或初始化固定的物理连接。
     *
     * <p>只有连接获取和延迟属性应用全部成功后才保存物理连接。
     * 任一步骤失败都会关闭临时连接并保持未初始化状态。</p>
     *
     * @return 固定的物理连接
     * @throws SQLException 连接已关闭、数据源连接失败或属性应用失败时抛出
     */
    private synchronized Connection physicalConnection() throws SQLException {
        ensureOpen();

        if (physicalConnection != null) {
            return physicalConnection;
        }

        Connection acquired = connectionFactory.getConnection();

        if (acquired == null) {
            throw new SQLException("Physical DataSource returned " + "a null Connection");
        }

        try {
            applyDeferredProperties(acquired);
        } catch (SQLException | RuntimeException exception) {
            closeAfterInitializationFailure(acquired, exception);
            throw exception;
        }

        physicalConnection = acquired;
        return physicalConnection;
    }

    /**
     * 将 Spring 在事务开始阶段设置的逻辑属性应用到物理连接。
     *
     * @param acquired 新获取的物理连接
     * @throws SQLException JDBC 驱动拒绝属性设置时抛出
     */
    private void applyDeferredProperties(Connection acquired) throws SQLException {
        if (readOnlyConfigured) {
            acquired.setReadOnly(readOnly);
        }

        if (autoCommitConfigured) {
            acquired.setAutoCommit(autoCommit);
        }
    }

    private static void closeAfterInitializationFailure(Connection acquired, Throwable originalFailure) {
        try {
            acquired.close();
        } catch (SQLException closeFailure) {
            originalFailure.addSuppressed(closeFailure);
        }
    }

    /**
     * 获取逻辑或物理连接的 autoCommit 状态。
     *
     * @return 当前 autoCommit 状态
     * @throws SQLException 连接关闭或驱动查询失败时抛出
     */
    private synchronized boolean getAutoCommit() throws SQLException {
        ensureOpen();

        if (physicalConnection != null) {
            return physicalConnection.getAutoCommit();
        }

        return autoCommit;
    }

    /**
     * 设置 autoCommit，物理连接未创建时只记录逻辑状态。
     *
     * @param requestedAutoCommit 新的 autoCommit 状态
     * @throws SQLException 连接关闭或驱动设置失败时抛出
     */
    private synchronized void setAutoCommit(boolean requestedAutoCommit) throws SQLException {
        ensureOpen();

        if (physicalConnection != null) {
            physicalConnection.setAutoCommit(requestedAutoCommit);
        }

        autoCommit = requestedAutoCommit;
        autoCommitConfigured = true;
    }

    /**
     * 获取逻辑或物理连接的只读状态。
     *
     * @return 当前只读状态
     * @throws SQLException 连接关闭或驱动查询失败时抛出
     */
    private synchronized boolean isReadOnly() throws SQLException {
        ensureOpen();

        if (physicalConnection != null) {
            return physicalConnection.isReadOnly();
        }

        return readOnly;
    }

    /**
     * 设置只读状态，物理连接未创建时只记录逻辑状态。
     *
     * @param requestedReadOnly 新的只读状态
     * @throws SQLException 连接关闭或驱动设置失败时抛出
     */
    private synchronized void setReadOnly(boolean requestedReadOnly) throws SQLException {
        ensureOpen();

        if (physicalConnection != null) {
            physicalConnection.setReadOnly(requestedReadOnly);
        }

        readOnly = requestedReadOnly;
        readOnlyConfigured = true;
    }

    /**
     * 查询关闭状态，不因查询本身创建物理连接。
     *
     * @return 逻辑连接或物理连接是否关闭
     * @throws SQLException 物理连接状态查询失败时抛出
     */
    private synchronized boolean isClosedConnection() throws SQLException {
        if (closed) {
            return true;
        }

        return physicalConnection != null && physicalConnection.isClosed();
    }

    /**
     * 执行 JDBC unwrap 语义。
     *
     * @param proxy     逻辑连接代理
     * @param arguments unwrap 参数
     * @return 逻辑代理或驱动解包对象
     * @throws SQLException 接口为空、连接关闭或无法解包时抛出
     */
    private Object unwrap(Object proxy, Object[] arguments) throws SQLException {
        Class<?> type = wrapperType(arguments);

        if (type.isInstance(proxy)) {
            return proxy;
        }

        ensureOpen();
        return physicalConnection().unwrap(type);
    }

    /**
     * 执行 JDBC isWrapperFor 语义。
     *
     * @param proxy     逻辑连接代理
     * @param arguments isWrapperFor 参数
     * @return 是否能解包为指定接口
     * @throws SQLException 接口为空、连接关闭或物理查询失败时抛出
     */
    private boolean isWrapperFor(Object proxy, Object[] arguments) throws SQLException {
        Class<?> type = wrapperType(arguments);

        if (type.isInstance(proxy)) {
            return true;
        }

        ensureOpen();
        return physicalConnection().isWrapperFor(type);
    }

    /**
     * 终止连接。尚未初始化时只关闭逻辑连接。
     *
     * @param executor JDBC 驱动执行终止工作的执行器
     * @throws SQLException 物理连接终止失败时抛出
     */
    private synchronized void abortConnection(Executor executor) throws SQLException {
        if (closed) {
            return;
        }

        closed = true;

        if (physicalConnection != null) {
            physicalConnection.abort(executor);
        }
    }

    /**
     * 关闭逻辑连接及已经创建的物理连接。
     *
     * @throws SQLException 物理连接关闭失败时抛出
     */
    private synchronized void closeConnection() throws SQLException {
        if (closed) {
            return;
        }

        closed = true;

        if (physicalConnection != null) {
            physicalConnection.close();
        }
    }

    private synchronized void ensureOpen() throws SQLException {
        if (closed) {
            throw new SQLException("Connection is closed");
        }
    }

    private synchronized String description() {
        String state;

        if (closed) {
            state = "closed";
        } else if (physicalConnection == null) {
            state = "uninitialized";
        } else {
            state = "initialized";
        }

        return "LazyRoutingConnection[" + state + "]";
    }

    private static Class<?> wrapperType(Object[] arguments) throws SQLException {
        if (arguments == null || arguments.length != 1 || arguments[0] == null) {
            throw new SQLException("Wrapper interface must not be null");
        }

        return (Class<?>) arguments[0];
    }

    /**
     * 可抛出 SQLException 的物理连接工厂。
     */
    @FunctionalInterface
    public interface PhysicalConnectionFactory {

        /**
         * 获取当前路由选择的物理连接。
         *
         * @return 物理连接
         * @throws SQLException 数据源选择或连接获取失败时抛出
         */
        Connection getConnection() throws SQLException;
    }
}