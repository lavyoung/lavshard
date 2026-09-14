# ADR-005：跨数据库事务采用 LOCAL_STRICT

## 状态

已接受，适用于 v0.1 及尚未引入外部事务协调器适配的后续版本。

## 背景

LavShard 允许不同逻辑表使用不同分片键，也允许不同请求分别访问不同物理数据库。问题出现在一个 Spring 本地事务内连续执行多条
SQL，而路由结果包含多个 `dataSourceId` 时。

`DataSourceTransactionManager` 管理一个 JDBC 数据源上的本地事务。单个物理 `Connection`
不能原子提交另一个数据库的事务。若中间件只是为每个数据库打开本地连接并依次提交，在第二个数据库提交失败、网络中断或进程崩溃时，可能产生无法自动回滚的部分提交。

LavShard 的默认原则是无法保证正确性时在访问错误数据库前失败，不能把 best-effort 行为描述成本地 ACID 事务。

## 已考虑方案

### 方案一：LOCAL_STRICT

事务第一次路由时固定 `dataSourceId`。后续 SQL 命中相同数据源时继续执行；命中不同数据源时，在获取第二个物理连接前抛出
`CrossShardTransactionException`。

优点：语义清楚、故障边界可验证、不会静默部分提交，复用 Spring 本地事务。缺点：需要业务显式设计跨库一致性。

### 方案二：LOCAL_BEST_EFFORT

为多个数据库分别开启本地事务，提交或回滚时依次操作所有连接。

优点：正常路径看起来可以跨库。缺点：提交阶段故障可能部分成功，既不保证强一致，也不保证最终一致；应用容易把它误认为普通
`@Transactional` 的 ACID 语义。

### 方案三：内置 XA、AT、TCC 或 Saga

在 LavShard 内部实现两阶段提交、回滚日志、资源预留、补偿编排和故障恢复。

优点：表面接入统一。缺点：显著扩大产品边界，需要持久化事务日志、恢复任务、管理服务、幂等协议和长期运维能力，会使轻量路由组件演变为事务协调平台。

### 方案四：适配成熟外部协调器

由 Narayana、Atomikos、Seata 等产品承担协调、日志和恢复，LavShard 通过独立可选模块提供路由资源登记和上下文衔接。

优点：职责边界清晰，可以复用成熟恢复机制。缺点：需要额外基础设施、数据源代理和严格兼容性测试；当前固定单物理连接的模型需要扩展。

## 决定

1. v0.x 只提供 `LOCAL_STRICT`，不开放配置开关绕过跨库事务守卫。
2. 不同逻辑表和不同分片键受到支持；本地事务是否合法只根据最终 `dataSourceId` 及规则、拓扑版本兼容性判断。
3. 不提供 `LOCAL_BEST_EFFORT`，避免把部分提交风险隐藏在普通 `@Transactional` 后面。
4. LavShard 不自研分布式事务协调器、事务日志或恢复控制台。
5. 后续存在真实用户需求时，可以单独评审 `lavshard-transaction-seata` 或 XA 适配模块；适配模式必须显式启用，不能改变默认严格行为。
6. 跨库最终一致由业务采用 Transactional Outbox、幂等消费、重试、死信和对账；长流程可以采用 Saga，核心资源预留可以采用 TCC。

## 影响

### 正面影响

- 普通 Spring 本地事务始终具有可解释的单数据库边界。
- 跨库错误发生在第二个数据库被访问之前，避免隐式部分提交。
- Core 继续只产生路由决策，不依赖 Spring、JTA、Seata 或连接池。
- 用户可以根据业务一致性要求选择数据共置、最终一致或外部强一致方案。

### 负面影响

- 一个 `@Transactional` 方法不能直接原子修改两个数据库。
- 不同分片键的跨库业务需要增加事件、补偿或外部协调基础设施。
- 将来接入外部协调器时，需要支持一个逻辑事务登记多个物理资源，不能直接复用当前单连接固定实现。

## 演进与回滚

`LOCAL_STRICT` 是默认且长期保留的安全模式。未来外部协调适配必须作为可选模块和显式事务模式加入，并提供真实数据库故障注入、提交恢复、挂起恢复和重复回调测试。

如果外部适配不成熟，可以删除可选模块并回到 `LOCAL_STRICT`，不需要修改 Core 路由计划或现有业务配置。禁止通过删除事务守卫回滚本决策。

## 参考资料

- [Apache ShardingSphere：LOCAL、XA、BASE 事务模式](https://shardingsphere.apache.org/document/5.3.0/en/user-manual/shardingsphere-jdbc/yaml-config/rules/transaction/)
- [Apache Seata：AT 模式](https://seata.apache.org/docs/user/mode/at/)
- [Apache Seata：TCC 模式](https://seata.apache.org/docs/v1.6/user/mode/tcc/)
- [Apache Seata：Saga 模式](https://seata.apache.org/docs/dev/mode/saga-mode/)
- [Vitess：跨分片隔离与原子性模型](https://vitess.io/docs/23.0/user-guides/configuration-advanced/shard-isolation-atomicity/)
