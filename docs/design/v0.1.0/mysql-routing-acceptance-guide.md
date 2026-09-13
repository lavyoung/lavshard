# MySQL 单分片路由发布验收指南

## 本轮目标

新增 `MySqlRoutingIT`，使用两个相互独立的 MySQL 8.0.36 Testcontainers 验证 LavShard 的真实驱动、数据库和事务行为。

该测试是严格发布门禁。运行环境没有 Docker 时测试报错，不使用 `disabledWithoutDocker = true` 静默跳过；因此，没有容器能力的
`mvn verify` 不代表项目回归失败，也不能被记录为 MySQL 验收通过。

当前环境结果：

- 测试源码已通过 Maven testCompile。
- 不运行 `*IT` 的模块测试全部通过。
- Failsafe 在测试类初始化阶段报 `Could not find a valid Docker environment`。
- 本机未发现 Docker 命令，localhost:3306 也没有可用 MySQL 服务。
- 24 个 MySQL 测试方法尚未进入执行，因此当前没有业务代码失败证据。

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

测试使用固定镜像标签 `mysql:8.0.36` 保证可复现。它是验收夹具版本，不代表生产环境数据库选型建议。

## 业务代码编写结论

本轮暂时不需要手写任何生产 Java 文件，也不要修改下列类型：

- `LavShardExecutorInterceptor`
- `LavShardRoutingDataSource`
- `LazyRoutingConnection`
- `SpringShardContext`
- SQL 分析器、路由计划器和改写器

原因是当前失败发生在 Testcontainers 获取 Docker 客户端时，早于 `@BeforeEach`、MyBatis 装配、SQL 路由和数据库连接。此时没有证据表明业务实现缺失。

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

只有得到上述业务阶段的真实失败后，再修改相应生产文件，并重新执行全部 24 个用例。不要为了让当前环境转绿而给测试添加自动跳过。

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

聚焦命令预期执行 24 个用例，必须为零失败、零错误、零跳过。完成前不能在 README 中把 MySQL CRUD、生成键、缓存和回滚标记为已验收。

## 提交信息

当前提交仅包含严格 MySQL 验收测试和本文：

```text
test(mysql): 补充分库分表 CRUD 与事务缓存验收
```

若 Docker 环境就绪后暴露业务缺口，修复提交应根据实际问题命名，不要预先使用笼统的 `fix(mysql)`。
