# Spring 事务传播与路由绑定：手写实现指南

## 本轮范围与现状

测试已直接补充，生产源码保持不变。当前处于 Red 阶段，不能将本轮标记为已支持；下面的候选实现尚未写入生产文件，也尚未完成 Green
验证。

新增 10 个测试：9 个 Spring + MyBatis + H2 集成用例及 1 个事务同步回调单元用例。沿用已有 6 个事务集成用例，合计 15
个集成用例。本轮覆盖：

| 场景                  | 契约                                                 |
|-----------------------|------------------------------------------------------|
| REQUIRES_NEW 跨库提交 | 内层独立绑定 ds1，外层恢复 ds0 原连接                |
| 外层回滚              | 已提交的内层事务不随外层回滚                         |
| 内层数据库异常        | 内层写入回滚，外层恢复后可以继续提交                 |
| 同库 REQUIRES_NEW     | 两个事务独立；内层完成不解除外层跨库限制             |
| 三层事务              | 各自独立连接，按嵌套顺序恢复                         |
| 外层尚未执行 SQL      | 内层绑定不影响外层第一次路由                         |
| 普通表透传            | 内层可独立访问默认库，返回外层后仍接受外层守卫       |
| NOT_SUPPORTED         | 非事务 SQL 独立提交，恢复外层时保留原绑定            |
| NESTED                | 已绑定外层事务内的保存点回滚，不释放分片约束         |
| 同步回调              | 挂起解绑、恢复同一状态对象，保留版本并且不碰其他资源 |

H2 用于验证事务传播、连接获取和提交回滚，不用于证明 MySQL SQL 方言兼容性。NESTED 用例先执行外层
SQL，再建立保存点；本轮不宣称覆盖外层尚未选择物理连接时建立保存点的场景。

## 原因与设计

当前 SpringShardContext 为首次路由注册的 TransactionSynchronization 只实现 afterCompletion。Spring 挂起事务时会调用同步器的
suspend，恢复事务时调用 resume；默认方法不替你操作自定义资源。

因此外层 ds0 绑定在 REQUIRES_NEW 执行期间仍可见，内层合法访问 ds1 被当作外层跨库操作拒绝。问题在事务资源生命周期，不在分片算法或
MyBatis SQL 改写。

参考：[Spring TransactionSynchronization 官方契约](https://docs.spring.io/spring-framework/docs/6.1.6/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html)
。suspend 负责解绑同步器管理的资源，resume 负责重新绑定。

仅需修改一个生产文件：

`lavshard-spring-boot-starter/src/main/java/io/github/lavyoung/lavshard/starter/internal/transaction/SpringShardContext.java`

无需新增生产类型，无需调整自动配置、DataSource 或 Executor 拦截器。

## 完整业务代码

将上述文件内容手动替换为下面完整代码，包含原有数据源及版本校验。不要把它复制到测试目录。

```java
package io.github.lavyoung.lavshard.starter.internal.transaction;

import io.github.lavyoung.lavshard.core.api.exception.CrossShardTransactionException;
import io.github.lavyoung.lavshard.core.api.route.ManagedRouteDecision;
import io.github.lavyoung.lavshard.core.api.route.PassThroughDecision;
import io.github.lavyoung.lavshard.core.api.route.SqlRouteDecision;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Objects;

/**
 * Spring 本地事务的 LavShard 路由绑定与冲突守卫。
 *
 * <p>事务第一次路由时绑定物理数据源。第一次 Managed 路由还会
 * 固定规则版本和拓扑版本。后续不兼容路由在 Executor 访问数据库
 * 前抛出 {@link CrossShardTransactionException}。</p>
 *
 * <p>事务绑定存储在 Spring TransactionSynchronizationManager，
 * 并通过事务完成回调清理，不要求调用方手工清除。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public final class SpringShardContext {

    private final Object transactionResourceKey = new Object();

    /**
     * 校验并绑定当前 SQL 路由。
     *
     * <p>非事务调用不保存任何状态。事务调用第一次进入时创建绑定，
     * 后续调用必须使用相同数据源。Managed 路由还必须保持规则版本
     * 和拓扑版本一致。</p>
     *
     * @param decision 当前 SQL 路由决策
     * @throws NullPointerException           decision 为空时抛出
     * @throws IllegalStateException          已标记事务活动但 Spring 事务同步
     *                                        尚未激活时抛出
     * @throws CrossShardTransactionException 当前路由与事务绑定冲突时抛出
     */
    public void validate(SqlRouteDecision decision) {
        Objects.requireNonNull(decision, "decision must not be null");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Transaction synchronization is not active");
        }

        TransactionRoute route = transactionRoute(decision);
        Object resource = TransactionSynchronizationManager.getResource(transactionResourceKey);

        if (resource == null) {
            bindFirstRoute(route);
            return;
        }

        if (!(resource instanceof TransactionRouteState state)) {
            throw new IllegalStateException("Unexpected transaction route resource: " + resource.getClass().getName());
        }

        state.validate(route);
    }

    /**
     * 建立事务第一次路由的绑定并注册清理回调。
     *
     * @param route 第一次事务路由
     */
    private void bindFirstRoute(TransactionRoute route) {
        TransactionRouteState state = new TransactionRouteState(route);
        TransactionSynchronizationManager.bindResource(transactionResourceKey, state);

        try {
            TransactionSynchronizationManager
                    .registerSynchronization(new TransactionSynchronization() {

                        /**
                         * 挂起当前事务时移除本守卫拥有的线程绑定。
                         *
                         * @throws IllegalStateException 当前资源未绑定时抛出
                         */
                        @Override
                        public void suspend() {
                            TransactionSynchronizationManager.unbindResource(transactionResourceKey);
                        }

                        /**
                         * 恢复原事务及其原有数据源、规则和拓扑版本。
                         *
                         * @throws IllegalStateException 当前线程已有同键资源时抛出
                         */
                        @Override
                        public void resume() {
                            TransactionSynchronizationManager.bindResource(transactionResourceKey, state);
                        }

                        @Override
                        public void afterCompletion(int status) {
                            TransactionSynchronizationManager.unbindResourceIfPossible(transactionResourceKey);
                        }

                    });
        } catch (RuntimeException exception) {
            TransactionSynchronizationManager.unbindResourceIfPossible(transactionResourceKey);
            throw exception;
        }
    }

    /**
     * 将 Core 路由决策转换为事务校验所需的最小坐标。
     *
     * @param decision Core 路由决策
     * @return Managed 或 PassThrough 事务路由
     */
    private static TransactionRoute transactionRoute(SqlRouteDecision decision) {
        if (decision instanceof ManagedRouteDecision managed) {
            String dataSourceId = managed.routePlan()
                    .units()
                    .get(0)
                    .target()
                    .node()
                    .dataSourceId();
            return new ManagedTransactionRoute(
                    dataSourceId,
                    managed.routePlan().ruleVersion(),
                    managed.routePlan().topologyVersion()
            );
        }

        if (decision instanceof PassThroughDecision passThrough) {
            return new PassThroughTransactionRoute(passThrough.dataSourceId());
        }

        throw new IllegalArgumentException(
                "Unsupported route decision type: "
                        + decision.getClass().getName()
        );
    }

    private sealed interface TransactionRoute permits ManagedTransactionRoute, PassThroughTransactionRoute {
        String dataSourceId();
    }

    private record ManagedTransactionRoute(
            String dataSourceId,
            String ruleVersion,
            String topologyVersion
    ) implements TransactionRoute {

    }

    private record PassThroughTransactionRoute(
            String dataSourceId
    ) implements TransactionRoute {

    }

    private record ManagedRouteVersion(
            String ruleVersion,
            String topologyVersion
    ) {
    }

    /**
     * 当前 Spring 事务已经固定的路由状态。
     *
     * <p>PassThrough 先执行时只固定数据源；事务中第一次 Managed
     * 路由再补充规则和拓扑版本。版本列表始终为空或只包含一个元素，
     * 避免用 null 或 Optional 字段表达尚未绑定。</p>
     */
    private static final class TransactionRouteState {
        private final String dataSourceId;

        private List<ManagedRouteVersion> managedVersions;

        private TransactionRouteState(TransactionRoute initialRoute) {
            dataSourceId = initialRoute.dataSourceId();
            if (initialRoute instanceof ManagedTransactionRoute managed) {
                managedVersions = List.of(versionOf(managed));
            } else {
                managedVersions = List.of();
            }
        }

        /**
         * 校验后续路由并在必要时补充首次 Managed 版本。
         *
         * @param route 后续事务路由
         * @throws CrossShardTransactionException 数据源或版本冲突时抛出
         */
        private void validate(TransactionRoute route) {
            validateDataSource(route);
            if (!(route instanceof ManagedTransactionRoute managed)) {
                return;
            }

            ManagedRouteVersion incoming = versionOf(managed);

            if (managedVersions.isEmpty()) {
                managedVersions = List.of(incoming);
            }

            ManagedRouteVersion bound = managedVersions.get(0);

            if (!bound.equals(incoming)) {
                throw new CrossShardTransactionException(
                        "Transaction is already bound to "
                                + "ruleVersion "
                                + bound.ruleVersion()
                                + " and topologyVersion "
                                + bound.topologyVersion()
                                + " but attempted ruleVersion "
                                + incoming.ruleVersion()
                                + " and topologyVersion "
                                + incoming.topologyVersion()
                );
            }
        }

        private void validateDataSource(TransactionRoute route) {
            if (dataSourceId.equals(route.dataSourceId())) {
                return;
            }
            throw new CrossShardTransactionException(
                    "Transaction is already bound to "
                            + "dataSourceId "
                            + dataSourceId
                            + " and cannot route to "
                            + route.dataSourceId()
            );
        }

        private static ManagedRouteVersion versionOf(ManagedTransactionRoute managed) {
            return new ManagedRouteVersion(managed.ruleVersion, managed.topologyVersion);
        }
    }
}
```

## 分段解释

1. **validate**：沿用非事务无绑定、事务内先校验同步状态的行为。首次 SQL 创建绑定，后续 SQL 校验数据源和版本。没有事务时的访问不会占用外层挂起的事务状态。
2. **bindFirstRoute**：为每个物理事务创建自己的 TransactionRouteState，并由该事务自己的同步器捕获。每次调用建立的 state
   独立，不使用全局静态状态或额外线程栈。
3. **suspend**：只解绑 transactionResourceKey 对应的资源。解绑不会关闭连接、提交事务或丢弃 state；连接及 SqlSession 的挂起由其各自的
   Spring 同步机制负责。
4. **resume**：把同步器捕获的同一 state 重新绑定，保留数据源、ruleVersion 和 topologyVersion。不能重建一个只有 dataSourceId
   的对象，否则可能丢失版本约束。
5. **afterCompletion**：继续清理当前事务的资源。正常 REQUIRES_NEW 生命周期中，内层先完成清理，Spring 再恢复外层同步器及其
   state。
6. **注册失败清理**：保留原有 catch，避免注册同步器失败后遗留首次绑定。suspend/resume 使用严格解绑/绑定操作，生命周期异常直接暴露，不覆盖别的状态。
7. **TransactionRouteState**：保留透传先固定数据源、首次 Managed 补充版本的规则。NESTED 使用同一物理事务，不创建新绑定；保存点回滚后仍保留该事务的分片约束。

同一个 dataSourceId 下的 REQUIRES_NEW 也必须创建独立事务资源；只有恢复外层时才能复用外层原来的连接。REQUIRES_NEW
独立提交意味着外层回滚不会撤销内层结果，这不是跨库原子事务。

## 执行命令

在项目根目录使用 PowerShell，带点的 Maven 属性参数保留引号。

聚焦本轮两个测试类：

```powershell
mvn -fae -pl lavshard-test -am '-Dtest=SpringShardContextTest,SpringMyBatisTransactionRoutingTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

模块及依赖回归：

```powershell
mvn -pl lavshard-test -am test
```

全项目干净验证：

```powershell
mvn clean verify
```

当前生产实现下应看到挂起资源检查及跨库 REQUIRES_NEW 相关用例失败；手写实现后，应全部转绿。实际结果以重新执行测试为准。

## 提交信息

仅提交当前 Red 测试及指南：

```text
test(transaction): 补充事务传播与路由挂起恢复契约
```

手写实现完成且全项目回归通过后：

```text
fix(transaction): 完成路由绑定挂起恢复与事务传播闭环
```

不要在仍有失败时使用“完成闭环”的提交说明；不要把根目录未跟踪的 AGENTS.md 加入提交。

