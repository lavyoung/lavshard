# MyBatis 多插件链 BATCH 安全边界：手写实现指南

## 本轮状态

上一轮事务传播修复已提交，聚焦测试已通过。本轮仅新增测试和本文，生产文件保持未修改，处于 Red 阶段。

测试文件：
`lavshard-mybatis/src/test/java/io/github/lavyoung/lavshard/mybatis/internal/executor/MyBatisPluginChainBatchGuardTest.java`。

本轮 44 个参数化用例构成一个执行器识别契约闭环：

| 分组             | 数量 | 验证                                                 |
|------------------|-----:|------------------------------------------------------|
| BATCH            |   32 | 四入口 × 缓存开关 × 插件内外顺序 × 一层/三层其他插件 |
| SIMPLE/REUSE     |    8 | 四入口 × 两类执行器，缓存开启且位于多层插件之后      |
| 管理范围外 BATCH |    4 | 四入口均完全绕过 LavShard，原链可到达连接获取        |

四入口为四参数 query、六参数 query、update、queryCursor。使用 Configuration.newExecutor 构造真实 MyBatis 执行器与代理链；Transaction
探针不会连接数据库。

首次执行结果：44 个用例中 28 个通过、16 个失败。失败均为 LavShard 在其他插件外层时漏掉
BATCH，进入了本应禁止进入的路由阶段。用例显式抛出路由探针异常，避免错误继续访问数据库。SIMPLE/REUSE
对照只验证可以进入路由阶段，不宣称这组测试覆盖它们的完整 SQL 执行。

## 根因和设计

现有 isBatchExecutor 只识别裸 BatchExecutor 和一层 CachingExecutor。当其他插件先注册时，LavShard 收到的目标是 JDK
代理，不再直接满足这两个 instanceof。

MyBatis
官方源码：[Plugin](https://mybatis.org/mybatis-3/xref/org/apache/ibatis/plugin/Plugin.html)、[Configuration](https://mybatis.org/mybatis-3/xref/org/apache/ibatis/session/Configuration.html)
。框架通过 Plugin 持有 target 并创建 JDK 代理，Configuration 先创建执行器及缓存包装，再应用插件链。

修复仅作类型探测：

- BatchExecutor：立即识别为 BATCH。
- CachingExecutor：继续检查 delegate。
- 由 MyBatis Plugin 持有的 JDK 代理：继续检查 Plugin.target。
- 其他对象：沿用非 BATCH 分支，不猜测自定义包装器字段。

不把探测到的底层对象用于执行 SQL，不替换 invocation.target，所有正常调用仍沿原插件链执行。否则可能绕过其他插件的审计、分页或权限逻辑。

本轮只承诺标准 MyBatis Plugin 包装，不宣称支持任意自定义 Executor 装饰器或其他 InvocationHandler。也不宣称兼容修改 SQL
的所有第三方插件。字段读取是对 MyBatis 内部实现的适配依赖，升级 MyBatis 时必须运行本组测试；读取失败直接暴露，不能捕获后静默放行。

## 手写位置

仅修改：
`lavshard-mybatis/src/main/java/io/github/lavyoung/lavshard/mybatis/internal/executor/LavShardExecutorInterceptor.java`。

局部修改为新增 Plugin 和 Proxy 两个 import、替换 isBatchExecutor 方法并更新相邻 Javadoc。无需新增业务类型，无需改配置、依赖或缓存键协议。

下面提供整个文件的完整可替换代码。候选实现未写入生产文件，尚未执行 Green 验证。

## 完整业务代码

```java
package io.github.lavyoung.lavshard.mybatis.internal.executor;

import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import io.github.lavyoung.lavshard.core.api.route.ManagedRouteDecision;
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
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

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
@Intercepts({@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}), @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}), @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class, CacheKey.class, BoundSql.class}), @Signature(type = Executor.class, method = "queryCursor", args = {MappedStatement.class, Object.class, RowBounds.class})})
public final class LavShardExecutorInterceptor implements Interceptor {

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
            return invocation.proceed();
        }

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
     * CachingExecutor 内，其他插件还会追加 JDK 代理，因此必须遍历标准包装链。</p>
     *
     * @param target 当前 MyBatis Executor
     * @throws UnsupportedSqlException 使用 BatchExecutor 时抛出
     */
    private static void rejectBatchExecutor(Object target) {
        if (isBatchExecutor(target)) {
            throw new UnsupportedSqlException(BATCH_REJECTION_MESSAGE);
        }
    }

    /**
     * 沿 MyBatis 标准包装链识别真实执行器，只读检查，不改变调用链。
     *
     * @param target 当前拦截目标
     * @return 是否为批执行器
     * @throws org.apache.ibatis.reflection.ReflectionException 标准包装字段无法读取时抛出
     */
    private static boolean isBatchExecutor(Object target) {
        Object current = target;
        while (true) {
            if (current instanceof BatchExecutor) {
                return true;
            }
            if (current instanceof CachingExecutor) {
                current = SystemMetaObject.forObject(current).getValue("delegate");
                continue;
            }
            if (Proxy.isProxyClass(current.getClass())
                    && Proxy.getInvocationHandler(current) instanceof Plugin plugin) {
                current = SystemMetaObject.forObject(plugin).getValue("target");
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
```

## 逐段原理

1. **管理范围检查保持最前**。范围外的调用属于应用自身 MyBatis 行为，不被 LavShard 的 BATCH 限制接管。四个范围外对照测试验证它们能到达原始
   Transaction。
2. **rejectBatchExecutor 保持在路由前**。不读取规则、不取参数、不创建 CacheKey、不绑定路由上下文，即拒绝受管理的 BATCH。
3. **while 遍历包装链**。每次只向内取一层；标准框架包装是有限嵌套，因此无需递归。缓存包装和 Plugin 包装分别识别。
4. **Proxy.getInvocationHandler**。先确认对象是 JDK 代理，再从其处理器识别 MyBatis Plugin。反射读取的是 Plugin 的 target
   字段，不尝试读取 JDK Proxy 的私有字段。
5. **只读探测**。既不缓存真实执行器，也不拆除包装。执行器具有 SqlSession 生命周期，静态缓存会带来引用保留或串用风险。
6. **非 BATCH 保持原行为**。SIMPLE/REUSE 可以进入既有路由管线；无须复制执行器或重新实现 MyBatis 调用流程。

## 验证命令

在项目根目录的 PowerShell 中：

```powershell
# 聚焦本轮
mvn -pl lavshard-mybatis -am '-Dtest=MyBatisPluginChainBatchGuardTest' '-Dsurefire.failIfNoSpecifiedTests=false' test

# MyBatis 模块及依赖回归
mvn -pl lavshard-mybatis -am test

# 完成手写实现后的最终验收（不排除测试）
mvn clean verify
```

当前 Red 阶段只为确认旧测试没有回归，可以排除新增类执行既有测试；该命令不是最终验收：

```powershell
mvn test '-Dtest=!MyBatisPluginChainBatchGuardTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

## 提交信息

当前仅测试和指南：

```text
test(mybatis): 覆盖多插件链下的 BATCH 安全边界
```

手写实现并全部转绿后：

```text
fix(mybatis): 修复多层插件代理绕过 BATCH 检查
```

