package io.github.lavyoung.lavshard.mybatis.internal.routing;

import io.github.lavyoung.lavshard.core.api.route.ManagedRouteDecision;
import io.github.lavyoung.lavshard.core.api.route.PassThroughDecision;
import io.github.lavyoung.lavshard.core.api.route.SqlRouteDecision;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * 基于当前 MyBatis 路由决策选择物理数据源的延迟路由数据源。
 *
 * <p>调用 {@link #getConnection()} 时只创建逻辑 Connection 代理，
 * 不访问任何物理数据源。代理第一次执行需要真实连接的 JDBC 操作时，
 * 才从 {@link MyBatisRouteContext} 读取当前路由决策并选择数据源。</p>
 *
 * <p>数据源注册表在构造时形成不可变快照。未知数据源标识必须明确
 * 失败，不允许回退到任意默认数据源。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public class LavShardRoutingDataSource implements DataSource {

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(LavShardRoutingDataSource.class);
    private static final Logger PARENT_LOGGER = Logger.getLogger(LavShardRoutingDataSource.class.getName());

    private final Map<String, DataSource> dataSources;

    private final MyBatisRouteContext routeContext;
    private final int defaultTransactionIsolation;

    private volatile PrintWriter logWriter;
    private volatile int loginTimeout;

    /**
     * 使用 MySQL 默认事务隔离级别创建路由数据源。
     *
     * @param dataSources  物理数据源注册表
     * @param routeContext MyBatis 路由上下文
     */
    public LavShardRoutingDataSource(Map<String, DataSource> dataSources, MyBatisRouteContext routeContext) {
        this(dataSources, routeContext, Connection.TRANSACTION_REPEATABLE_READ);
    }

    /**
     * 使用显式默认事务隔离级别创建路由数据源。
     *
     * <p>该值必须与物理连接池实际配置的默认隔离级别一致。Spring 在
     * 物理分片尚未确定时使用它保存事务开始前的隔离级别。</p>
     *
     * @param dataSources                 物理数据源注册表
     * @param routeContext                MyBatis 路由上下文
     * @param defaultTransactionIsolation 物理连接池默认隔离级别
     * @throws IllegalArgumentException 注册表为空、内容非法或隔离级别非法时抛出
     */
    public LavShardRoutingDataSource(Map<String, DataSource> dataSources, MyBatisRouteContext routeContext, int defaultTransactionIsolation) {
        Objects.requireNonNull(dataSources, "dataSources must not be null");
        this.routeContext = Objects.requireNonNull(routeContext, "routeContext must not be null");
        this.defaultTransactionIsolation = LazyRoutingConnection.validateDefaultTransactionIsolation(defaultTransactionIsolation);

        if (dataSources.isEmpty()) {
            throw new IllegalArgumentException("dataSources must not be empty");
        }

        dataSources.forEach(LavShardRoutingDataSource::validateDataSourceEntry);
        this.dataSources = Map.copyOf(dataSources);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return LazyRoutingConnection.create(this::obtainPyhsicalConnection, defaultTransactionIsolation);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return LazyRoutingConnection.create(() -> obtainPyhsicalConnection(username, password), defaultTransactionIsolation);
    }

    /**
     * 根据当前路由决策获取无显式凭据的物理连接。
     *
     * @return 已选择数据源返回的物理连接
     * @throws SQLException 未绑定决策、数据源未知或连接获取失败时抛出
     */
    private Connection obtainPyhsicalConnection() throws SQLException {
        return resolveDataSource().getConnection();
    }

    /**
     * 根据当前路由决策获取带显式凭据的物理连接。
     *
     * @param username JDBC 用户名
     * @param password JDBC 密码
     * @return 已选择数据源返回的物理连接
     * @throws SQLException 未绑定决策、数据源未知或连接获取失败时抛出
     */
    private Connection obtainPyhsicalConnection(String username, String password) throws SQLException {
        return resolveDataSource().getConnection(username, password);
    }

    private DataSource resolveDataSource() throws SQLException {
        SqlRouteDecision decision = routeContext.currentDecision().orElseThrow(() -> new SQLException("No route decision is bound to the current thread"));

        String dataSourceId = resolveDataSourceId(decision);
        DataSource dataSource = dataSources.get(dataSourceId);

        if (dataSource == null) {
            LOGGER.warn(
                    "LavShard physical data source selection rejected: decision={}, dataSourceId={}, reason=UNKNOWN_DATA_SOURCE",
                    decisionType(decision),
                    dataSourceId
            );
            throw new SQLException("Unknown dataSourceId: " + dataSourceId);
        }

        LOGGER.debug(
                "LavShard physical data source selected: decision={}, dataSourceId={}",
                decisionType(decision),
                dataSourceId
        );

        return dataSource;
    }

    private static String decisionType(SqlRouteDecision decision) {
        return decision instanceof ManagedRouteDecision ? "MANAGED" : "PASSTHROUGH";
    }

    private static String resolveDataSourceId(SqlRouteDecision decision) throws SQLException {
        if (decision instanceof ManagedRouteDecision managed) {
            return managed.routePlan().units().get(0).target().node().dataSourceId();
        }

        if (decision instanceof PassThroughDecision passThrough) {
            return passThrough.dataSourceId();
        }

        throw new SQLException("Unsupported route decision type: " + decision.getClass().getName());
    }

    private static void validateDataSourceEntry(String dataSourceId, DataSource dataSource) {
        if (dataSourceId == null || dataSourceId.isBlank()) {
            throw new IllegalArgumentException("dataSourceId must not be blank");
        }

        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource must not be null: " + dataSourceId);
        }
    }


    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return logWriter;
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        logWriter = out;
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        if (seconds < 0) {
            throw new SQLException("login timeout must not be negative");
        }
        loginTimeout = seconds;
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return loginTimeout;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return PARENT_LOGGER;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        Objects.requireNonNull(iface, "iface must not be null");

        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        Objects.requireNonNull(iface, "iface must not be null");
        return iface.isInstance(this);
    }
}
