# Changelog

本项目遵循[语义化版本](https://semver.org/lang/zh-CN/)。`0.x` 阶段的公开 API 和配置仍可能发生有说明的破坏性调整。

## [Unreleased]

### Fixed

- 避免向业务项目传递并锁定 MyBatis 最低编译版本，允许标准 MyBatis 或 MyBatis-Plus Starter 决定最终版本。
- 确保 LavShard 托管数据源先于 Spring Boot 默认数据源自动配置注册，无需额外提供 `spring.datasource.url`。

### Planned

- Range 分片算法。
- 自定义算法 SPI。
- 编程式 Hint 路由。
- 安全的规则快照手动重载。

## [0.1.0] - Release Candidate

### Added

- 固定逻辑桶、Murmur3 稳定 Hash、静态拓扑和不可变单路由计划。
- `SELECT`、`INSERT`、`UPDATE`、`DELETE` 单分片分析、绑定、路由和 AST 表名改写。
- 受管 SQL、普通表透传、未知表及不支持 SQL 的严格分类。
- MyBatis 参数提取、`BoundSql`/`MappedStatement` 重建、缓存键隔离、生成键和游标生命周期支持。
- 延迟连接数据源、数据源选择、连接状态同步和失败恢复。
- Spring Boot 自动配置、配置元数据、托管数据源和应用 DataSource Bean 引用模式。
- 单数据库本地事务绑定、跨数据库事务拒绝，以及 `REQUIRED`、`REQUIRES_NEW`、`NESTED` 传播行为。
- 单库分表与多数据源分库的可运行示例。
- 两个 MySQL 8.0.36 Testcontainers 上的 24 项真实数据库验收。

### Constraints

- 每条 SQL 最多包含一个受管表，并且必须通过单个非空等值分片键确定唯一节点。
- 不支持跨分片 JOIN、广播查询、多行 INSERT、MyBatis Batch、动态规则和分布式事务。
- 本地事务只能绑定一个 `dataSourceId`；跨库业务需采用共置、最终一致性方案或外部事务协调器。

[Unreleased]: https://github.com/lavyoung/lavshard/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/lavyoung/lavshard/releases/tag/v0.1.0
