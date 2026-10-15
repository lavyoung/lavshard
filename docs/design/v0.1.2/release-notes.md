# LavShard v0.1.2 Release Notes

> 发布状态：**Draft**
> 发布日期：待定
> Git Tag：待创建 `v0.1.2`
> 当前代码版本：`0.1.2`

## 发布摘要

v0.1.2 面向需要显式执行跨分片扫描的业务场景，新增公开、稳定的
`ShardBroadcastOperations` API。业务代码不再需要依赖 Starter 内部的数据源注册表，
并可在回调中取得确定的物理数据源与物理表。

本版本同时完善简化静态拓扑配置、分片路由诊断和自动配置兼容性。常规 MyBatis SQL
仍保持原有的单分片路由语义；广播扫描必须由业务显式调用公开 API。

## Added

- 新增 `ShardBroadcastOperations` 公开接口，支持：
  - `targets`：获取一个逻辑表对应的全部物理扫描目标；
  - `forEachTarget`：按稳定节点顺序遍历目标；
  - `queryAll`：按目标顺序合并各分片查询结果。
- 新增 `ShardBroadcastTarget`，向回调提供 `nodeId`、`dataSourceId`、物理
  `DataSource` 与真实物理表名，适配分库分表场景。
- 新增简化分片布局配置，可统一描述单库分表、纯分库同名表和分库分表；仍保留显式拓扑模式。
- 新增路由诊断日志，覆盖启动摘要、SQL 路由决策、物理数据源首次选择和事务首次绑定。
- 新增基于物理数据源的 Actuator 健康检查自动配置。

## Changed

- LavShard 不再向业务项目传递并锁定 MyBatis 最低编译版本；标准 MyBatis Starter 或
  MyBatis-Plus Starter 由业务项目自行选择与管理。
- 托管物理数据源会在 Spring Boot 默认数据源自动配置之前完成注册，无需额外配置
  `spring.datasource.url`。
- 跨分片扫描始终按 `nodeId` 稳定排序，并以物理节点为单位去重；多个逻辑桶映射到
  同一节点时不会重复执行回调。

## Fixed

- 健康检查直接探测已解析的物理数据源，避免通过路由数据源造成不必要的路由依赖。
- 数据源自动配置顺序与 MyBatis 依赖传递策略已调整，降低 Starter 集成冲突风险。
- Maven 发布配置区分 `central-snapshot` 与 `central-release`，正式发布时统一执行 GPG 签名，并从发布包排除示例与测试模块。

## Breaking Changes

- 本版本没有计划中的破坏性变更。
- `0.x` 阶段的公开 API 和配置仍可能演进；后续如有破坏性调整，必须提供迁移说明。

## Upgrade Guide

### Maven

```xml
<dependency>
    <groupId>io.github.lavyoung</groupId>
    <artifactId>lavshard-spring-boot-starter</artifactId>
    <version>0.1.2</version>
</dependency>
```

### Cross-Shard Scan

```java
List<TaskEntity> tasks = shardBroadcastOperations.queryAll(
        new QualifiedTableName("task"),
        target -> taskRepository.queryPublishableTasks(
                target.dataSource(),
                target.actualTable(),
                limit
        )
);
```

跨分片扫描不应放在 Spring 活跃事务中执行；框架会抛出
`CrossShardTransactionException`，避免业务误认为该操作具备跨库原子性。

## Compatibility

| Component | Verified Version | Notes |
| --- | --- | --- |
| Java | 17 | Starter 模块编译与测试 |
| Spring Boot | 3.x | Starter 自动配置与示例集成范围 |
| MyBatis | 业务项目自行管理 | LavShard 不传递或锁定最终 MyBatis 版本 |
| MyBatis-Plus | 业务项目自行管理 | 与标准 MyBatis Starter 二选一 |
| MySQL | 8.0.36 | 既有 v0.1.0 Testcontainers 验收基线 |

## Verification

- 已执行：`mvn clean verify -DskipITs`，Core、MyBatis、Starter、示例及非容器集成测试共
  667 项通过，0 failures, 0 skipped。
- 覆盖：广播目标去重与排序、结果合并、未知逻辑表、活跃事务拒绝、公开 Bean 自动配置，
  以及多 SET UPDATE 的 JDBC 参数映射回归。
- 发布前待执行：在具备 Docker 的环境中完成 MySQL Testcontainers 验收；当前环境未安装
  Docker，`MySqlRoutingIT` 无法启动容器。
- 发布前待补充：v0.1.2 的 Git Tag、提交 SHA、CI 运行链接和制品发布记录。

## Known Limitations

- 常规 SQL 仍只支持命中唯一物理节点的单分片路由；不会自动广播 Mapper SQL。
- `queryAll` 不提供全局排序、全局分页、并行执行、部分分片失败继续或结果限流。
- 跨分片扫描不提供分布式事务语义；调用方需要自行控制幂等、重试和结果处理。
- 不支持 Range 分片算法、运行期规则热更新和分布式事务。

## Links

- [Changelog](../../../CHANGELOG.md)
- [README](../../../README.md)
- [Release Notes Template](../release-notes-template.md)
- [Architecture Roadmap](../architecture-roadmap.md)
- [Issue #1: Public cross-shard scan / broadcast API](https://github.com/lavyoung/lavshard/issues/1)
