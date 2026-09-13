# MyBatis 游标生命周期验证与业务编写指南

## 本轮结论

新增 MyBatisCursorLifecycleTest，7 类场景组合 SIMPLE/REUSE 和 cacheEnabled=false/true，共 28 个执行用例，首次执行全部通过。现有生产实现无需修改。

| 场景                          | 验证                                                                    |
|-------------------------------|-------------------------------------------------------------------------|
| 完整遍历                      | 读取正确物理库数据，Mapper 返回后路由上下文已清理，游标仍可在事务内消费 |
| 提前关闭                      | 结果集关闭，同一事务同一连接可再打开一个游标                            |
| 未消费即结束事务              | Session 关闭游标，事务外无法继续遍历                                    |
| 无事务调用 SqlSessionTemplate | Mapper 返回的游标已关闭，不可作为业务流直接返回                         |
| 遍历期间访问另一分片          | 在获取第二个物理连接前拒绝，原游标可继续消费                            |
| 消费者异常                    | try-with-resources 关闭游标，事务内写入回滚                             |
| 无效分片键                    | 创建 JDBC 连接、Statement、ResultSet 之前拒绝                           |

测试通过 JDBC 包装器记录真实 Connection、PreparedStatement、ResultSet；每个用例结束查询这些实际对象的 isClosed，并断言路由和
Spring 事务资源清理。提前关闭游标只要求 ResultSet 关闭，REUSE 的 Statement 可以保留至事务结束。

H2 仅用于验证框架生命周期，不证明 MySQL Connector/J 使用服务器游标或逐批拉取。cacheEnabled 开关覆盖 CachingExecutor
包装，不代表游标结果缓存或二级缓存命中验证。嵌套映射、懒加载、驱动流式参数及大结果集内存上限仍需专项验收。

## 生产内核为什么不用改

LavShardExecutorInterceptor 已拦截 queryCursor。在物理查询执行时绑定路由上下文，获取正确连接；Mapper
返回时清理路由上下文。之后游标依赖的是已建立的 JDBC 资源，不需要在整个遍历期间保留路由 ThreadLocal。

Spring 事务维持 SqlSession 和连接生命周期。Cursor.close 关闭结果集；事务完成关闭 Session
时也关闭其登记的游标。不要为了支持遍历而扩大路由上下文作用域，或额外套一个静态 ThreadLocal。

## 业务文件一：OrderCursorMapper.java

以下业务示例未写入项目生产目录。请替换包名，并确保 Mapper 扫描及 LavShard 管理范围覆盖该包；t_order 分片规则应使用 user_id。

```java
package your.application.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.cursor.Cursor;

/** 单分片订单备注游标查询。 */
@Mapper
public interface OrderCursorMapper {

    /**
     * 查询指定用户的订单备注，由调用方在事务内消费并关闭。
     *
     * @param userId 非空分片键
     * @return 需要关闭的结果游标
     * @throws RuntimeException 路由或数据库查询失败时抛出
     */
    @Select("""
            SELECT note
            FROM t_order
            WHERE user_id = #{userId}
            ORDER BY id
            """)
    Cursor<String> selectNotes(@Param("userId") String userId);
}
```

此示例的 note 列应非空，与集成测试一致。查询包含等值分片键，ORDER BY 下推给目标数据库；实际表应评估包含 user_id 和 id 的合适索引。

## 业务文件二：OrderCursorService.java

```java
package your.application.service;

import your.application.mapper.OrderCursorMapper;
import org.apache.ibatis.cursor.Cursor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.function.Consumer;

/** 在同一服务事务中打开、消费并关闭游标。 */
@Service
public class OrderCursorService {
    private final OrderCursorMapper mapper;

    public OrderCursorService(OrderCursorMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    /**
     * 同步逐条消费备注，方法返回前释放游标。
     *
     * @param userId 非空分片键
     * @param consumer 同步消费者，不应保留游标或逐条发起额外数据库查询
     * @throws NullPointerException 参数为空时抛出
     * @throws UncheckedIOException 关闭游标失败时抛出
     * @throws RuntimeException 路由、读取或消费失败时抛出
     */
    @Transactional(readOnly = true)
    public void forEachNote(String userId, Consumer<String> consumer) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(consumer, "consumer must not be null");

        try (Cursor<String> cursor = mapper.selectNotes(userId)) {
            for (String note : cursor) {
                consumer.accept(note);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to close order cursor", exception);
        }
    }
}
```

## 逐段原理与边界

1. **事务在打开游标前开始**：通过 Spring 注入的 Service Bean 从外部调用此方法。手工 new 服务、自调用等未经过事务代理的路径不会获得这里期望的事务。
2. **消费在方法内完成**：不向 Controller 返回 Cursor，也不转成逃逸出事务作用域的 Stream。测试已经验证事务结束后的游标不可再遍历。
3. **try-with-resources**：正常结束、消费者异常、提前退出循环都应关闭 Cursor。若自行改成提前终止，仍需保留该资源作用域。
4. **同步消费者**：不要把 Cursor/Iterator 交给线程池继续遍历；事务资源和路由状态不会因此自动传播。不要在每一行消费时额外查询数据库造成
   N+1，也不要把本应由数据库完成的大量聚合搬到 Consumer 中。
5. **异常保留**：关闭 IOException 转为运行时异常；消费者自身的运行时异常继续传播，try-with-resources 会保留关闭异常作为
   suppressed。业务写事务的回滚行为由本轮异常路径测试验证。
6. **只读事务**：示例用于读取；如果修改为写入业务，不应继续沿用 readOnly=true。长时间消费会长时间占用连接，应用应限制工作量和消费耗时。

本轮没有添加 fetchSize 或 MySQL URL 参数；这些参数需要结合实际驱动版本、游标模式和负载进行验证，不能从 H2 的通过结果推断生产内存占用。

## 验证命令

```powershell
# 聚焦本轮
mvn -pl lavshard-test -am '-Dtest=MyBatisCursorLifecycleTest' '-Dsurefire.failIfNoSpecifiedTests=false' test

# 模块及依赖回归
mvn -pl lavshard-test -am test

# 全项目回归
mvn verify
```

## 提交信息

```text
test(mybatis): 完成游标消费与事务资源生命周期验证
```
