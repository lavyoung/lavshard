# v0.1 开发阶段包结构迁移

本说明覆盖 MyBatis 与 Starter 从平级包到职责包的调整，以及随后对内部实现边界的收紧。当前尚未发布稳定版本；本次包含 Java
类型包名和可见性的破坏性调整，不提供旧类型转发层。

## 契约边界

- 业务扩展契约使用 `core.api` 下已有的算法、规则和路由类型。
- Starter 的应用配置入口为 `lavshard.*` 配置项；配置项名称及绑定语义保持不变。
- `starter.autoconfigure` 保存 Spring Boot 自动配置入口，`starter.autoconfigure.config.LavShardProperties`
  保存配置绑定模型。这些是框架集成入口，不代表内部 Bean、方法或 Java 类型的稳定扩展承诺。
- `starter.internal.config` 负责配置编译、启动校验和运行时快照；`starter.internal.datasource` 管理 Starter 创建的连接池；
  `starter.internal.transaction` 负责 Spring 事务路由守卫。
- `mybatis.internal.executor` 负责 MyBatis 执行器适配；`mybatis.internal.routing` 负责线程路由上下文和延迟连接。
- `internal` 中部分类型因跨包装配保留 `public`，但不属于应用公共 API。仅在同包使用的辅助类采用包级可见性。

本轮没有增加 Maven 模块或扩展接口。未来出现独立 JDBC 适配需求时再评估提取路由连接实现；core 继续不依赖 Spring、MyBatis
或连接池。

## 类型迁移表

以下包名均省略共同前缀 `io.github.lavyoung.lavshard.`；类名不变。

| 类型                                                                                                              | 原包             | 新包                                  |
|-------------------------------------------------------------------------------------------------------------------|------------------|---------------------------------------|
| LavShardExecutorInterceptor、MyBatisIntegrationScope                                                              | mybatis.internal | mybatis.internal.executor             |
| MyBatisBoundSqlRewriter、MyBatisMappedStatementRewriter、MyBatisParameterValueExtractor、MyBatisCacheKeyAugmenter | mybatis.internal | mybatis.internal.executor（包级可见） |
| LavShardRoutingDataSource、MyBatisRouteContext                                                                    | mybatis.internal | mybatis.internal.routing              |
| LazyRoutingConnection                                                                                             | mybatis.internal | mybatis.internal.routing（包级可见）  |
| LavShardAutoConfiguration、LavShardDataSourceAutoConfiguration                                                    | starter          | starter.autoconfigure                 |
| LavShardProperties                                                                                                | starter          | starter.autoconfigure.config          |
| LavShardConfigurationCompiler、LavShardConfigurationSnapshot、LavShardAlgorithmConfigurationValidator             | starter          | starter.internal.config               |
| LavShardManagedDataSourceRegistry                                                                                 | starter          | starter.internal.datasource           |
| SpringShardContext                                                                                                | starter.support  | starter.internal.transaction          |

如果已采用本轮之前的中间目录布局，还需将编译器、快照和校验器从 `starter.autoconfigure.config` 移到
`starter.internal.config`，注册表从 `starter.autoconfigure.datasource` 移到 `starter.internal.datasource`，事务守卫从
`starter.transaction` 移到 `starter.internal.transaction`。LavShardProperties 保留原位置。

对应测试跟随生产类迁移。自动配置发现文件
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 已更新为两个新的自动配置完整类名。

## 被删除的空骨架

以下类型原先均为空实现且仓库中没有使用方，已删除，可从 Git 历史恢复：

- `starter.EnableLavShard`
- `starter.LavShardRouteAutoConfiguration`
- `starter.LavShardRuleAutoConfiguration`
- `starter.actuator.LavShardEndpoint`
- `starter.actuator.LavShardHealthIndicator`
- `starter.condition.OnLavShardEnabledCondition`
- `starter.config.LavShardCoreConfiguration`
- `starter.config.LavShardDataSourceConfiguration`
- `starter.support.LavShardBeanPostProcessor`

应用依靠 Spring Boot 自动配置启用集成，不需要添加旧的 EnableLavShard 注解。Actuator 等能力仍按路线图推进。

## 缓存协议

MyBatis 路由缓存命名空间从 Java 完整类名改为固定标识 `lavshard:mybatis:route:v1`。追加到 MyBatis 基础 CacheKey 的顺序为：

- Managed：协议标识、`MANAGED`、dataSourceId、ruleVersion、topologyVersion。
- PassThrough：协议标识、`PASSTHROUGH`、dataSourceId。

未来包或类重命名不得改变这个标识。只有缓存语义发生不兼容调整时才升级协议版本。测试通过独立构造的预期 CacheKey 固定该契约。

本次升级会使旧缓存键与新键隔离，产生一次缓存冷启动。默认进程内缓存随应用重启销毁；若应用自定义了共享或持久化二级缓存，应在部署时停止旧版本实例并按缓存提供方机制失效受影响
Mapper 的旧条目。不要假设跨版本实例会互相失效彼此的数据缓存，也不要为了迁移清空与 LavShard 无关的缓存。

## 应用迁移与验证

1. 同步升级 LavShard 各模块，并重新编译直接引用旧类型的代码。手动注册拦截器、类型形式的自动配置排除，以及字符串形式的类名配置均需检查。
2. 移除对包级辅助类的直接依赖。它们不再作为外部集成入口，应通过执行器拦截器或路由 DataSource 使用完整行为。
3. 按上述说明处理自定义二级缓存，执行干净构建，避免旧包 class 文件残留。

PowerShell 下在项目根目录执行（带点的 Maven 属性参数保持引号）：

```powershell
mvn -pl lavshard-mybatis -am '-Dtest=MyBatisCacheKeyAugmenterTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -pl lavshard-spring-boot-starter -am test
mvn clean verify
```

回退时应整体回退这批包结构变更并重新构建应用，同时处理新旧缓存命名空间的切换，避免混用不同版本的 LavShard 模块。
