# LavShard v0.1.0 兼容性矩阵

## 支持策略

本矩阵只声明已由自动测试或真实数据库验收证明的组合，不把宽泛的版本范围当作兼容承诺。补充新版本前必须先加入 CI 或专项验收。

## 已验证组合

| 组件                | 已验证版本        | 验证范围                                                       |
|---------------------|-------------------|----------------------------------------------------------------|
| Java                | 17                | 全模块编译、单元测试、集成测试和发布产物生成                   |
| Maven               | 3.9.12            | Reactor 构建、Failsafe、源码包和 Javadoc 包                    |
| Spring Boot         | 3.2.5             | 自动配置、配置绑定、事务传播和两个示例应用                     |
| Spring Framework    | 6.1.6             | 本地事务同步、资源绑定和传播行为                               |
| MyBatis             | 3.5.14、3.5.19    | 3.5.14 为最低编译基线；业务项目可选择同一 3.5.x 系列的较新版本 |
| MyBatis-Plus        | 3.5.17            | 由业务项目显式引入；与其要求的 MyBatis 3.5.19 组合验证         |
| MyBatis Spring Boot | 3.0.3             | Starter 自动装配和 Mapper 执行链                               |
| MySQL Connector/J   | 8.0.32            | JDBC 连接、生成键、事务和 SQL 执行                             |
| MySQL Server        | 8.0.36            | 两个独立 Testcontainers 上的 24 项发布验收                     |
| Testcontainers      | 1.21.4            | Docker Engine 29.1.3 远程守护进程                              |
| Docker Engine       | 29.1.3 / API 1.52 | Linux 容器创建、端口映射和 Ryuk 自动清理                       |

## 明确未承诺

- Java 21、其他 Spring Boot 3.x 和其他 MyBatis 版本尚未进入兼容性矩阵。
- MySQL 5.7、MariaDB、PostgreSQL 和其他数据库方言未验证。
- Windows 容器不支持；Windows 开发机必须连接 Linux Docker Engine。
- MyBatis-Plus、GraalVM Native Image、JTA/XA 和第三方分布式事务框架未验证。

## 扩展矩阵规则

新增组合时至少执行：

1. 全仓 `mvn clean verify`。
2. `MySqlRoutingIT` 零失败、零错误、零跳过。
3. 两个示例应用上下文和 HTTP 行为测试。
4. 源码包与 Javadoc 包构建。
5. 对新增版本差异给出兼容性结论，不以“可以编译”替代运行时验证。
