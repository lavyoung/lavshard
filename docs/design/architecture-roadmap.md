# LavShard 总体架构与演进规划

## 1. 文档目的

本文定义 LavShard 的长期定位、稳定架构边界、核心领域模型、版本路线、兼容性策略和质量门槛。

它解决两个问题：

1. 当前版本应该实现什么，避免以 README 或空骨架代替可运行能力。
2. 后续版本如何扩展，避免每增加一种路由能力就推翻核心接口。

本文是架构方向，不代表所有能力都应立即实现。每个版本仍遵循最小闭环原则。

当前项目处于架构与最小闭环实现阶段，尚未形成可发布版本。README 中只有通过自动测试和真实数据库集成测试验证的能力，才能标记为“已支持”。

---

## 2. 产品定位

### 2.1 核心定位

LavShard 是面向 Java 应用的轻量级、嵌入式分库分表路由组件：

- 首要场景：Spring Boot + MyBatis + JDBC。
- 首要数据库：MySQL。
- 首要价值：低侵入的单分片路由、SQL 表名改写和本地事务保护。
- 核心模块不依赖 Spring、MyBatis、连接池实现。
- 以严格正确性为默认策略；无法安全路由时快速失败。
- 允许分片表与普通表共用一个应用，但必须显式区分受管、透传和不支持的 SQL。

### 2.2 不成为另一个 ShardingSphere

LavShard 不以完整替代数据库中间件为目标。以下能力需要独立评估投入产出：

- 任意跨分片 JOIN。
- 通用分布式聚合、排序、分页和窗口函数。
- SQL Federation 优化器。
- XA、TCC、Saga 或自研分布式事务协调器；后续只评估与成熟外部协调器集成。
- 数据迁移、扩容编排和全量数据治理平台。

当用户需求进入这些领域时，默认建议评估 ShardingSphere-JDBC、ShardingSphere-Proxy 或专用数据平台，而不是无限扩张 LavShard 内核。

### 2.3 长期愿景

LavShard 的长期价值应集中在：

- 规则简单、性能可预测的应用内分片。
- 清晰、稳定、可测试的路由计划模型。
- 对 MyBatis 和 Spring Boot 的优秀集成体验。
- 完善的错误防护、可观测性和迁移工具。
- 可插拔算法、规则源、SQL 方言和框架适配器。

---

## 3. 架构原则

### 3.1 正确性优先

- 缺少分片键、规则冲突、算法未注册或事务跨分片时默认抛错。
- 不允许解析失败后静默访问默认数据源。
- 不使用字符串替换或正则表达式改写 SQL 表名。
- 分片算法和拓扑映射必须是确定性的。

### 3.2 决策与执行分离

内核只输出路由计划，不直接获取 JDBC `Connection`，不直接提交事务，也不持有连接池对象。

### 3.3 不可变模型

规则、SQL 分析结果、分片位置和路由计划使用不可变对象。配置在启动或刷新时完成校验和快照替换，读路径不观察半更新状态。

### 3.4 单向依赖

依赖方向固定为：

```text
adapter-spring-boot ─┐
adapter-mybatis ─────┼──> core
adapter-jdbc ────────┘
```

`core` 不反向依赖任何适配器。

### 3.5 逻辑分桶与物理拓扑分离

分片算法从第一版只计算固定逻辑桶，物理数据源和物理表由带版本的拓扑映射决定。增加物理节点不能改变分片键到逻辑桶的映射。

同一个 `ShardNode(dataSourceId, actualTable)` 模型覆盖三种分片形态：

- 仅分表：不同节点使用相同 `dataSourceId`，映射到不同物理表。
- 仅分库：不同节点使用不同 `dataSourceId`，物理表名可以相同。
- 分库 + 分表：节点的数据源 ID 与物理表名都可以不同。

三种形态共用同一套路由管线，不分别引入算法分支。仅分库存在跨数据库同名物理表时，必须通过数据源 ID 隔离事务、缓存与诊断信息。以上是 v0.1 目标能力，只有对应集成测试通过后才能标记为正式支持。

### 3.6 显式能力边界

每个 SQL 在执行前必须经过能力校验。暂不支持的语法抛出带错误码的异常，不做猜测性兼容。

### 3.7 先支持单路由，再开放多路由

核心结果从第一版使用 `List<RouteUnit>` 表达计划，避免未来破坏 API；但 v0.1 的能力策略强制列表只能包含一个路由单元。

### 3.8 框架内部对象不进入公共契约

JSQLParser AST、MyBatis `MappedStatement`、`BoundSql` 和 Spring `DataSource` 只存在于适配器或内部实现中。公共 API 只暴露 LavShard 自有类型和 JDK 类型。

---

## 4. 稳定处理管线

整体处理流程固定为：

```text
Framework Invocation
        │
        ▼
Integration Scope Filter
        │  判断当前 Mapper/SqlSessionFactory 是否由 LavShard 管理
        ▼
SQL Parse
        │  SQL 类型、逻辑表、条件、占位符
        ▼
Statement Classify
        │  MANAGED / PASSTHROUGH / UNSUPPORTED
        ▼
Parameter Bind
        │  占位符与实际参数绑定
        ▼
Capability Validate
        │  校验当前版本是否安全支持
        ▼
Rule Resolve
        │  获取规则快照
        ▼
Shard Calculate
        │  分片值 → 固定逻辑桶
        ▼
Topology Map
        │  逻辑桶 → 稳定节点 → 数据源 ID + 物理表名
        ▼
SQL Rewrite
        │  AST 改写逻辑表名
        ▼
Route Plan
        │  一个或多个不可变 RouteUnit
        ▼
Framework Execute
        │  事务守卫、数据源选择、SQL 执行
        ▼
Optional Result Merge
```

处理语义：

- `MANAGED`：SQL 命中已配置分片表，进入严格路由管线。
- `PASSTHROUGH`：SQL 属于明确的普通表或非管理范围，保持原 SQL 并访问明确的默认数据源。
- `UNSUPPORTED`：SQL 属于管理范围但无法安全路由，在获取错误物理连接前失败。
- 解析失败不能自动降级为 `PASSTHROUGH`。

解析、绑定、路由、改写是四个独立阶段，不允许混在一个 `Router` 方法中。

---

## 5. 模块规划

### 5.1 `lavshard-core`

职责：

- 领域模型和错误码。
- SQL 分析抽象。
- 参数绑定后的路由条件。
- 规则仓库抽象和不可变规则快照。
- 分片算法、拓扑映射和算法注册表。
- SQL 改写抽象。
- 路由计划生成。

约束：

- 不依赖 Spring、MyBatis、HikariCP。
- 不创建或关闭数据源。
- 不使用 ThreadLocal。
- 不执行 SQL。
- 不暴露具体 SQL 解析器的 AST。

### 5.2 `lavshard-sql-jsqlparser`

职责：

- 基于 JSQLParser 生成 SQL AST。
- 提取逻辑表、条件和 JDBC 参数位置。
- 基于 AST 改写物理表名。
- 实现 MySQL 方言能力矩阵。

拆分时机：v0.1 可以暂时位于 core；当需要支持第二种解析器或第二种方言时再独立成模块。

### 5.3 `lavshard-mybatis`

职责：

- 拦截 MyBatis `Executor.query/update`。
- 获取 `MappedStatement`、`BoundSql`、参数对象和参数映射。
- 将实际参数绑定到 SQL 条件。
- 应用改写后的 `BoundSql`。
- 执行前设置路由上下文，执行后可靠清理。
- 保证物理 SQL 在 MyBatis 创建一级、二级缓存键之前确定，并将数据源、规则与拓扑版本纳入缓存键。
- 完整保留或投影 `ParameterMapping` 与动态 SQL additional parameters。

约束：

- 不复制 MyBatis 执行器。
- 不绕过 MyBatis-Spring 的事务管理。
- v0.1 拒绝 Batch；后续开放时必须先证明同一批次只命中一个物理节点，并定义刷新、生成键与部分失败语义。
- 生成键、游标查询、缓存和不同 Executor 类型必须分别通过兼容性测试。

### 5.4 `lavshard-spring-boot-starter`

职责：

- `@ConfigurationProperties` 配置绑定和启动校验。
- 注册算法、规则仓库、路由器和 MyBatis 拦截器。
- 创建物理数据源和路由数据源。
- 集成 Spring 事务守卫。
- 在 v0.2 之后按路线图提供可选 Actuator 和 Micrometer 观测。

数据源接入提供两种模式：

1. 引用应用已注册的 `DataSource` Bean，作为企业项目的推荐模式。
2. 由 Starter 根据配置创建连接池，作为快速开始的便捷模式。

### 5.5 `lavshard-test`

职责：

- 跨模块集成测试。
- MySQL Testcontainers 方言测试。
- Spring Boot 自动配置测试。
- MyBatis 参数绑定与事务测试。
- JMH 路由热路径基准。

示例模块只用于演示，不承担核心行为验证。

---

## 6. 核心领域模型

以下模型名称可在实现时微调，但职责边界应保持稳定。

### 6.1 SQL 分析模型

```java
record SqlAnalysis(
        SqlType type,
        List<QualifiedTableName> tables,
        List<ShardPredicate> predicates
) {
}

public record QualifiedTableName(
        List<String> qualifiers,
        String table
) {
}
```

`SqlAnalysis` 是 internal 模型，只描述 SQL 结构，不包含数据源、规则、解析器 AST 或执行资源。表名不能仅用裸 `String` 表达；大小写、引用符和 Schema 归一化由具体 SQL 方言处理。

```java
record ShardPredicate(
        String column,
        ShardOperator operator,
        List<ValueReference> values
) {
}
```

`ValueReference` 表达字面量或参数位置；参数绑定阶段才产生实际分片值。

### 6.2 分片算法模型

```java
public interface ShardAlgorithm {

    String name();

    ShardBucket calculate(ShardValue value, AlgorithmConfig config);
}

public record ShardBucket(int value) {
}
```

算法只计算固定范围内的逻辑桶，不知道数据源对象、物理节点数量和表名格式。

Hash 路由必须固定以下持久化语义：

- `bucketCount`：建表时确定，v0.x 不允许在线修改。
- `hashVersion`：明确算法名称、版本、字符编码和类型规范化规则。
- `ShardValue`：只接受已声明的类型；字符串、整数、UUID 和二进制值采用稳定且有测试的规范化方式。
- 不直接依赖业务对象的 `hashCode()`。

### 6.3 拓扑模型

```java
public record ShardNode(
        String nodeId,
        String dataSourceId,
        QualifiedTableName actualTable
) {
}

public record ShardTopology(
        String version,
        int bucketCount,
        Map<Integer, String> bucketPlacements,
        Map<String, ShardNode> nodes
) {
}
```

推荐映射流程：

```text
canonicalValue = canonicalize(shardValue, hashVersion)
bucketId       = floorMod(stableHash(canonicalValue, hashVersion), bucketCount)
nodeId         = topology.bucketPlacements[bucketId]
target         = topology.nodes[nodeId]
```

内部拓扑快照必须包含完整的 `bucketId → nodeId` 映射。v0.1 配置加载器可以根据初始布局生成该映射，但路由热路径不能根据当前节点数量重新取模。节点新增、删除和桶迁移必须产生新的 `topologyVersion`。

### 6.4 路由计划

```java
public record RouteUnit(
        ShardTarget target,
        SqlRewriteResult sql,
        List<ParameterReference> parameters
) {
}

public record SqlRewriteResult(
        String sql,
        List<Integer> sourceParameterIndexes
) {
}

public record RoutePlan(
        RouteMode mode,
        String ruleVersion,
        String topologyVersion,
        List<RouteUnit> units
) {
}
```

约束：

- `RouteUnit` 不持有 `DataSource`。
- `ParameterReference` 表达原始参数的引用和投影，不包含 MyBatis 类型。
- MyBatis 适配器依据 `sourceParameterIndexes` 重建有序 `ParameterMapping`，并复制仍被引用的 additional parameters。
- 物理 SQL 必须在 MyBatis 创建 CacheKey 前确定；CacheKey 还必须追加稳定的 `dataSourceId`、`ruleVersion` 和 `topologyVersion`，防止不同数据库使用同名物理表或版本切换时串读。
- 不能在 `StatementHandler.prepare` 阶段才改变影响缓存语义的 SQL，也不能在已有 CacheKey 上只替换 SQL 文本。
- `RoutePlan.units` 不为空且不可变。
- `RoutePlan` 固定使用一次计算所读取的规则版本和拓扑版本。
- 构造时校验 `RouteMode` 与路由单元数量一致。
- v0.1 只允许 `SINGLE`。
- 后续 `MULTI` 和 `BROADCAST` 复用同一模型。

透传不伪装成空路由计划，单独表达：

```java
public record PassThroughDecision(
        String dataSourceId,
        String originalSql
) {
}
```

### 6.5 规则仓库

```java
public interface RuleRepository {

    Optional<TableRule> find(String logicalTable);

    RuleSnapshot snapshot();
}
```

一次路由必须从同一个 `RuleSnapshot` 完成规则查找、算法计算和拓扑映射。更新策略采用：完整构建 → 完整校验 → 原子替换快照。路由热路径只读当前快照。

规则不存在并不必然表示异常：非管理范围或明确普通表可以透传；已声明为分片表但规则缺失才抛出异常。

### 6.6 异常体系

建议统一：

```text
LavShardException
├── ConfigurationException
├── SqlParseException
├── UnsupportedSqlException
├── MissingShardKeyException
├── ShardAlgorithmException
├── RouteNotFoundException
├── ParameterBindingException
├── TopologyVersionException
├── CrossShardTransactionException
└── RuleRefreshException
```

异常至少携带稳定错误码、可读消息和原始 cause。日志中避免输出数据源密码和完整敏感参数。

---

## 7. 配置模型

推荐优先引用应用中已有的数据源 Bean，Starter 管理连接池作为便捷模式：

```yaml
lavshard:
  enabled: true

  integration:
    default-data-source: ds0
    ordinary-tables: [sys_dict, sys_config]

  data-sources:
    ds0:
      bean-name: orderDataSource0
    ds1:
      bean-name: orderDataSource1

  tables:
    t_order:
      rule-version: order-rule-v1
      sharding-column: user_id
      algorithm:
        name: hash_mod
        hash-version: murmur3_32_v1
      topology:
        version: order-topology-v1
        bucket-count: 1024
        nodes:
          order-00: {data-source: ds0, actual-table: t_order_00}
          order-01: {data-source: ds0, actual-table: t_order_01}
          order-02: {data-source: ds1, actual-table: t_order_00}
          order-03: {data-source: ds1, actual-table: t_order_01}
        initial-placement:
          strategy: round-robin
          ordered-node-ids: [order-00, order-01, order-02, order-03]
```

配置原则：

- `bucket-count` 和 `hash-version` 是数据定位的持久化契约。
- `rule-version` 和 `topology.version` 必须显式填写，不能用启动时间或随机值生成。
- `nodeId` 是稳定物理节点身份，不能依赖 Map 或 YAML 的自然顺序。
- `initial-placement` 只用于首次生成完整桶映射；拓扑快照保存显式的 `bucketId → nodeId` 结果。
- 已存在数据后禁止重新执行初始布局覆盖当前桶映射。
- 算法配置使用结构化对象，不使用难校验的压缩字符串。
- 启动时校验规则、数据源、桶映射、物理表和算法是否一致。
- 普通表必须进入显式允许列表；未知表在 v0.1 固定拒绝，避免规则遗漏时误访问默认库。
- 配置密钥只接受外部注入，不在示例中提供真实密码。

Starter 管理连接池时使用独立配置分支：

```yaml
lavshard:
  data-sources:
    ds0:
      managed:
        url: jdbc:mysql://localhost:3306/order_0
        username: root
        password: ${DB_0_PASSWORD}
```

同一个数据源 ID 只能选择 `bean-name` 或 `managed` 其中一种。连接池实现和连接参数只存在于 Starter，不进入 core。

Range 规则建议：

```yaml
algorithm:
  name: range
  ranges:
    - lower-inclusive: 0
      upper-exclusive: 10000
      shard-bucket: 0
    - lower-inclusive: 10000
      upper-exclusive: 20000
      shard-bucket: 1
```

启动时拒绝区间重叠、倒置、未覆盖策略不明确或逻辑桶越界。Range 边界值必须声明数据类型和规范化规则。

---

## 8. 数据库模型治理

LavShard 不只负责 SQL 路由，还必须约束能被安全分片的数据模型：

- 分片键必须非空、稳定、不可修改，并具有足够基数和可接受的数据分布。
- `UPDATE` 禁止修改分片键；需要变更时由显式数据迁移流程处理。
- v0.1 拒绝多行 INSERT；后续只有在能够先计算全部记录、确认同一物理节点并正确重建参数映射后才允许开放。
- 数据库自增键只保证单分片唯一；全局 ID 由业务或独立 ID 服务提供。
- 普通唯一索引只保证单分片唯一；需要全局唯一时由业务预分配、全局索引或包含分片键的约束实现。
- 禁止跨分片外键；需要同库 JOIN 的关联表必须使用兼容分片键和相同桶布局。
- 不包含分片键的二级查询默认拒绝，后续通过显式 Lookup 或有限多路由能力支持。
- 所有物理表的 Schema、字符集、排序规则和索引必须一致。

完整约束和选型清单见 [分片数据模型规范](./sharding-data-model-guidelines.md)。

---

## 9. 事务模型

### 9.1 v0.x 事务边界：LOCAL_STRICT

- 支持单物理数据库本地事务。
- 同一事务第一次访问时绑定数据源。
- 后续 SQL 命中相同数据源时允许执行。
- 后续 SQL 命中不同数据源时立即抛出 `CrossShardTransactionException`。
- 普通表透传同样接受事务守卫；默认数据源与当前事务绑定的数据源不一致时必须拒绝。
- 事务首次路由时同时固定 `ruleVersion`、`topologyVersion` 和 `dataSourceId`。
- 事务期间不允许切换到与已固定版本不兼容的规则或拓扑。
- 不提供伪分布式事务。
- 不提供依次提交多个本地事务的 `LOCAL_BEST_EFFORT` 模式；该模式在提交阶段故障时可能部分成功，不符合默认严格正确性原则。
- 不同逻辑表可以使用不同分片键。它们可以在独立事务中访问不同数据库，也可以在最终落到同一 `dataSourceId`
  时共享本地事务；分片键不同不等于禁止业务关联。

### 9.2 Spring 数据源链

建议使用：

```text
LazyConnectionDataSourceProxy
        └── LavShardRoutingDataSource
                ├── physical ds0
                └── physical ds1
```

MyBatis `SqlSessionFactory` 和 Spring 事务管理器必须引用同一个最外层逻辑数据源。

### 9.3 上下文生命周期

- 非事务路由使用作用域对象，在 `try/finally` 或 `AutoCloseable` 中恢复上一层上下文。
- 事务路由使用 Spring 事务资源绑定和同步回调清理。
- 不把“调用者必须手工 clear”作为公共 API 的正常使用方式。
- 异步调用不隐式继承 ThreadLocal；需要显式上下文传递。

### 9.4 跨库业务一致性选择

跨库执行能力与跨库原子提交能力必须分开描述。业务设计按照一致性要求选择：

1. 需要同库强一致的聚合数据，优先使用兼容分片键和桶布局共置；替代查询键通过全局 Lookup、查询投影或冗余字段解决。
2. 可接受最终一致的跨库流程，使用 Transactional Outbox，在源数据库的本地事务中同时写业务数据和事件；消费者必须幂等，并具备重试、死信和对账能力。
3. 长业务流程使用 Saga 时，每个参与者独立提交本地事务，并提供可重试、幂等的补偿操作；业务必须接受补偿期间的中间状态和较弱隔离性。
4. 必须跨库强一致的短事务评估 XA；库存、余额和额度等资源预留场景评估 TCC。二者由成熟事务协调器承担，不进入 LavShard 内核。

LavShard 后续若支持分布式事务，只新增可选适配模块，例如 `lavshard-transaction-seata`
，负责路由上下文与外部全局事务资源登记的衔接。事务协调、持久化日志、故障恢复和管理控制台继续由外部产品负责。适配器落地前必须解决一个逻辑事务持有多个物理连接、分支资源注册、挂起恢复、幂等和故障注入测试，不能复用
v0.x 的单连接固定模型冒充完成。

---

## 10. 扩展机制

### 10.1 初期只保留必要扩展点

v0.1 只承诺一个必要扩展接口：

- `ShardAlgorithm`

候选扩展接口只有在出现第二个真实实现后才公开：

- `RuleProvider`：有第二种规则源时引入。
- `SqlDialect`：有第二种数据库方言时引入。
- `RouteObserver`：优先使用内部事件和 Micrometer，不在 v0.x 早期公开。

暂不提供：

- 通用执行器 SPI。
- 通用结果合并 SPI。
- 任意策略链编排。
- 为每个内部类增加接口。

### 10.2 算法发现

只保留一个 `ShardAlgorithmRegistry`：

1. 注册内置算法。
2. 使用 `ServiceLoader<ShardAlgorithm>` 加载 Java SPI。
3. Starter 将 Spring 容器中的用户算法注册进去。
4. 算法名重复时默认启动失败，不以不透明优先级覆盖。

不再额外创建 `ShardAlgorithmFactory` 或空的 `ShardAlgorithmSPI` 类型。

### 10.3 兼容性约束

- SPI 接口进入公开 API 后遵循语义化版本。
- 内部 AST 和框架对象不进入公共 API。
- 对外模型只使用 LavShard 自有类型或 JDK 类型。
- 新增配置项可以向后兼容；重命名配置项需要弃用周期和迁移说明。

---

## 11. 版本路线

版本号表达能力成熟度，不以文件数量或空类数量作为完成标准。

### 11.1 v0.1：可信的单分片闭环

目标：第一条真实可运行、可验证的链路。

范围：

- Java 17。
- MySQL + MyBatis + Spring Boot。
- 每条 SQL 最多包含一个受管分片表；项目可以配置多张分片表。
- 支持 v0.1 SQL 能力矩阵内的 `SELECT`、`INSERT`、`UPDATE`、`DELETE`。
- 单个等值分片键，支持 JDBC 占位符和字面量。
- 固定逻辑桶、稳定 Hash 版本和静态物理拓扑。
- 使用统一拓扑模型覆盖仅分表、仅分库、分库 + 分表。
- 单分片 SQL 改写。
- 单物理数据库本地事务和跨数据库事务拦截。
- 受管 SQL、普通 SQL 透传和不支持 SQL 的明确分类。
- MyBatis 参数投影、additional parameters 和 CacheKey 正确性。
- 启动配置校验。

明确拒绝：

- `IN`、`BETWEEN`、无分片键。
- JOIN、子查询、`UNION`、修改分片键、多行 INSERT、MyBatis Batch Executor。
- 运行期动态规则。

精确语法和行为边界见 [v0.1 SQL 支持矩阵](v0.1.0/v0.1-sql-support-matrix.md)。

退出标准：

- 全量 Maven 构建通过。
- MySQL Testcontainers 完成四类 DML 集成测试。
- 异常路径验证不会访问错误分片。
- 同事务跨库测试稳定失败。
- MyBatis 一级、二级缓存不会跨物理表串读。
- README 中的每个“已支持”能力都有自动测试对应。

### 11.2 v0.2：规则和算法完善

目标：提升实际业务可用性，但仍保持单路由执行模型。

范围：

- Range 算法。
- 自定义算法 SPI。
- 编程式 Hint 路由。
- 规则快照、版本号和显式手动重载。
- 配置变更校验；影响数据定位的变更拒绝应用。
- SQL 解析缓存。
- 路由指标和结构化诊断日志。
- 最小 Actuator 状态端点。

约束：

- 手动重载只允许不改变已有数据定位语义的安全变更。
- 数据再分片不属于普通规则刷新。

### 11.3 v0.3：多路由读取

目标：有限、可控地支持多个分片。

范围：

- `IN` 条件拆分到多个路由单元。
- 并发度、超时、连接数和最大路由数限制。
- 仅支持无 `DISTINCT`、`ORDER BY`、`LIMIT`、`GROUP BY`、聚合和锁语义的只读结果拼接。
- 多路由执行观测。

必须先解决：

- 执行资源上限。
- 部分分片失败语义。
- 分页和排序的正确性边界。
- 写操作、聚合和有序合并继续拒绝；引入前必须单独完成架构评审。

### 11.4 v0.4：治理与生产能力

目标：可在可控生产环境中运维。

范围：

- 配置中心规则源适配器。
- 规则版本、审计、回滚和安全热更新。
- 事务规则版本固定、旧快照生命周期和兼容性判定。
- 慢路由、全路由和热点分片检测。
- SQL 解析与路由缓存命中率。
- 健康检查、数据源状态和脱敏诊断。
- 影子路由或双算校验模式，用于迁移验证。
- 故障注入与压力测试。

### 11.5 v0.5：生态适配

目标：在不污染内核的前提下扩展使用面。

候选范围：

- PostgreSQL 方言。
- 原生 JDBC 代理适配器。
- MyBatis-Plus 兼容验证。
- Spring Boot 当前受支持版本矩阵。
- GraalVM/AOT 可行性验证。

选择原则：有真实用户场景和维护能力后再加入，不因路线图存在就承诺全部实现。

### 11.6 v1.0：稳定契约

目标：冻结核心公开 API 和配置语义。

要求：

- 至少一个生产验证周期。
- 公开 API、SPI、错误码和配置完成兼容性审计。
- 升级指南、能力矩阵和故障排查文档完整。
- 路由正确性、事务安全性和资源上限有系统测试。
- 性能基准可复现，不使用无依据的宣传数字。
- 明确长期支持的 JDK、Spring Boot、MyBatis 和数据库版本。

### 11.7 v1.x 之后的候选方向

- 有限广播表。
- 读写分离和只读事务路由。
- 数据扩容辅助工具和一致性校验。
- 分片键迁移的双写/影子验证适配点。
- OpenTelemetry 路由追踪。

以下方向必须单独立项，不默认进入内核：

- 自研或内置分布式事务协调器。
- 外部 XA、Seata AT/TCC/Saga 协调器的可选适配模块可以独立立项，但不得进入 core，也不得由 LavShard 自研事务日志和恢复协议。
- 任意 SQL 的分布式执行与合并。
- 在线数据迁移平台。
- 全功能数据库代理。

---

## 12. 质量门槛

### 12.1 正确性测试

- 分片算法确定性、负 hash、边界值和所有逻辑桶可达。
- Hash 类型规范化和 `hashVersion` 固定向量测试。
- 桶到物理节点映射完整、无越界且版本可追踪。
- 仅分表、仅分库、分库 + 分表三种拓扑均验证路由、SQL 改写和同名物理表缓存隔离。
- 参数映射覆盖 POJO、Map、`@Param`、集合和动态 SQL。
- 改写后的物理 SQL、参数顺序和 additional parameters 一致；CacheKey 包含物理 SQL、`dataSourceId`、`ruleVersion` 与 `topologyVersion`。
- 表别名、引用符、Schema、子查询等 SQL AST 场景。
- 缺失分片键、重复条件、冲突条件和不支持语法。
- 普通表透传不会被错误改写，受管表解析失败不会静默透传。
- 修改分片键、跨分片批量写和跨分片唯一性风险被明确拒绝。
- 异常后上下文清理。
- 事务内同分片、跨分片和规则/拓扑版本变化行为。

### 12.2 集成测试

- H2 只用于非方言相关的轻量测试。
- MySQL 行为由 Testcontainers 验证。
- Starter 使用 `ApplicationContextRunner` 验证自动配置、禁用和用户 Bean 退让。
- 每个支持的 Spring Boot/MyBatis 组合进入兼容性矩阵。

### 12.3 并发与性能

- 路由热路径不持有全局写锁。
- 规则更新通过不可变快照原子替换。
- 多路由执行只能使用项目统一配置的执行器。
- 必须配置最大路由单元数、最大并发数和超时。
- JMH 分离测量解析、缓存、算法和完整路由，不混入数据库网络耗时。

### 12.4 发布门禁

- `mvn verify` 通过。
- 没有空的自动配置资源文件和空的 ServiceLoader 声明。
- 示例能够从全新环境启动。
- README 功能状态与测试覆盖一致。
- v0.1 SQL 支持矩阵中的每一行至少存在一个通过或拒绝用例。
- 数据库建模规范进入示例 Schema 和评审清单。
- 不发布仅含注释的占位公共类型。
- 依赖漏洞、许可证和二进制兼容检查通过。

---

## 13. 可观测性规划

指标建议：

- `lavshard.route.total`
- `lavshard.route.failure`
- `lavshard.route.duration`
- `lavshard.route.units`
- `lavshard.parse.cache.hit`
- `lavshard.rule.version`
- `lavshard.cross_shard.rejected`

标签必须低基数，只使用算法名、SQL 类型、结果类型等稳定值；不以 SQL、用户 ID、表后缀作为无限增长标签。

日志建议包含：

- traceId。
- 逻辑表。
- 算法名。
- 规则版本。
- 目标数据源名和物理表名。
- 路由耗时。

默认不记录完整 SQL 参数、密码或个人敏感数据。

---

## 14. 数据扩容与迁移边界

LavShard 从 v0.1 固定分片键到逻辑桶的映射，扩容只改变逻辑桶到物理节点的归属：

- v0.x 禁止在线修改既有规则的 `bucketCount` 和 `hashVersion`。
- 物理节点使用稳定 `nodeId`，不能以数据源列表顺序作为身份。
- 配置刷新发现拓扑语义变化时必须拒绝应用。
- 后续扩容应由独立迁移流程完成，而不是普通配置热更新。

可演进流程：

1. 创建新物理节点和新拓扑版本。
2. 复制待迁移逻辑桶的数据，不改变分片键到桶的算法。
3. 迁移期间启用旧拓扑/新拓扑双算对比和只读一致性检查。
4. 短暂停写或通过独立迁移协议追平增量数据。
5. 原子切换拓扑版本，并保留快速回滚窗口。

迁移工具应作为独立模块或外部工具，不侵入路由热路径。

---

## 15. API 与版本策略

- `0.x` 允许有说明的破坏性调整，但每次发布必须提供迁移说明。
- `1.0` 后遵循语义化版本。
- 公共 API 放入明确的 `api` 包；实现放入 `internal` 包。
- 异常错误码一旦发布不复用。
- 配置废弃至少保留一个次版本，并在启动时给出明确警告。
- 规则和算法的序列化格式需要携带 schema version。
- 不承诺 JSQLParser、MyBatis 内部类型的二进制兼容性，因为它们不应暴露到公共 API。

---

## 16. 近期实施顺序

### Milestone A：恢复工程可信度

1. 修正 Java 17 和 Maven 构建基线；删除当前无效 `module-info.java`，先使用 `Automatic-Module-Name`。
2. 清理空元数据文件、空 ServiceLoader 文件和无效占位类型。
3. 将 README 未实现功能统一标为规划中。
4. 建立最小 CI：编译、单测、集成测试、格式检查。

验证：全仓 `mvn verify` 可重复通过。

### Milestone B：冻结核心契约

1. 定义不可变的规则、SQL 分析、逻辑桶、拓扑版本和路由计划模型。
2. 定义异常和错误码。
3. 定义 `ShardAlgorithm` 和唯一注册中心。
4. 落实 v0.1 SQL 能力矩阵和普通 SQL 透传策略。

验证：领域模型单测通过，不依赖 Spring、MyBatis、HikariCP。

### Milestone C：完成纯内核路由

1. 实现 JSQLParser 分析。
2. 实现参数引用与绑定。
3. 实现稳定 Hash、固定逻辑桶、显式拓扑映射和 AST 改写。
4. 生成包含参数投影、规则版本和拓扑版本的严格单路由 `RoutePlan`。

验证：给定 SQL、参数和规则，可以稳定得到唯一物理 SQL，不连接数据库。

### Milestone D：完成 MyBatis 与事务闭环

1. 实现 MyBatis Executor 拦截器。
2. 在 CacheKey 产生前完成物理 SQL 和参数映射重建，并加入数据源、规则与拓扑版本标识。
3. 接入延迟连接路由数据源。
4. 实现事务分片守卫、规则/拓扑版本固定和作用域清理。
5. 运行真实 MySQL CRUD、缓存与回滚测试。

验证：单分片 CRUD 正确，跨分片事务在访问错误库前被拒绝。

### Milestone E：形成可发布 Starter

1. 完成 Spring Boot 自动配置和配置元数据。
2. 增加用户 Bean 退让和关闭开关。
3. 完成可运行示例和文档。

验证：用户只引入 Starter 并配置规则即可运行，无需 `@EnableLavShard`。

---

## 17. 架构决策记录

后续重要变更应在 `docs/adr/` 中记录 ADR，至少包括：

- 背景和问题。
- 已考虑方案。
- 最终决定。
- 正面和负面影响。
- 回滚或迁移方式。

首批建议 ADR：

1. ADR-001：LavShard 的产品边界与不支持能力。
2. ADR-002：固定逻辑桶与物理拓扑分离。
3. ADR-003：路由计划不持有 `DataSource`。
4. ADR-004：MyBatis Executor 拦截点和延迟连接。
5. ADR-005：跨数据库事务采用 LOCAL_STRICT。
6. ADR-006：规则快照原子替换。
7. ADR-007：受管、透传和不支持 SQL 的分类策略。
8. ADR-008：MyBatis SQL 改写、参数映射与缓存顺序。

---

## 18. 最终成功判据

LavShard 的成功不是“拥有最多功能”，而是：

- 支持范围内的 SQL 总能路由到正确分片。
- 不支持范围内的 SQL 总能在访问错误数据前失败。
- 应用升级时公开契约稳定、迁移路径清晰。
- 运维人员能解释一次路由使用了哪条规则、哪个算法、哪个规则版本和哪个拓扑版本。
- 新框架、新方言和新规则源通过适配器扩展，不破坏核心模型。
- 当需求超出轻量组件边界时，项目能够明确建议更合适的成熟方案。
