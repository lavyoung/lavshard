# MyBatis 一级、二级缓存与事务集成验证

## 本轮结论

新增 MyBatisCacheTransactionTest，20 个用例首次及补充验证均通过，现有生产代码无需修改。

测试使用真实 Mapper namespace 缓存、Spring 事务、H2 数据库，并统计 PreparedStatement 实际执行 SELECT 的次数。REUSE 可以重用
Statement，因此仅统计 prepareStatement 或连接次数不足以证明缓存命中。

| 场景                                                   | SIMPLE/REUSE 用例数 |
|--------------------------------------------------------|--------------------:|
| 一级 SESSION 缓存命中，更新后失效                      |                   2 |
| 一级 STATEMENT 作用域重复执行查询                      |                   2 |
| 跨事务二级缓存命中，无物理连接获取                     |                   2 |
| 更新提交后旧缓存失效、新结果可缓存                     |                   2 |
| 更新及查询后回滚，未提交值不发布到二级缓存             |                   2 |
| 两库都已预热缓存，事务首次命中仍绑定路由并拒绝跨库     |                   2 |
| 两个物理库同名表结果分别缓存                           |                   2 |
| 单分片更新使整个 Mapper namespace 缓存失效             |                   2 |
| 仅 ruleVersion 或 topologyVersion 变化导致旧缓存不命中 |                   4 |

一级缓存测试关闭二级缓存，避免二级命中掩盖一级行为。二级缓存测试显式使用 CacheNamespace，而非只开启 cacheEnabled。

## 生产代码为什么无需修改

- LavShardExecutorInterceptor 基于物理 SQL 创建 CacheKey，再由 MyBatisCacheKeyAugmenter 加入路由维度。
- MyBatisRouteContext.open 在调用 Executor 前执行事务守卫，即使 Executor 最终从缓存返回，也不会跳过事务数据源绑定。
- MyBatisMappedStatementRewriter 保留同一个 Cache 和 flushCacheRequired，物理 SQL 改写没有切断 namespace 级失效规则。
- 提交与回滚仍由 MyBatis/Spring 管理，本轮不引入自定义缓存提交机制。

## 业务 Mapper 完整示例

此示例只返回不可变 String，因此演示 readWrite=false。示例未写入生产目录，不要求为所有业务 Mapper 开启二级缓存。

```java
package your.application.mapper;

import org.apache.ibatis.annotations.CacheNamespace;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 单分片订单备注查询与更新，共享同一 namespace 缓存。 */
@Mapper
@CacheNamespace(readWrite = false)
public interface OrderCacheMapper {

    /**
     * 查询备注。没有匹配记录时返回空 Optional。
     *
     * @param userId 非空分片键，示例假设只匹配一条记录
     * @return 查询到的非空备注，或空 Optional
     * @throws RuntimeException 路由或数据库查询失败时抛出
     */
    @Select("""
            SELECT note FROM t_order WHERE user_id = #{userId}
            """)
    java.util.Optional<String> findNote(@Param("userId") String userId);

    /**
     * 更新同一 Mapper 管理的数据，并在提交时失效 namespace 缓存。
     *
     * @param userId 非空分片键
     * @param note 新备注
     * @return 受影响行数
     * @throws RuntimeException 路由或数据库更新失败时抛出
     */
    @Update("""
            UPDATE t_order SET note = #{note} WHERE user_id = #{userId}
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    int updateNote(@Param("userId") String userId, @Param("note") String note);
}
```

该业务示例用 Optional 表达“无记录”；当前集成用例使用确定存在的行和 String 返回值，未新增验证 Optional
或缺失记录缓存语义。真实订单一名用户可能有多行，不能直接照搬单行查询，应按实际业务唯一条件增加过滤。

## 配置及边界

已有 MyBatis 配置中可明确设置：

```yaml
mybatis:
  configuration:
    cache-enabled: true
    local-cache-scope: SESSION
```

cache-enabled 允许二级缓存；具体 Mapper 还需要 CacheNamespace 或 XML cache 声明。SESSION 的一级缓存跟随
SqlSession，STATEMENT 在语句级结束后清理；本轮分别验证了两种作用域。

业务写事务仍通过 Spring 的有效事务代理执行。不需要改 LavShard 路由器、缓存增强器或事务守卫。

### 不可忽略的限制

- readWrite=false 会共享缓存返回对象，示例仅使用不可变 String；不要未经评估用于可变 DTO。本轮没有验证 readWrite=true
  的对象序列化路径。
- 同一 Mapper namespace 下任一分片更新提交都会失效整个 namespace。本轮没有提供按分片精细失效。
- 二级缓存并不会自动感知其他 Mapper namespace、直接 JDBC 或外部应用的写入。必须先确认写入路径和失效策略，再决定是否启用。
- 两库结果隔离用例的分片参数不同，因此不能单凭该用例证明 dataSourceId 是唯一产生隔离的维度；已有 CacheKey 单元契约负责独立验证该维度。
- 版本用例通过测试专用快照 Supplier 替换保持其他条件不变，只验证缓存键版本隔离，不代表 Starter 已实现生产规则热更新，也不证明旧缓存条目已被物理清除。
- 本轮回滚用例是“写入、读取未提交值、回滚”；不额外承诺所有只读事务回滚时的缓存发布细节。
- H2 用于框架缓存/事务机制验证，MySQL 隔离级别和驱动行为仍需真实数据库验收。

## 验证命令

```powershell
# 聚焦本轮
mvn -pl lavshard-test -am '-Dtest=MyBatisCacheTransactionTest' '-Dsurefire.failIfNoSpecifiedTests=false' test

# 模块及依赖
mvn -pl lavshard-test -am test

# 全项目
mvn verify
```

## 提交信息

```text
test(mybatis): 验证真实缓存命中与事务失效闭环
```
