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
 * <p>物理连接获取失败不会写入半初始化状态，因此后续 JDBC 操作
 * 可以重新尝试初始化。逻辑连接关闭后不会再次访问数据源。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public final class LazyRoutingConnection implements InvocationHandler {

    private final PhysicalConnectionFactory connectionFactory;

    private Connection physcialConnection;
    private boolean closed;

    private LazyRoutingConnection(PhysicalConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(
                connectionFactory,
                "connectionFactory must not be null"
        );
    }

    /**
     * 创建延迟 Connection 代理。
     *
     * @param connectionFactory 物理连接工厂
     * @return 尚未初始化物理连接的逻辑 Connection
     */
    public static Connection create(PhysicalConnectionFactory connectionFactory) {
        LazyRoutingConnection handler = new LazyRoutingConnection(connectionFactory);
        return (Connection) Proxy.newProxyInstance(LazyRoutingConnection.class.getClassLoader(),
                new Class[]{Connection.class},
                handler);
    }

    /**
     * 分发 Connection 调用。
     *
     * @param proxy  逻辑 Connection 代理
     * @param method 被调用的方法
     * @param args   方法参数
     * @return JDBC 方法结果
     * @throws Throwable JDBC 调用失败或反射调用失败时抛出
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        return switch (method.getName()) {
            case "close" -> {
                closeConnection();
                yield null;
            }
            case "isClosed" -> isCloseConnection();
            case "unwrap" -> unwrap(proxy, args);
            case "isWrapperFor" -> isWrapperFor(proxy, args);
            case "abort" -> {
                abortConnection((Executor) args[0]);
                yield null;
            }
            case "toString" -> description();
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> invokePhysical(method, args);
        };
    }

    /**
     * 执行普通 JDBC 方法，并解包反射异常。
     *
     * @param method JDBC 方法
     * @param args   JDBC 参数
     * @return 物理连接的调用结果
     * @throws Throwable 底层 JDBC 方法抛出的原始异常
     */
    private Object invokePhysical(Method method, Object[] args) throws Throwable {
        ensureOpen();
        try {
            return method.invoke(physicalConnection(), args);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        } catch (IllegalAccessException exception) {
            throw new SQLException(
                    "Cannot invoke Connection method: "
                            + method.getName(),
                    exception
            );
        }
    }

    /**
     * 返回或初始化固定的物理连接。
     *
     * <p>只有物理连接获取成功后才写入字段。连接池抛出异常或返回
     * 空值时，实例仍保持未初始化状态，下一次操作可以重试。</p>
     *
     * @return 固定的物理连接
     * @throws SQLException 逻辑连接已关闭或物理连接获取失败时抛出
     */
    private synchronized Connection physicalConnection() throws SQLException {
        ensureOpen();
        if (physcialConnection != null) {
            return physcialConnection;
        }

        Connection acquired = connectionFactory.getConnection();

        if (acquired == null) {
            throw new SQLException(
                    "Physical DataSource returned "
                            + "a null Connection"
            );
        }
        physcialConnection = acquired;
        return physcialConnection;
    }

    private synchronized void ensureOpen() throws SQLException {
        if (closed) {
            throw new SQLException(
                    "Connection is closed"
            );
        }
    }

    private synchronized String description() {
        String state;
        if (closed) {
            state = "closed";
        } else if (physcialConnection == null) {
            state = "uninitialized";
        } else {
            state = "initialized";
        }
        return "LazyRoutingConnection[" + state + "]";
    }

    /**
     * 终止连接。尚未初始化时只关闭逻辑连接。
     *
     * @param executor JDBC 驱动执行终止工作的执行器
     * @throws SQLException 物理连接终止失败时抛出
     */
    private void abortConnection(Executor executor) throws SQLException {
        if (closed) {
            return;
        }
        closed = true;

        if (physcialConnection != null) {
            physcialConnection.abort(executor);
        }
    }

    /**
     * 执行 JDBC isWrapperFor 语义。
     *
     * <p>查询 Connection 接口本身不会初始化物理连接；查询驱动
     * 专有接口会初始化连接并委托给真实驱动。</p>
     *
     * @param proxy 逻辑连接代理
     * @param args  isWrapperFor 参数
     * @return 是否能够解包为指定接口
     * @throws SQLException 接口为空、连接关闭或物理查询失败时抛出
     */
    private boolean isWrapperFor(Object proxy, Object[] args) throws SQLException {
        Class<?> type = wrapperType(args);

        if (type.isInstance(proxy)) {
            return true;
        }

        ensureOpen();

        return physicalConnection().isWrapperFor(type);
    }

    /**
     * 执行 JDBC unwrap 语义。
     *
     * <p>Connection 接口本身直接返回逻辑代理，不初始化物理连接；
     * 驱动专有接口需要访问物理连接并委托给驱动。</p>
     *
     * @param proxy 逻辑连接代理
     * @param args  unwrap 参数
     * @return 逻辑代理或驱动解包对象
     * @throws SQLException 接口为空、连接关闭或无法解包时抛出
     */
    private Object unwrap(Object proxy, Object[] args) throws SQLException {
        Class<?> type = wrapperType(args);
        if (type.isInstance(proxy)) {
            return proxy;
        }

        ensureOpen();
        return physicalConnection().unwrap(type);
    }

    /**
     * 查询关闭状态，不因查询本身创建物理连接。
     *
     * @return 逻辑连接或物理连接是否关闭
     * @throws SQLException 物理连接状态查询失败时抛出
     */
    private synchronized boolean isCloseConnection() throws SQLException {
        if (closed) {
            return true;
        }
        return physcialConnection != null && physcialConnection.isClosed();
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
        if (physcialConnection != null) {
            physcialConnection.close();
        }
    }

    private static Class<?> wrapperType(Object[] args) throws SQLException {
        if (args == null || args.length != 1 || args[0] == null) {
            throw new SQLException(
                    "Wrapper interface must not be null"
            );
        }
        return (Class<?>) args[0];
    }


    public interface PhysicalConnectionFactory {

        /**
         * 获取当前路由选择的物理连接
         *
         * @return 物理连接
         * @throws SQLException 数据源选择或获取连接时可能抛出
         */
        Connection getConnection() throws SQLException;
    }
}
