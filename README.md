<p align="center">
  <img src="docs/images/logo.svg" alt="LavShard Logo" width="200"/>
</p>

> 轻量级数据库分库分表路由组件 —— 让海量数据像熔岩般奔流，但分而有道，路由有方。

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Project Status](https://img.shields.io/badge/status-v0.1%20release%20candidate-blue.svg)](docs/design/architecture-roadmap.md)
[![Build Status](https://github.com/lavyoung/lavshard/actions/workflows/ci.yml/badge.svg)](https://github.com/lavyoung/lavshard/actions)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](https://github.com/lavyoung/lavshard/pulls)

---

## 📖 简介

**LavShard** 的目标是成为轻量级、严格正确且性能可预测的数据库分库分表路由组件，为 Spring Boot + MyBatis 应用提供低侵入的单分片能力。

> **项目状态：v0.1 单分片闭环已完成自动测试和真实 MySQL 验收，当前处于发布候选阶段，尚未发布生产稳定版本。** 使用前请严格遵守
> SQL 支持矩阵和本地事务边界。

总体设计见 [架构与演进规划](docs/design/architecture-roadmap.md)，首版边界见 [v0.1 SQL 支持矩阵](docs/design/v0.1.0/v0.1-sql-support-matrix.md)，业务建模前请阅读 [分片数据模型规范](docs/design/sharding-data-model-guidelines.md)。

升级开发版本时，请查看 [MyBatis 与 Starter 包结构迁移说明](docs/design/v0.1.0/package-layout-migration.md)
，其中记录类型迁移、内部实现边界及缓存命名空间变化。

核心设计理念：

- **轻量** —— 核心模块保持零 Spring、MyBatis 和连接池依赖
- **严格** —— 无法安全确定唯一分片时在访问数据库前失败
- **低侵入** —— 通过框架适配器完成参数绑定、路由与 SQL 改写
- **可演进** —— 固定逻辑桶与物理拓扑分离，为后续扩容保留稳定基础

---

## ✨ 特性

| 特性                | 说明                                        |      状态      |
|:--------------------|:--------------------------------------------|:--------------:|
| 可信单分片闭环      | Hash + 固定逻辑桶 + 静态拓扑                | ✅ v0.1 已验收 |
| 分片形态            | 统一拓扑模型覆盖仅分表、仅分库、分库 + 分表 | ✅ v0.1 已验收 |
| MyBatis 集成        | 参数绑定、SQL 改写、缓存与事务安全          | ✅ v0.1 已验收 |
| Spring Boot Starter | 自动配置、配置校验与用户 Bean 退让          | ✅ v0.1 已验收 |
| Range / 自定义算法  | 结构化 Range 规则与算法 SPI                 |    📋 v0.2     |
| 多路由读取          | 受限 `IN` 拆分与无序结果拼接                |    📋 v0.3     |
| 规则治理            | 版本、审计、回滚和安全热更新                |    📋 v0.4     |
| PostgreSQL / JDBC   | 第二方言与原生 JDBC 适配器                  |    🔭 候选     |
| 分布式事务协调器    | 不在内核自研；后续只评估外部协调器适配      |   ⛔ 非目标    |

---

## 🏗️ 目标架构

```text
Business Code / MyBatis Mapper
              │ logical SQL + parameters
              ▼
lavshard-mybatis
(extract BoundSql → call lavshard-core → rebuild physical BoundSql/CacheKey)
              │ scoped dataSourceId + physical SQL
              ▼
Spring DataSource Chain (auto-configured by lavshard-spring-boot-starter)
(transaction guard → LazyConnectionDataSourceProxy → LavShardRoutingDataSource)
              │
       physical DataSources

lavshard-core: Parse → Classify → Bind → Validate → Bucket → Topology → Rewrite
               produces an immutable, framework-neutral RoutePlan only
```

---

## 🚀 快速开始

> 当前代码是 v0.1.0 发布候选版本；配置键和公开 API 在正式发布前仍可能调整。

### 接入方式

v0.1 的接入方式是：

1. 引入 `lavshard-spring-boot-starter`。
2. 引用已有 `DataSource` Bean，或使用 Starter 便捷模式创建物理数据源。
3. 配置分片表、固定逻辑桶、Hash 版本和物理拓扑。
4. 使用普通 MyBatis Mapper 编写支持矩阵内的单分片 SQL。
5. Starter 自动装配，不要求额外添加 `@EnableLavShard`。

可直接运行两个示例：

```bash
# 单库分表：一个数据源、两个物理表
mvn -pl lavshard-example/lavshard-example-simple -am -DskipTests package
java -jar lavshard-example/lavshard-example-simple/target/lavshard-example-simple-0.1.0.jar

# 多数据源分库：两个数据源、跨库本地事务拒绝
mvn -pl lavshard-example/lavshard-example-multi-datasource -am -DskipTests package
java -jar lavshard-example/lavshard-example-multi-datasource/target/lavshard-example-multi-datasource-0.1.0.jar
```

示例默认使用 H2 的 MySQL 兼容模式，便于直接启动；真实 MySQL 行为由 `MySqlRoutingIT` 使用两个 MySQL 8.0.36
临时实例验收。配置模型和生产边界见[总体架构与演进规划](docs/design/architecture-roadmap.md)。

### v0.1 行为边界

- 已配置分片表：严格解析、参数绑定、路由和 SQL 改写。
- 明确普通表：透传到默认数据源。
- 无法产生唯一安全路由的受管 SQL：访问数据库前失败。
- 单个本地事务：只能绑定一个物理数据库和一个兼容规则/拓扑版本。

精确支持范围见 [v0.1 SQL 支持矩阵](docs/design/v0.1.0/v0.1-sql-support-matrix.md)。

### 跨库事务与不同分片键

不同逻辑表可以使用不同分片键，也可以在彼此独立的调用中路由到不同数据库。v0.1 限制的是 Spring 本地事务的资源边界：事务第一次路由后固定一个
`dataSourceId`，后续 SQL 命中其他数据库时，在获取第二个物理连接前抛出 `CrossShardTransactionException`。

- 不同逻辑表最终命中同一 `dataSourceId`：可以分别执行多条 SQL，并由一个本地事务提交或回滚。
- 不同逻辑表最终命中不同 `dataSourceId`：可以在独立事务中执行，但不能宣称一个本地事务具有跨库原子性。
- 可接受最终一致的流程：优先采用 Transactional Outbox、幂等消费、重试与对账；长流程可以使用 Saga。
- 必须跨库强一致的短事务：评估 XA；库存、余额、额度等需要资源预留的核心流程评估 TCC。
- LavShard 不提供无协调器的 `LOCAL_BEST_EFFORT` 模式，也不自研 XA、TCC、Saga 或事务恢复日志；未来只考虑可选的外部协调器适配模块。

详细决策见 [ADR-005：跨数据库事务采用 LOCAL_STRICT](docs/adr/ADR-005-local-strict-cross-database-transactions.md)。

---

## 🧩 模块结构

```text
lavshard/
├── lavshard-core/                  # 核心路由引擎（无 Spring 依赖）
├── lavshard-mybatis/               # MyBatis 参数、缓存、执行与延迟连接适配
├── lavshard-spring-boot-starter/   # Spring Boot 自动配置
├── lavshard-example/               # 可运行示例
│   ├── lavshard-example-simple/
│   └── lavshard-example-multi-datasource/
├── lavshard-test/                  # 集成测试 + JMH 压测
└── docs/                           # 中英文文档
```

| 模块                           | 说明                                                        |
|:-------------------------------|:------------------------------------------------------------|
| `lavshard-core`                | 领域模型、JSQLParser 分析、逻辑桶、拓扑、AST 改写与路由计划 |
| `lavshard-spring-boot-starter` | 配置绑定、启动校验、数据源与 MyBatis/Spring 事务自动装配    |
| `lavshard-mybatis`             | MyBatis 参数绑定、SQL 改写、缓存安全、延迟连接和执行适配    |
| `lavshard-example-*`           | 单库分表和多数据源分库的可运行 Spring Boot 示例             |
| `lavshard-test`                | Testcontainers 集成测试、JMH 性能基准                       |

---

## 🔧 构建

> `mvn clean verify` 是发布门禁，会执行单元测试、跨模块集成测试和真实 MySQL Testcontainers 验收；运行环境必须提供可用的
> Linux Docker Engine。

### 环境要求

- JDK 17+
- Maven 3.8+
- Docker Engine（执行真实 MySQL 发布验收时必需）

### 命令

```bash
# 克隆仓库
git clone https://github.com/lavyoung/lavshard.git
cd lavshard

# 全量发布门禁（含 MySQL Testcontainers）
mvn clean verify

# 快速构建（跳过测试）
mvn clean install -Pfast

# 只构建 core
mvn clean install -pl lavshard-core -am
```

---

## 🗺️ 路线图

- [x] **v0.1.0 发布候选** — 可信单分片闭环、固定逻辑桶、MyBatis 与本地事务安全
- [ ] **v0.2.0** — Range、自定义算法、规则版本、诊断与最小 Actuator
- [ ] **v0.3.0** — 受限多路由读取和资源上限保护
- [ ] **v0.4.0** — 规则治理、安全热更新、迁移验证与生产可观测性
- [ ] **v0.5.0** — 候选方言与框架适配器
- [ ] **v1.0.0** — 稳定公共 API、配置与兼容性承诺

详见 [总体架构与演进规划](docs/design/architecture-roadmap.md)。

---

## 🤝 贡献

欢迎任何形式的贡献！请先阅读 [CONTRIBUTING.md](./CONTRIBUTING.md)。

1. Fork 本仓库
2. 创建特性分支：`git checkout -b feature/amazing-feature`
3. 提交代码：`git commit -m 'feat: add amazing feature'`
4. 推送分支：`git push origin feature/amazing-feature`
5. 提交 Pull Request

**提交规范**（遵循 [Conventional Commits](https://www.conventionalcommits.org/)）：

- `feat:` 新功能
- `fix:` 修复 bug
- `docs:` 文档变更
- `refactor:` 重构
- `test:` 测试
- `chore:` 构建/工具变更

---

## 📄 许可证

本项目基于 [Apache License 2.0](./LICENSE) 发布。

## 📮 联系

- **Author:** lavyoung
- **GitHub:** [@lavyoung](https://github.com/lavyoung)
- **Issues:** [github.com/lavyoung/lavshard/issues](https://github.com/lavyoung/lavshard/issues)
- **Discussions:** [github.com/lavyoung/lavshard/discussions](https://github.com/lavyoung/lavshard/discussions)

---

## 🙏 致谢

灵感来源于以下优秀开源项目：

- [Apache ShardingSphere](https://shardingsphere.apache.org/)
- [MyBatis-Plus](https://baomidou.com/)
- [Dynamic-Datasource](https://github.com/baomidou/dynamic-datasource)

---

<div align="center">

**⭐ 如果 LavShard 对你有帮助，欢迎给个 Star！**

Made with ❤️ by [lavyoung](https://github.com/lavyoung)

</div>
