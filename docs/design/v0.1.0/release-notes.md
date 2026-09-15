# LavShard v0.1.0 Release Notes

## 发布状态

v0.1.0 是 LavShard 首个发布候选版本，提供 Spring Boot + MyBatis + MySQL 场景下严格、可预测的单分片路由闭环。它尚不是生产稳定版本，公开
API 和配置在 `0.x` 阶段仍可能发生有迁移说明的调整。

## 核心能力

- 支持仅分表、仅分库、分库分表三种静态拓扑。
- 支持矩阵范围内的单分片 `SELECT`、`INSERT`、`UPDATE`、`DELETE`。
- 支持 JDBC 参数和兼容类型字面量分片键。
- 支持 MyBatis SIMPLE、REUSE、一级/二级缓存、生成键和 Cursor。
- 支持 Spring 单数据库本地事务，并在访问第二个数据库前拒绝跨库事务。
- 支持引用应用 DataSource Bean，或由 Starter 创建 HikariCP 数据源。
- 支持可复用分片布局模板：普通表只声明分片列即可自动展开固定逻辑桶、物理节点和桶映射。
- 布局模板统一覆盖单库分表、纯分库同名表及分库分表，并保留完整显式拓扑专家模式。
- 未知表、缺失分片键、混合受管/普通表及不支持语法均快速失败，不回退默认库。

## 验收结果

发布候选基线完成以下验证：

- 8 个 Maven 模块 `clean verify` 全部成功。
- 639 个自动测试零失败。
- `MySqlRoutingIT` 在两个 MySQL 8.0.36 实例上执行 24 个场景。
- Testcontainers 1.21.4 直接连接 Docker Engine 29.1.3，无需覆盖 Docker API 版本。
- Core、MyBatis、Starter 的源码包和 Javadoc 包成功生成。
- Testcontainers 临时容器在 JVM 退出后自动清理。

## 发布质量门禁

- Java 格式检查以提交 `bdf3275` 为历史基线，只约束此后新增或修改的 Java 文件，避免首个版本发布前进行与功能无关的大规模格式重写。
- Pull Request 会审查本次引入的运行时依赖；发现高危或严重漏洞时阻止合并。
- `v0.1.0` 是首个公开版本，没有可比较的历史发布制品，因此本版本不执行二进制兼容性比较。自 `v0.1.0` 发布后，后续版本应以其公开
  API 为兼容基线。
- 依赖审查只覆盖 Pull Request 新引入的依赖变化，不等同于对全部存量依赖的离线漏洞扫描。

## 重要边界

- 一条 SQL 只能访问一个受管逻辑表，并由等值分片键命中唯一节点。
- 不支持 `IN` 多路由、跨分片 JOIN、子查询、`UNION`、多行 INSERT 和 Batch Executor。
- 不支持运行期动态规则、在线扩容编排和分布式事务。
- 已有数据后不能直接修改布局版本、数据源顺序、桶数量、Hash 版本或表后缀语义；定位变化必须通过独立迁移流程完成。
- 不同逻辑表可以采用不同分片键；本地事务能否共同执行只取决于最终是否命中同一个 `dataSourceId`。
- 需要跨库最终一致时由业务采用 Outbox、幂等消费、重试和对账；需要强一致时使用外部成熟协调器。

## 升级与接入

这是首个候选版本，没有旧版升级步骤。接入前必须阅读：

- [SQL 支持矩阵](v0.1-sql-support-matrix.md)
- [兼容性矩阵](compatibility-matrix.md)
- [分片数据模型规范](../sharding-data-model-guidelines.md)
- [LOCAL_STRICT 事务决策](../../adr/ADR-005-local-strict-cross-database-transactions.md)

示例应用位于：

- `lavshard-example/lavshard-example-simple`
- `lavshard-example/lavshard-example-multi-datasource`
