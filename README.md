# LavShard
<p align="center">
  <img src="docs/images/logo.svg" alt="LavShard Logo" width="200"/>
</p>

> 轻量级数据库分库分表路由组件 —— 让海量数据像熔岩般奔流，但分而有道，路由有方。

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Project Status](https://img.shields.io/badge/status-v0.1%20design%20%26%20development-yellow.svg)](docs/design/architecture-roadmap.md)
[![Build Status](https://github.com/lavyoung/lavshard/actions/workflows/ci.yml/badge.svg)](https://github.com/lavyoung/lavshard/actions)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](https://github.com/lavyoung/lavshard/pulls)

---

## 📖 简介

**LavShard** 的目标是成为轻量级、严格正确且性能可预测的数据库分库分表路由组件，为 Spring Boot + MyBatis 应用提供低侵入的单分片能力。

> **项目状态：架构设计与 v0.1 最小闭环开发中，尚无可用于生产或正式集成的发布版本。** 当前仓库中的部分类型和示例仍是占位骨架；只有通过自动测试与真实 MySQL 集成测试验证的能力才会标记为已支持。

总体设计见 [架构与演进规划](docs/design/architecture-roadmap.md)，首版边界见 [v0.1 SQL 支持矩阵](docs/design/v0.1.0/v0.1-sql-support-matrix.md)，业务建模前请阅读 [分片数据模型规范](docs/design/sharding-data-model-guidelines.md)。

核心设计理念：

- **轻量** —— 核心模块保持零 Spring、MyBatis 和连接池依赖
- **严格** —— 无法安全确定唯一分片时在访问数据库前失败
- **低侵入** —— 通过框架适配器完成参数绑定、路由与 SQL 改写
- **可演进** —— 固定逻辑桶与物理拓扑分离，为后续扩容保留稳定基础

---

## ✨ 特性

| 特性 | 说明 | 状态 |
| :--- | :--- | :---: |
| 可信单分片闭环 | Hash + 固定逻辑桶 + 静态拓扑 | 🚧 开发中 |
| 分片形态 | 统一拓扑模型覆盖仅分表、仅分库、分库 + 分表 | 🚧 v0.1 开发中 |
| MyBatis 集成 | 参数绑定、SQL 改写、缓存与事务安全 | 📋 规划中 |
| Spring Boot Starter | 自动配置、配置校验与用户 Bean 退让 | 📋 规划中 |
| Range / 自定义算法 | 结构化 Range 规则与算法 SPI | 📋 v0.2 |
| 多路由读取 | 受限 `IN` 拆分与无序结果拼接 | 📋 v0.3 |
| 规则治理 | 版本、审计、回滚和安全热更新 | 📋 v0.4 |
| PostgreSQL / JDBC | 第二方言与原生 JDBC 适配器 | 🔭 候选 |
| 分布式事务与通用结果合并 | 不属于默认内核范围 | ⛔ 非目标 |

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

## 🚀 目标用法草案

> 以下内容用于描述期望的最终使用体验，当前版本尚不可运行，配置键和 API 会在 v0.1 契约冻结前调整。请勿将此章节作为已发布功能说明。

### 预期接入方式

v0.1 的目标体验是：

1. 引入 `lavshard-spring-boot-starter`。
2. 引用已有 `DataSource` Bean，或使用 Starter 便捷模式创建物理数据源。
3. 配置分片表、固定逻辑桶、Hash 版本和物理拓扑。
4. 使用普通 MyBatis Mapper 编写支持矩阵内的单分片 SQL。
5. Starter 自动装配，不要求额外添加 `@EnableLavShard`。

配置模型仍在冻结中，当前以[总体架构与演进规划](docs/design/architecture-roadmap.md)为准。完整实现通过 v0.1 发布门禁后，本节再提供可复制运行的正式示例。

### v0.1 行为边界

- 已配置分片表：严格解析、参数绑定、路由和 SQL 改写。
- 明确普通表：透传到默认数据源。
- 无法产生唯一安全路由的受管 SQL：访问数据库前失败。
- 单个本地事务：只能绑定一个物理数据库和一个兼容规则/拓扑版本。

精确支持范围见 [v0.1 SQL 支持矩阵](docs/design/v0.1.0/v0.1-sql-support-matrix.md)。

---

## 🧩 模块结构

```text
lavshard/
├── lavshard-core/                  # 核心路由引擎（无 Spring 依赖）
├── lavshard-sql-jsqlparser/        # 规划：MySQL SQL 分析与 AST 改写
├── lavshard-mybatis/               # 规划：MyBatis 参数、缓存与执行适配
├── lavshard-spring-boot-starter/   # Spring Boot 自动配置
├── lavshard-example/               # 示例骨架
│   ├── lavshard-example-simple/
│   └── lavshard-example-multi-datasource/
├── lavshard-test/                  # 集成测试 + JMH 压测
└── docs/                           # 中英文文档
```

| 模块 | 说明 |
| :--- | :--- |
| `lavshard-core` | 领域模型、SQL 分析抽象、逻辑桶、拓扑与路由计划 |
| `lavshard-sql-jsqlparser` | 规划模块：JSQLParser 分析、能力校验与 AST 表名改写 |
| `lavshard-spring-boot-starter` | 当前 Starter 骨架，后续负责配置绑定与自动装配 |
| `lavshard-mybatis` | 规划模块：MyBatis 参数绑定、SQL 改写与缓存安全 |
| `lavshard-example-*` | 当前示例骨架，通过 v0.1 发布门禁后提供可运行示例 |
| `lavshard-test` | Testcontainers 集成测试、JMH 性能基准 |

---

## 🔧 构建

> 当前工程基线可通过 `mvn clean verify` 完成全模块编译与打包。现有测试仍是占位骨架，构建成功不代表 v0.1 分片能力已经通过功能验收。

### 环境要求

- JDK 17+
- Maven 3.8+
- MySQL 8.0+（示例用，可选 H2）

### 命令

```bash
# 克隆仓库
git clone https://github.com/lavyoung/lavshard.git
cd lavshard

# 全量构建（含测试）
mvn clean install

# 快速构建（跳过测试）
mvn clean install -Pfast

# 只构建 core
mvn clean install -pl lavshard-core -am
```

---

## 🗺️ 路线图

- [ ] **v0.1.0** — 可信单分片闭环、固定逻辑桶、MyBatis 与本地事务安全
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

```text
Copyright 2026 lavyoung

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

---

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
