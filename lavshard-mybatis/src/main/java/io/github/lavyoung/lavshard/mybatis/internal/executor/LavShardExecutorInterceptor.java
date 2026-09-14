package io.github.lavyoung.lavshard.mybatis.internal.executor;

import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import io.github.lavyoung.lavshard.core.api.route.ManagedRouteDecision;
import io.github.lavyoung.lavshard.core.api.route.PassThroughDecision;
import io.github.lavyoung.lavshard.core.api.route.RouteUnit;
import io.github.lavyoung.lavshard.core.api.route.SqlRouteDecision;
import io.github.lavyoung.lavshard.core.api.rule.RuleSnapshot;
import io.github.lavyoung.lavshard.core.internal.route.SqlRouteEngine;
import io.github.lavyoung.lavshard.mybatis.internal.routing.MyBatisRouteContext;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.BatchExecutor;
import org.apache.ibatis.executor.CachingExecutor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
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
@Intercepts(
        {
                @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
                @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
                @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class, CacheKey.class, BoundSql.class}),
                @Signature(type = Executor.class, method = "queryCursor", args = {MappedStatement.class, Object.class, RowBounds.class})
        })
public final class LavShardExecutorInterceptor implements Interceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(LavShardExecutorInterceptor.class);

    private static final String BATCH_REJECTION_MESSAGE = "MyBatis ExecutorType.BATCH is not supported in v0.1";

    private final SqlRouteEngine routeEngine;
    private final Supplier<RuleSnapshot> snapshotSupplier;
    private final MyBatisRouteContext routeContext;
    private final MyBatisMappedStatementRewriter mappedStatementRewriter;
    private final MyBatisCacheKeyAugmenter cacheKeyAugmenter;
    private final MyBatisIntegrationScope integrationScope;

    /**
     * 创建 Executor 路由拦截器。
     *
     * @param routeEngine      Core SQL 路由引擎
     * @param snapshotSupplier 当前规则快照提供器
     * @param routeContext     当前线程路由上下文
     * @throws NullPointerException 任一参数为空时抛出
     */
    public LavShardExecutorInterceptor(SqlRouteEngine routeEngine, Supplier<RuleSnapshot> snapshotSupplier, MyBatisRouteContext routeContext) {
        this(routeEngine, snapshotSupplier, routeContext, MyBatisIntegrationScope.all());
    }

    /**
     * 创建具有明确 Mapper 管理范围的 Executor 路由拦截器。
     *
     * @param routeEngine      Core SQL 路由引擎
     * @param snapshotSupplier 当前规则快照提供器
     * @param routeContext     当前线程路由上下文
     * @param integrationScope MyBatis Mapper 管理范围
     * @throws NullPointerException 任一参数为空时抛出
     */
    public LavShardExecutorInterceptor(SqlRouteEngine routeEngine, Supplier<RuleSnapshot> snapshotSupplier,
                                       MyBatisRouteContext routeContext, MyBatisIntegrationScope integrationScope) {
        this.routeEngine = Objects.requireNonNull(routeEngine, "routeEngine must not be null");
        this.snapshotSupplier = Objects.requireNonNull(snapshotSupplier, "snapshotSupplier must not be null");
        this.routeContext = Objects.requireNonNull(routeContext, "routeContext must not be null");
        this.integrationScope = Objects.requireNonNull(integrationScope, "integrationScope must not be null");
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
        MappedStatement mappedStatement = (MappedStatement) invocation.getArgs()[0];

        if (!integrationScope.includes(mappedStatement.getId())) {
            LOGGER.trace(
                    "LavShard invocation skipped: statementId={}, reason=OUT_OF_SCOPE",
                    mappedStatement.getId()
            );
            return invocation.proceed();
        }

        rejectBatchExecutor(invocation.getTarget(), mappedStatement.getId());

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
    private static void rejectBatchExecutor(Object target, String statementId) {
        if (isBatchExecutor(target)) {
            LOGGER.warn(
                    "LavShard execution rejected: statementId={}, reason=BATCH_EXECUTOR_UNSUPPORTED",
                    statementId
            );
            throw new UnsupportedSqlException(BATCH_REJECTION_MESSAGE);
        }
    }

    private static boolean isBatchExecutor(Object target) {
        Object current = target;

        while (true) {
            // 已经找到真实的批执行器，立即拒绝
            if (current instanceof BatchExecutor) {
                return true;
            }

            // 开启二级缓存时 执行器会被CachingExecutor 包装
            if (current instanceof CachingExecutor) {
                current = SystemMetaObject.forObject(current).getValue("delegate");
                continue;
            }

            // 其他 MyBatis 插件可能在执行器外面包装多层 JDK 代理。
            if (Proxy.isProxyClass(current.getClass()) && Proxy.getInvocationHandler(current) instanceof Plugin plugin) {
                current = SystemMetaObject.forObject(plugin)
                        .getValue("target");
                continue;
            }

            return false;
        }
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
        long startedAt = System.nanoTime();
        RuleSnapshot snapshot = Objects.requireNonNull(snapshotSupplier.get(), "snapshotSupplier returned null");

        MyBatisParameterValueExtractor extractor = new MyBatisParameterValueExtractor(mappedStatement.getConfiguration());

        List<Object> parameterValues = extractor.extract(boundSql);

        SqlRouteDecision decision = routeEngine.decide(snapshot, boundSql.getSql(), parameterValues);

        logRouteDecision(mappedStatement.getId(), decision, startedAt);

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
     * 记录一次不包含 SQL 参数和 SQL 文本的路由结果。
     *
     * @param statementId MyBatis MappedStatement 标识
     * @param decision    路由决策
     * @param startedAt   路由准备开始的纳秒时间
     */
    private static void logRouteDecision(
            String statementId,
            SqlRouteDecision decision,
            long startedAt
    ) {
        if (!LOGGER.isDebugEnabled()) {
            return;
        }

        long elapsedMicros = (System.nanoTime() - startedAt) / 1_000L;

        if (decision instanceof ManagedRouteDecision managed) {
            RouteUnit unit = managed.routePlan().units().get(0);
            LOGGER.debug(
                    "LavShard route decided: statementId={}, decision=MANAGED, logicalTable={}, bucket={}, dataSourceId={}, actualTable={}, ruleVersion={}, topologyVersion={}, transactionRequirement={}, elapsedMicros={}",
                    statementId,
                    managed.logicalTable(),
                    unit.target().bucket().value(),
                    unit.target().node().dataSourceId(),
                    unit.target().node().actualTable(),
                    managed.routePlan().ruleVersion(),
                    managed.routePlan().topologyVersion(),
                    managed.transactionRequirement(),
                    elapsedMicros
            );
            return;
        }

        if (decision instanceof PassThroughDecision passThrough) {
            LOGGER.debug(
                    "LavShard route decided: statementId={}, decision=PASSTHROUGH, dataSourceId={}, transactionRequirement={}, elapsedMicros={}",
                    statementId,
                    passThrough.dataSourceId(),
                    passThrough.transactionRequirement(),
                    elapsedMicros
            );
            return;
        }

        throw new IllegalArgumentException(
                "Unsupported route decision type: " + decision.getClass().getName()
        );
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
