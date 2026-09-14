# MySQL 单分片路由发布验收指南

## 本轮目标

新增 `MySqlRoutingIT`，使用两个相互独立的 MySQL 8.0.36 Testcontainers 验证 LavShard 的真实驱动、数据库和事务行为。

该测试是严格发布门禁。运行环境没有 Docker 时测试报错，不使用 `disabledWithoutDocker = true` 静默跳过；因此，没有容器能力时
`mvn verify` 会失败，也不能被记录为 MySQL 验收通过。

2026-09-14 发布验收结果：

- 通过 SSH 隧道连接 Linux Docker 29.1.3，Testcontainers 自动创建两个独立的 MySQL 8.0.36 实例。
- `MySqlRoutingIT` 共 24 个用例，零失败、零错误、零跳过。
- 仅分表、仅分库、分库分表三种拓扑及 `ExecutorType.SIMPLE`、`REUSE` 均已覆盖。
- 临时 MySQL 和 Ryuk 容器在 JVM 退出后自动清理，没有写入已有开发数据库。
- 验收期间未发现生产业务代码缺口。

## 覆盖矩阵

三种拓扑分别组合 `ExecutorType.SIMPLE` 和 `ExecutorType.REUSE`，每种组合执行四类完整场景，共 24 个用例。

| 拓扑     | 桶 0           | 桶 1           |
|----------|----------------|----------------|
| 仅分表   | ds0.t_order_00 | ds0.t_order_01 |
| 仅分库   | ds0.t_order_00 | ds1.t_order_00 |
| 分库分表 | ds0.t_order_00 | ds1.t_order_01 |

每种组合验证：

1. MySQL InnoDB 的 INSERT、SELECT、UPDATE、DELETE 路由和表名改写。
2. JDBC `useGeneratedKeys` 从实际物理库回填主键。
3. 事务回滚删除未提交行，Java 参数对象已经获得的 ID 不自动撤销。
4. 两个目标的二级缓存预热后，事务首次缓存命中仍建立路由绑定；仅分表可以继续访问同库另一表，分库场景必须拒绝跨库。
5. 空分片键和多行 INSERT 在获取任何物理连接前失败。
6. 每个物理表创建 `INDEX idx_user_id (user_id)`，避免验收 SQL 使用无索引分片条件。

测试使用固定镜像标签 `mysql:8.0.36` 保证可复现，并使用 Testcontainers 1.21.4 兼容近期 Docker
Engine。它们是验收夹具版本，不代表生产环境数据库选型建议。

## 业务代码结论

本轮不需要修改任何生产 Java 文件。以下类型已经通过真实 MySQL 验收：

- `LavShardExecutorInterceptor`
- `LavShardRoutingDataSource`
- `LazyRoutingConnection`
- `SpringShardContext`
- SQL 分析器、路由计划器和改写器

测试已进入 MyBatis 装配、路由、物理连接、SQL 执行、缓存与事务断言阶段并全部通过，因此不能为本轮验收继续增加无证据的生产代码修改。

如果 Docker 环境就绪后测试失败，应保留完整 Maven 输出和 `lavshard-test/target/failsafe-reports`。按失败阶段定位：

| 失败位置           | 优先检查                                                |
|--------------------|---------------------------------------------------------|
| 容器启动、镜像拉取 | Docker 服务、代理、镜像仓库、磁盘空间                   |
| 表初始化           | MySQL 权限、DDL、字符集或驱动连接参数                   |
| 路由前连接计数变化 | SQL 分类、参数绑定、事务守卫调用顺序                    |
| SQL 表名或选库错误 | RoutePlan、AST 改写、DataSource 选择                    |
| 生成键未回填       | MappedStatement 的 KeyGenerator、keyProperty、keyColumn |
| 回滚后仍有记录     | Spring 事务管理器与 MyBatis 是否引用同一逻辑 DataSource |
| 缓存跨库错误       | 物理 CacheKey、dataSourceId、规则和拓扑版本维度         |

后续只有得到业务阶段的真实失败后，才修改相应生产文件，并重新执行全部 24 个用例。不要为了让环境转绿而给测试添加自动跳过。

## Windows 运行环境

准备一个支持 Linux 容器的 Docker 环境，例如 Docker Desktop，并确保 Docker 引擎已经启动。先在 PowerShell 验证：

```powershell
docker version
docker info
docker run --rm mysql:8.0.36 mysql --version
```

第三条命令会拉取并运行本轮固定镜像。它成功后再进入项目根目录执行 Maven。测试会自行创建临时数据库、表和索引，不需要手工创建
schema，也不读取本机 3306 数据库。

不要在测试中写入公司共享数据库、开发数据库或生产凭据。Testcontainers 生成的地址、用户名和密码只属于临时容器。

## 测试命令

```powershell
# 只编译包括 IT 在内的所有测试源码，不启动容器
mvn -pl lavshard-test -am test

# 聚焦真实 MySQL 验收
mvn -pl lavshard-test -am '-Dit.test=MySqlRoutingIT' '-Dfailsafe.failIfNoSpecifiedTests=false' verify

# Docker 环境就绪后的最终全项目验收
mvn clean verify
```

聚焦命令必须执行 24 个用例，并保持零失败、零错误、零跳过。README 中的 MySQL CRUD、生成键、缓存和回滚状态以该发布门禁结果为依据。

## 提交信息

发布收口提交包含 Testcontainers 兼容、CI 和验收状态同步：

```text
chore(release): 完成 v0.1.0 CI 与发布验收收口
```
