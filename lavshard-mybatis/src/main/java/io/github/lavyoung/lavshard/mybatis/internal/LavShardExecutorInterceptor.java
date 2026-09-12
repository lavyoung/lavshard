package io.github.lavyoung.lavshard.mybatis.internal;

import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import io.github.lavyoung.lavshard.core.api.route.ManagedRouteDecision;
import io.github.lavyoung.lavshard.core.api.route.RouteUnit;
import io.github.lavyoung.lavshard.core.api.route.SqlRouteDecision;
import io.github.lavyoung.lavshard.core.api.rule.RuleSnapshot;
import io.github.lavyoung.lavshard.core.internal.route.SqlRouteEngine;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.BatchExecutor;
import org.apache.ibatis.executor.CachingExecutor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * LavShard MyBatis Executor 路由拦截器。
 *
 * <p>在 MyBatis 创建 CacheKey 和获取数据库连接之前完成：</p>
 *
 * <ol>
 *     <li>校验 Executor 类型</li>
 *     <li>获取逻辑 BoundSql</li>
 *     <li>提取有序 JDBC 参数</li>
 *     <li>调用 Core 生成路由决策</li>
 *     <li>重建物理 BoundSql 和 MappedStatement</li>
 *     <li>创建并增强物理 CacheKey</li>
 *     <li>在路由上下文中执行真实 Executor</li>
 * </ol>
 *
 * <p>该拦截器不直接获取 DataSource 或 Connection。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
@Intercepts({@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}), @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}), @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class, CacheKey.class, BoundSql.class}), @Signature(type = Executor.class, method = "queryCursor", args = {MappedStatement.class, Object.class, RowBounds.class})})
public final class LavShardExecutorInterceptor implements Interceptor {

    private static final String BATCH_REJECTION_MESSAGE = "MyBatis ExecutorType.BATCH is not supported in v0.1";

    private final SqlRouteEngine routeEngine;
    private final Supplier<RuleSnapshot> snapshotSupplier;
    private final MyBatisRouteContext routeContext;
    private final MyBatisMappedStatementRewriter mappedStatementRewriter;
    private final MyBatisCacheKeyAugmenter cacheKeyAugmenter;

    /**
     * 创建 Executor 路由拦截器。
     *
     * @param routeEngine      Core SQL 路由引擎
     * @param snapshotSupplier 当前规则快照提供器
     * @param routeContext     当前线程路由上下文
     * @throws NullPointerException 任一参数为空时抛出
     */
    public LavShardExecutorInterceptor(SqlRouteEngine routeEngine, Supplier<RuleSnapshot> snapshotSupplier, MyBatisRouteContext routeContext) {
        this.routeEngine = Objects.requireNonNull(routeEngine, "routeEngine must not be null");
        this.snapshotSupplier = Objects.requireNonNull(snapshotSupplier, "snapshotSupplier must not be null");
        this.routeContext = Objects.requireNonNull(routeContext, "routeContext must not be null");
        this.mappedStatementRewriter = new MyBatisMappedStatementRewriter();
        this.cacheKeyAugmenter = new MyBatisCacheKeyAugmenter();
    }

    /**
     * 根据 Executor 方法和参数数量分派拦截流程。
     *
     * @param invocation MyBatis 方法调用
     * @return Executor 的原始执行结果
     * @throws Throwable 路由或数据库执行失败时抛出
     */
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        rejectBatchExecutor(invocation.getTarget());

        String methodName = invocation.getMethod().getName();
        int argumentCount = invocation.getArgs().length;

        if ("query".equals(methodName) && argumentCount == 4) {
            return interceptFourArgumentQuery(invocation);
        }

        if ("query".equals(methodName) && argumentCount == 6) {
            return interceptSixArgumentQuery(invocation);
        }

        if ("update".equals(methodName) || "queryCursor".equals(methodName)) {
            return interceptDirectExecution(invocation);
        }

        return invocation.proceed();
    }

    /**
     * 在任何路由、上下文绑定或数据库访问前拒绝 BatchExecutor。
     *
     * <p>开启 MyBatis 二级缓存后，真实 BatchExecutor 会包装在
     * CachingExecutor 内，因此必须同时识别直接和标准包装形态。</p>
     *
     * @param target 当前 MyBatis Executor
     * @throws UnsupportedSqlException 使用 BatchExecutor 时抛出
     */
    private static void rejectBatchExecutor(Object target) {
        if (isBatchExecutor(target)) {
            throw new UnsupportedSqlException(BATCH_REJECTION_MESSAGE);
        }
    }

    private static boolean isBatchExecutor(Object target) {
        if (target instanceof BatchExecutor) {
            return true;
        }

        if (!(target instanceof CachingExecutor)) {
            return false;
        }

        Object delegate = SystemMetaObject.forObject(target).getValue("delegate");

        return delegate instanceof BatchExecutor;
    }

    /**
     * 处理 MyBatis 常规四参数查询入口。
     *
     * <p>不能直接 proceed，因为 BaseExecutor 会自行创建一个
     * 无 LavShard 路由维度的 CacheKey。</p>
     *
     * @param invocation MyBatis 调用
     * @return 查询结果
     * @throws Throwable 路由或查询失败时抛出
     */
    private Object interceptFourArgumentQuery(Invocation invocation) throws Throwable {
        Object[] arguments = invocation.getArgs();

        MappedStatement mappedStatement = (MappedStatement) arguments[0];
        Object parameterObject = arguments[1];
        RowBounds rowBounds = (RowBounds) arguments[2];
        ResultHandler resultHandler = (ResultHandler) arguments[3];

        BoundSql boundSql = mappedStatement.getBoundSql(parameterObject);

        PreparedExecution prepared = prepare(mappedStatement, boundSql);

        Executor executor = (Executor) invocation.getTarget();

        CacheKey cacheKey = executor.createCacheKey(prepared.mappedStatement(), parameterObject, rowBounds, prepared.boundSql());

        cacheKeyAugmenter.augment(cacheKey, prepared.decision());

        try (MyBatisRouteContext.Scope ignored = routeContext.open(prepared.decision())) {
            return executor.query(prepared.mappedStatement(), parameterObject, rowBounds, resultHandler, cacheKey, prepared.boundSql());
        }
    }

    /**
     * 处理调用方已经提供 BoundSql 和 CacheKey 的查询。
     *
     * <p>原 CacheKey 基于逻辑 SQL 创建，不能继续使用。
     * 必须根据物理 SQL 重新创建。</p>
     *
     * @param invocation MyBatis 调用
     * @return 查询结果
     * @throws Throwable 路由或查询失败时抛出
     */
    private Object interceptSixArgumentQuery(Invocation invocation) throws Throwable {
        Object[] arguments = invocation.getArgs();

        MappedStatement mappedStatement = (MappedStatement) arguments[0];
        Object parameterObject = arguments[1];
        RowBounds rowBounds = (RowBounds) arguments[2];
        BoundSql boundSql = (BoundSql) arguments[5];

        PreparedExecution prepared = prepare(mappedStatement, boundSql);

        Executor executor = (Executor) invocation.getTarget();

        CacheKey cacheKey = executor.createCacheKey(prepared.mappedStatement(), parameterObject, rowBounds, prepared.boundSql());

        cacheKeyAugmenter.augment(cacheKey, prepared.decision());

        arguments[0] = prepared.mappedStatement();
        arguments[4] = cacheKey;
        arguments[5] = prepared.boundSql();

        return proceedInsideScope(invocation, prepared.decision());
    }

    /**
     * 处理 update 和 queryCursor。
     *
     * <p>这两个入口没有外部 CacheKey 参数，只需要替换
     * MappedStatement 并绑定执行期间的路由上下文。</p>
     *
     * @param invocation MyBatis 调用
     * @return Executor 执行结果
     * @throws Throwable 路由或执行失败时抛出
     */
    private Object interceptDirectExecution(Invocation invocation) throws Throwable {
        Object[] arguments = invocation.getArgs();

        MappedStatement mappedStatement = (MappedStatement) arguments[0];
        Object parameterObject = arguments[1];

        BoundSql boundSql = mappedStatement.getBoundSql(parameterObject);

        PreparedExecution prepared = prepare(mappedStatement, boundSql);

        arguments[0] = prepared.mappedStatement();

        return proceedInsideScope(invocation, prepared.decision());
    }

    /**
     * 完成参数提取、Core 路由和 MyBatis 对象重建。
     *
     * @param mappedStatement 原始 MappedStatement
     * @param boundSql        原始 BoundSql
     * @return 本次执行需要的物理对象和路由决策
     * @throws NullPointerException 规则快照提供器返回空值时抛出
     */
    private PreparedExecution prepare(MappedStatement mappedStatement, BoundSql boundSql) {
        RuleSnapshot snapshot = Objects.requireNonNull(snapshotSupplier.get(), "snapshotSupplier returned null");

        MyBatisParameterValueExtractor extractor = new MyBatisParameterValueExtractor(mappedStatement.getConfiguration());

        List<Object> parameterValues = extractor.extract(boundSql);

        SqlRouteDecision decision = routeEngine.decide(snapshot, boundSql.getSql(), parameterValues);

        if (!(decision instanceof ManagedRouteDecision managed)) {
            return new PreparedExecution(mappedStatement, boundSql, decision);
        }

        RouteUnit routeUnit = managed.routePlan().units().get(0);

        MyBatisBoundSqlRewriter boundSqlRewriter = new MyBatisBoundSqlRewriter(mappedStatement.getConfiguration());

        BoundSql physicalBoundSql = boundSqlRewriter.rewrite(boundSql, routeUnit.sql());

        MappedStatement physicalMappedStatement = mappedStatementRewriter.rewrite(mappedStatement, physicalBoundSql);

        return new PreparedExecution(physicalMappedStatement, physicalBoundSql, decision);
    }

    /**
     * 在当前线程路由作用域中执行原始调用。
     *
     * @param invocation MyBatis 调用
     * @param decision   本次路由决策
     * @return Executor 执行结果
     * @throws Throwable Executor 执行失败时抛出
     */
    private Object proceedInsideScope(Invocation invocation, SqlRouteDecision decision) throws Throwable {
        try (MyBatisRouteContext.Scope ignored = routeContext.open(decision)) {
            return invocation.proceed();
        }
    }

    /**
     * 单次 Executor 调用需要的完整物理执行信息。
     *
     * @param mappedStatement 物理或透传 MappedStatement
     * @param boundSql        物理或透传 BoundSql
     * @param decision        Core 路由决策
     */
    private record PreparedExecution(MappedStatement mappedStatement, BoundSql boundSql, SqlRouteDecision decision) {
    }
}