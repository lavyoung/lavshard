# 单行 INSERT 生成键：集成验证与业务使用

## 本轮结论

现有生产实现无需修改。新增 MyBatisGeneratedKeysRoutingTest，24 个集成用例首次执行全部通过。

本轮把“保留 KeyGenerator 元数据”的单元契约延伸为真实 MyBatis、Spring 和 JDBC 的主键回填验证。使用两个独立 H2 数据库及同名物理表；H2
的 identity 起点分别为 100 和 200，便于确认生成键来自正确数据库。

这不是 MySQL 驱动或方言验收，也不代表已经验证所有 MyBatis KeyGenerator。本轮仅覆盖 useGeneratedKeys 对应的 JDBC 生成键路径，未覆盖
SelectKeyGenerator、多行 INSERT、BATCH 或不可变 record 的生成键回填。

## 覆盖矩阵

下列六个场景分别组合 SIMPLE/REUSE 与 cacheEnabled=false/true，共 24 个执行用例。

| 场景                | 验证                                                         |
|---------------------|--------------------------------------------------------------|
| 两库独立插入        | 返回行数为 1，原 Map 收到各库真实 ID，按该 ID 能查回正确记录 |
| 命名 Map 与动态 SQL | bind 产生的 additional parameter 正确路由，row.id 写回原 Map |
| 同事务连续插入      | 同一连接执行相同 MappedStatement，两次主键不串写             |
| 显式回滚            | 数据库无记录，Java Map 仍保留已经回填的 ID                   |
| null 分片键         | 在物理连接获取前拒绝，不写入主键                             |
| 数据库约束失败      | 不写入主键，事务结束后下一事务可以访问另一物理库             |

cacheEnabled 开关覆盖 MyBatis CachingExecutor 包装是否存在；测试没有声明 Mapper 二级结果缓存，因此不声称验证了二级结果缓存的命中或失效。

每个用例结束验证路由上下文及事务资源清理，并关闭其 H2 内存数据库。

## 内核为什么不用修改

- MyBatisMappedStatementRewriter 已保留 keyGenerator、keyProperties、keyColumns。
- MyBatisBoundSqlRewriter 保留原参数对象及被引用的 additional parameters，生成键因此回填原对象而不是临时副本。
- Executor 拦截器在实际 INSERT 执行前完成物理 SQL 改写并绑定路由上下文。
- 路由 DataSource 为选中的物理库提供连接；物理 Statement 的 JDBC 生成键结果再交给 MyBatis 回填。

不要在 LavShard 中自己查询 MAX (id)、调用另一个数据源查询生成键，或手动覆盖 MyBatis 的标准回填结果。本轮已有实现直接通过集成契约，无需人为制造
Red 阶段。

## 业务 Mapper 完整示例

以下是应用层使用指导，并未写入用户的示例业务源码。将包名替换为你实际受管理的 Mapper 包；需要已有 t_order 分片规则、user_id
分片键和物理表自增 id 列。

```java
package your.application.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

import java.util.Map;

/** 单行订单插入；主键由物理数据库生成并回填。 */
public interface OrderMapper {

    /**
     * 插入订单，并将生成的主键写入 row 的 id 项。
     *
     * @param row 可变 Map，包含非空 userId 和 note
     * @return 受影响行数，成功单行插入应为 1
     * @throws RuntimeException 路由、数据库执行或生成键回填失败时抛出
     */
    @Insert("""
            INSERT INTO t_order (user_id, note)
            VALUES (#{row.userId}, #{row.note})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id", keyColumn = "id")
    int insert(@Param("row") Map<String, Object> row);
}
```

调用时构造 HashMap 等可变 Map，不能使用 Map.of。INSERT 列表不写 id，分片键必须在 SQL 执行前提供；id 不能同时作为需要由数据库生成的分片键。

```java
Map<String, Object> row = new HashMap<>();
row.put("userId", userId);
row.put("note", note);

int affectedRows = mapper.insert(row);
Object generatedId = row.get("id");
if (affectedRows != 1 || !(generatedId instanceof Number)) {
    throw new IllegalStateException("Expected one inserted row and a generated numeric key");
}
long id = ((Number) generatedId).longValue();
```

这是调用片段，不是额外生产类。实际业务应在服务事务边界内执行，并在事务最终提交成功后再确认业务成功。

### 参数名称必须一致

- 不带 Param 的单 Map 参数：SQL 为 #{userId}，keyProperty 为 id。
- 使用 Param ("row")：SQL 为 #{row.userId}，keyProperty 为 row.id。
- keyColumn 是真实数据库列名，不是 Java 属性路径。测试中 H2 未引用列名使用 ID；实际 MySQL 表应填写真实列名 id。

不要把 int 返回值当作生成主键，它是受影响行数。也不要把已回填 ID 当作已提交证明。不同分片可能生成相同 ID，因此本轮不提供全局
ID 唯一性承诺。

## 验证命令

项目根目录 PowerShell：

```powershell
# 聚焦本轮
mvn -pl lavshard-test -am '-Dtest=MyBatisGeneratedKeysRoutingTest' '-Dsurefire.failIfNoSpecifiedTests=false' test

# 集成模块及依赖
mvn -pl lavshard-test -am test

# 全项目回归
mvn verify
```

## 提交信息

```text
test(mybatis): 完成单行生成键回填与事务回滚集成验证
```

后续 MySQL Testcontainers 验收应复用这些关键断言，但不能据 H2 成功直接标记 MySQL 正式支持。
