# `SELECT ... FOR UPDATE` 事务守卫实现指南

## 1. 闭环目标

本轮将锁定读的事务要求从 SQL 分类阶段一直传递到 Spring 执行守卫：

```text
JSQLParser AST
    -> SqlClassification.transactionRequirement
    -> SqlRouteDecision.transactionRequirement
    -> SpringShardContext 执行前校验
```

`core` 只描述执行要求，不感知 Spring。`starter` 负责判断本地事务是否真实活动。受管表和普通表透传使用同一套契约。旧的两参数/单参数决策构造器继续保留，并默认
`NONE`，避免现有调用方一次性迁移。

## 2. 新增 `TransactionRequirement.java`

路径：`lavshard-core/src/main/java/io/github/lavyoung/lavshard/core/api/route/TransactionRequirement.java`

```java
package io.github.lavyoung.lavshard.core.api.route;

/**
 * SQL 路由决策对本地事务的最低要求。
 *
 * <p>该模型属于与框架无关的执行约束。Core 负责识别要求，
 * Spring 等适配器负责根据各自事务设施执行校验。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/13
 */
public enum TransactionRequirement {

    /** SQL 可以在事务内或事务外执行。 */
    NONE,

    /** SQL 必须在活动的本地事务中执行。 */
    REQUIRED
}
```

## 3. 完整替换 `SqlRouteDecision.java`

路径：`lavshard-core/src/main/java/io/github/lavyoung/lavshard/core/api/route/SqlRouteDecision.java`

```java
package io.github.lavyoung.lavshard.core.api.route;

/**
 * SQL 路由决策。
 *
 * <p>一个 SQL 经过管理边界分类后，只能产生两种结果：</p>
 *
 * <ul>
 *     <li>受管 SQL：进入分片路由，生成严格的 RoutePlan</li>
 *     <li>透传 SQL：保持原始 SQL，在默认数据源执行</li>
 * </ul>
 *
 * <p>决策同时携带与框架无关的事务要求，执行适配器必须在访问
 * 真实数据源之前完成校验。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public sealed interface SqlRouteDecision
        permits ManagedRouteDecision, PassThroughDecision {

    /**
     * 返回当前 SQL 的本地事务要求。
     *
     * @return 非空事务要求
     */
    TransactionRequirement transactionRequirement();
}
```

## 4. 完整替换 `ManagedRouteDecision.java`

路径：`lavshard-core/src/main/java/io/github/lavyoung/lavshard/core/api/route/ManagedRouteDecision.java`

```java
package io.github.lavyoung.lavshard.core.api.route;

import java.util.Objects;

/**
 * 受 LavShard 管理的 SQL 路由决策。
 *
 * <p>该决策表示 SQL 已命中分片规则，并且已经完成分片计算、
 * 物理节点选择和 SQL 改写。</p>
 *
 * @param routePlan             完整的分片路由计划
 * @param transactionRequirement 当前 SQL 的本地事务要求
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public record ManagedRouteDecision(
        RoutePlan routePlan,
        TransactionRequirement transactionRequirement
) implements SqlRouteDecision {

    public ManagedRouteDecision {
        Objects.requireNonNull(
                routePlan,
                "routePlan must not be null"
        );
        Objects.requireNonNull(
                transactionRequirement,
                "transactionRequirement must not be null"
        );
    }

    /**
     * 创建无强制事务要求的受管路由决策。
     *
     * @param routePlan 完整的分片路由计划
     */
    public ManagedRouteDecision(RoutePlan routePlan) {
        this(routePlan, TransactionRequirement.NONE);
    }
}
```

## 5. 完整替换 `PassThroughDecision.java`

路径：`lavshard-core/src/main/java/io/github/lavyoung/lavshard/core/api/route/PassThroughDecision.java`

```java
package io.github.lavyoung.lavshard.core.api.route;

import java.util.Objects;

/**
 * 普通 SQL 透传决策。
 *
 * <p>该决策表示 SQL 不属于分片表，但是其中引用的表已经被
 * 明确配置为普通表，因此可以保持原 SQL 在默认数据源执行。</p>
 *
 * @param dataSourceId          默认数据源标识
 * @param originalSql           保持不变的原始 SQL
 * @param transactionRequirement 当前 SQL 的本地事务要求
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public record PassThroughDecision(
        String dataSourceId,
        String originalSql,
        TransactionRequirement transactionRequirement
) implements SqlRouteDecision {

    public PassThroughDecision {
        if (dataSourceId == null || dataSourceId.isBlank()) {
            throw new IllegalArgumentException(
                    "dataSourceId must not be blank"
            );
        }
        if (originalSql == null || originalSql.isBlank()) {
            throw new IllegalArgumentException(
                    "originalSql must not be blank"
            );
        }
        Objects.requireNonNull(
                transactionRequirement,
                "transactionRequirement must not be null"
        );
    }

    /**
     * 创建无强制事务要求的透传决策。
     *
     * @param dataSourceId 默认数据源标识
     * @param originalSql  保持不变的原始 SQL
     */
    public PassThroughDecision(
            String dataSourceId,
            String originalSql
    ) {
        this(
                dataSourceId,
                originalSql,
                TransactionRequirement.NONE
        );
    }
}
```

## 6. 完整替换 `SqlClassification.java`

路径：`lavshard-core/src/main/java/io/github/lavyoung/lavshard/core/internal/sql/SqlClassification.java`

```java
package io.github.lavyoung.lavshard.core.internal.sql;

import io.github.lavyoung.lavshard.core.api.route.TransactionRequirement;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;

import java.util.List;
import java.util.Objects;

/**
 * SQL 管理边界分类结果。
 *
 * @param type                   SQL 分类
 * @param tables                 SQL 引用的去重表集合
 * @param transactionRequirement SQL 的本地事务要求
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public record SqlClassification(
        SqlClassificationType type,
        List<QualifiedTableName> tables,
        TransactionRequirement transactionRequirement
) {

    public SqlClassification {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(tables, "tables must not be null");
        Objects.requireNonNull(
                transactionRequirement,
                "transactionRequirement must not be null"
        );
        for (QualifiedTableName table : tables) {
            Objects.requireNonNull(table, "table must not be null");
        }
        tables = List.copyOf(tables);
    }

    /**
     * 创建无强制事务要求的分类结果。
     *
     * @param type   SQL 分类
     * @param tables SQL 引用的去重表集合
     */
    public SqlClassification(
            SqlClassificationType type,
            List<QualifiedTableName> tables
    ) {
        this(type, tables, TransactionRequirement.NONE);
    }
}
```

## 7. 完整替换 `JSqlParserStatementClassifier.java`

路径：`lavshard-core/src/main/java/io/github/lavyoung/lavshard/core/internal/sql/JSqlParserStatementClassifier.java`

```java
package io.github.lavyoung.lavshard.core.internal.sql;

import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import io.github.lavyoung.lavshard.core.api.route.TransactionRequirement;
import io.github.lavyoung.lavshard.core.api.rule.RuleSnapshot;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.ForMode;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 基于 JSQLParser 的 SQL 管理边界分类器。
 *
 * <p>分类器识别 SQL 引用的表、管理边界以及与执行相关的最低
 * 事务要求。它不负责参数绑定、分片计算、SQL 改写或数据库访问。</p>
 *
 * <p>无法安全分类的 SQL 必须在访问数据库之前失败，不能因为
 * 没找到分片规则就自动降级到默认数据源。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public final class JSqlParserStatementClassifier {

    private final Set<QualifiedTableName> ordinaryTables;

    /**
     * 创建 SQL 分类器。
     *
     * @param ordinaryTables 明确允许透传的普通表
     * @throws NullPointerException 集合或其中的表为空时抛出
     */
    public JSqlParserStatementClassifier(
            Set<QualifiedTableName> ordinaryTables
    ) {
        Objects.requireNonNull(
                ordinaryTables,
                "ordinaryTables must not be null"
        );
        for (QualifiedTableName table : ordinaryTables) {
            Objects.requireNonNull(
                    table,
                    "ordinary table must not be null"
            );
        }
        this.ordinaryTables = Set.copyOf(ordinaryTables);
    }

    /**
     * 根据规则快照和普通表允许列表对 SQL 分类。
     *
     * @param snapshot 当前不可变分片规则快照
     * @param sql      待分类 SQL
     * @return MANAGED 或 PASSTHROUGH 分类结果
     * @throws NullPointerException    snapshot 为空时抛出
     * @throws UnsupportedSqlException SQL 无法安全分类时抛出
     */
    public SqlClassification classify(
            RuleSnapshot snapshot,
            String sql
    ) {
        Objects.requireNonNull(
                snapshot,
                "snapshot must not be null"
        );

        Statement statement = parse(sql);
        validateStatementType(statement);
        TransactionRequirement transactionRequirement =
                transactionRequirement(statement);
        List<QualifiedTableName> tables = collectTables(statement);

        if (tables.isEmpty()) {
            return classifyTablelessStatement(
                    statement,
                    transactionRequirement
            );
        }

        List<QualifiedTableName> unknownTables =
                findUnknownTables(snapshot, tables);
        if (!unknownTables.isEmpty()) {
            throw new UnsupportedSqlException(
                    "table is not configured as managed or ordinary: "
                            + unknownTables.get(0)
            );
        }

        long managedTableCount = tables.stream()
                .filter(table -> snapshot.find(table).isPresent())
                .count();
        long ordinaryTableCount = tables.stream()
                .filter(ordinaryTables::contains)
                .count();

        if (managedTableCount > 0 && ordinaryTableCount > 0) {
            throw new UnsupportedSqlException(
                    "managed and ordinary tables must not be mixed"
            );
        }
        if (managedTableCount > 1) {
            throw new UnsupportedSqlException(
                    "v0.1 only supports one managed table per SQL"
            );
        }
        if (managedTableCount == 1) {
            return new SqlClassification(
                    SqlClassificationType.MANAGED,
                    tables,
                    transactionRequirement
            );
        }
        return new SqlClassification(
                SqlClassificationType.PASSTHROUGH,
                tables,
                transactionRequirement
        );
    }

    private static Statement parse(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new UnsupportedSqlException(
                    "sql must not be blank"
            );
        }
        try {
            return CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException exception) {
            throw new UnsupportedSqlException(
                    "failed to parse SQL",
                    exception
            );
        }
    }

    private static void validateStatementType(Statement statement) {
        boolean supported = statement instanceof Select
                || statement instanceof Insert
                || statement instanceof Update
                || statement instanceof Delete;
        if (!supported) {
            throw new UnsupportedSqlException(
                    "v0.1 only supports SELECT, INSERT, UPDATE and DELETE"
            );
        }
    }

    /**
     * 从顶层 SELECT AST 提取锁定读要求。
     *
     * <p>v0.1 只开放 MySQL {@code FOR UPDATE}。其他锁模式不能按
     * 普通 SELECT 执行，否则会绕过能力矩阵和事务约束。</p>
     *
     * @param statement 已解析并完成类型校验的语句
     * @return REQUIRED 或 NONE
     * @throws UnsupportedSqlException 锁模式不在 v0.1 支持范围时抛出
     */
    private static TransactionRequirement transactionRequirement(
            Statement statement
    ) {
        if (!(statement instanceof PlainSelect select)
                || select.getForMode() == null) {
            return TransactionRequirement.NONE;
        }
        if (select.getForMode() != ForMode.UPDATE) {
            throw new UnsupportedSqlException(
                    "v0.1 only supports FOR UPDATE locking reads"
            );
        }
        return TransactionRequirement.REQUIRED;
    }

    private static List<QualifiedTableName> collectTables(
            Statement statement
    ) {
        return new TableCollector().collect(statement);
    }

    private List<QualifiedTableName> findUnknownTables(
            RuleSnapshot snapshot,
            List<QualifiedTableName> tables
    ) {
        return tables.stream()
                .filter(table -> snapshot.find(table).isEmpty())
                .filter(table -> !ordinaryTables.contains(table))
                .toList();
    }

    private static SqlClassification classifyTablelessStatement(
            Statement statement,
            TransactionRequirement transactionRequirement
    ) {
        if (statement instanceof Select) {
            return new SqlClassification(
                    SqlClassificationType.PASSTHROUGH,
                    List.of(),
                    transactionRequirement
            );
        }
        throw new UnsupportedSqlException(
                "DML statement must reference a table"
        );
    }

    /** 收集 SQL AST 中真实出现的物理表节点。 */
    private static final class TableCollector extends TablesNamesFinder {

        private final Set<QualifiedTableName> tables =
                new LinkedHashSet<>();

        private List<QualifiedTableName> collect(Statement statement) {
            getTables(statement);
            return List.copyOf(tables);
        }

        @Override
        public void visit(Table table) {
            tables.add(JSqlParserTableNameMapper.from(table));
            super.visit(table);
        }
    }
}
```

## 8. 完整替换 `SqlRouteEngine.java`

路径：`lavshard-core/src/main/java/io/github/lavyoung/lavshard/core/internal/route/SqlRouteEngine.java`

```java
package io.github.lavyoung.lavshard.core.internal.route;

import io.github.lavyoung.lavshard.core.api.route.ManagedRouteDecision;
import io.github.lavyoung.lavshard.core.api.route.PassThroughDecision;
import io.github.lavyoung.lavshard.core.api.route.SqlRouteDecision;
import io.github.lavyoung.lavshard.core.api.rule.RuleSnapshot;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.internal.algorithm.ShardAlgorithmRegistry;
import io.github.lavyoung.lavshard.core.internal.sql.JSqlParserStatementClassifier;
import io.github.lavyoung.lavshard.core.internal.sql.SqlClassification;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * SQL 分类与分片路由的统一决策入口。
 *
 * <p>该引擎完成管理边界分类，并把分类阶段识别出的事务要求
 * 原样传播到最终路由决策。它不执行数据库操作。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public final class SqlRouteEngine {

    private final JSqlParserStatementClassifier classifier;
    private final SingleSqlRoutePlanner routePlanner;
    private final String defaultDataSourceId;

    /**
     * 创建统一 SQL 路由引擎。
     *
     * @param algorithmRegistry   分片算法注册表
     * @param ordinaryTables      明确允许透传的普通表
     * @param defaultDataSourceId 普通 SQL 使用的默认数据源
     * @throws NullPointerException     注册表或普通表集合为空时抛出
     * @throws IllegalArgumentException 默认数据源标识为空白时抛出
     */
    public SqlRouteEngine(
            ShardAlgorithmRegistry algorithmRegistry,
            Set<QualifiedTableName> ordinaryTables,
            String defaultDataSourceId
    ) {
        Objects.requireNonNull(
                algorithmRegistry,
                "algorithmRegistry must not be null"
        );
        Objects.requireNonNull(
                ordinaryTables,
                "ordinaryTables must not be null"
        );
        if (defaultDataSourceId == null
                || defaultDataSourceId.isBlank()) {
            throw new IllegalArgumentException(
                    "defaultDataSourceId must not be blank"
            );
        }
        this.classifier =
                new JSqlParserStatementClassifier(ordinaryTables);
        this.routePlanner =
                new SingleSqlRoutePlanner(algorithmRegistry);
        this.defaultDataSourceId = defaultDataSourceId;
    }

    /**
     * 对 SQL 进行分类并生成最终路由决策。
     *
     * @param snapshot   当前不可变规则快照
     * @param sql        原始逻辑 SQL
     * @param parameters JDBC 参数列表
     * @return 分片路由决策或者普通 SQL 透传决策
     * @throws NullPointerException snapshot 或 parameters 为空时抛出
     */
    public SqlRouteDecision decide(
            RuleSnapshot snapshot,
            String sql,
            List<?> parameters
    ) {
        Objects.requireNonNull(
                snapshot,
                "snapshot must not be null"
        );
        Objects.requireNonNull(
                parameters,
                "parameters must not be null"
        );

        SqlClassification classification =
                classifier.classify(snapshot, sql);

        return switch (classification.type()) {
            case MANAGED -> new ManagedRouteDecision(
                    routePlanner.plan(snapshot, sql, parameters),
                    classification.transactionRequirement()
            );
            case PASSTHROUGH -> new PassThroughDecision(
                    defaultDataSourceId,
                    sql,
                    classification.transactionRequirement()
            );
        };
    }
}
```

## 9. 完整替换 `LavShardErrorCode.java`

路径：`lavshard-core/src/main/java/io/github/lavyoung/lavshard/core/api/exception/LavShardErrorCode.java`

```java
package io.github.lavyoung.lavshard.core.api.exception;

/**
 * LavShard 对外公开的稳定错误码。
 *
 * <p>错误码一旦发布，只允许新增，不允许修改既有编号的含义。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public enum LavShardErrorCode {

    CONFIGURATION_INVALID("LAVSHARD-CORE-1001"),

    SQL_UNSUPPORTED("LAVSHARD-CORE-2001"),
    SHARD_KEY_MISSING("LAVSHARD-CORE-2002"),
    PARAMETER_BINDING_FAILED("LAVSHARD-CORE-2003"),

    SHARD_ALGORITHM_FAILED("LAVSHARD-CORE-3001"),

    SHARD_RULE_NOT_FOUND("LAVSHARD-CORE-4001"),
    ROUTE_NOT_FOUND("LAVSHARD-CORE-4002"),

    TRANSACTION_ROUTE_CONFLICT("LAVSHARD-CORE-5001"),
    TRANSACTION_REQUIRED("LAVSHARD-CORE-5002");

    private final String code;

    LavShardErrorCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
```

## 10. 新增 `TransactionRequiredException.java`

路径：`lavshard-core/src/main/java/io/github/lavyoung/lavshard/core/api/exception/TransactionRequiredException.java`

```java
package io.github.lavyoung.lavshard.core.api.exception;

/**
 * SQL 要求本地事务但当前没有活动事务时抛出。
 *
 * <p>该异常必须在获取真实物理连接之前抛出。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/13
 */
public class TransactionRequiredException extends LavShardException {

    /**
     * 创建事务缺失异常。
     *
     * @param message 可读错误信息
     */
    public TransactionRequiredException(String message) {
        super(LavShardErrorCode.TRANSACTION_REQUIRED, message);
    }

    /**
     * 创建包含原始原因的事务缺失异常。
     *
     * @param message 可读错误信息
     * @param cause   原始异常
     */
    public TransactionRequiredException(
            String message,
            Throwable cause
    ) {
        super(LavShardErrorCode.TRANSACTION_REQUIRED, message, cause);
    }
}
```

## 11. 完整替换 `SpringShardContext.java`

路径：
`lavshard-spring-boot-starter/src/main/java/io/github/lavyoung/lavshard/starter/internal/transaction/SpringShardContext.java`

```java
package io.github.lavyoung.lavshard.starter.internal.transaction;

import io.github.lavyoung.lavshard.core.api.exception.CrossShardTransactionException;
import io.github.lavyoung.lavshard.core.api.exception.TransactionRequiredException;
import io.github.lavyoung.lavshard.core.api.route.ManagedRouteDecision;
import io.github.lavyoung.lavshard.core.api.route.PassThroughDecision;
import io.github.lavyoung.lavshard.core.api.route.SqlRouteDecision;
import io.github.lavyoung.lavshard.core.api.route.TransactionRequirement;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Objects;

/**
 * Spring 本地事务的 LavShard 路由绑定与冲突守卫。
 *
 * <p>事务第一次路由时绑定物理数据源。第一次 Managed 路由还会
 * 固定规则版本和拓扑版本。后续不兼容路由在 Executor 访问数据库
 * 前抛出 {@link CrossShardTransactionException}。</p>
 *
 * <p>事务绑定存储在 Spring TransactionSynchronizationManager，
 * 并通过事务完成回调清理，不要求调用方手工清除。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public final class SpringShardContext {

    private final Object transactionResourceKey = new Object();

    /**
     * 校验并绑定当前 SQL 路由。
     *
     * <p>先执行事务存在性约束，再处理事务内的数据源和版本绑定。
     * 所有失败都发生在 Executor 获取真实物理连接之前。</p>
     *
     * @param decision 当前 SQL 路由决策
     * @throws NullPointerException           decision 为空时抛出
     * @throws TransactionRequiredException   SQL 要求事务但当前无活动事务时抛出
     * @throws IllegalStateException          已标记事务活动但 Spring 事务同步
     *                                        尚未激活时抛出
     * @throws CrossShardTransactionException 当前路由与事务绑定冲突时抛出
     */
    public void validate(SqlRouteDecision decision) {
        Objects.requireNonNull(decision, "decision must not be null");
        validateTransactionPresence(decision);

        if (!TransactionSynchronizationManager
                .isActualTransactionActive()) {
            return;
        }
        if (!TransactionSynchronizationManager
                .isSynchronizationActive()) {
            throw new IllegalStateException(
                    "Transaction synchronization is not active"
            );
        }

        TransactionRoute route = transactionRoute(decision);
        Object resource = TransactionSynchronizationManager
                .getResource(transactionResourceKey);

        if (resource == null) {
            bindFirstRoute(route);
            return;
        }
        if (!(resource instanceof TransactionRouteState state)) {
            throw new IllegalStateException(
                    "Unexpected transaction route resource: "
                            + resource.getClass().getName()
            );
        }
        state.validate(route);
    }

    /**
     * 校验 SQL 所声明的最低事务要求。
     *
     * @param decision 当前 SQL 路由决策
     * @throws TransactionRequiredException SQL 要求事务但当前无活动事务时抛出
     */
    private static void validateTransactionPresence(
            SqlRouteDecision decision
    ) {
        boolean transactionRequired =
                decision.transactionRequirement()
                        == TransactionRequirement.REQUIRED;
        boolean transactionActive =
                TransactionSynchronizationManager
                        .isActualTransactionActive();

        if (transactionRequired && !transactionActive) {
            throw new TransactionRequiredException(
                    "SQL requires an active local transaction"
            );
        }
    }

    /**
     * 建立事务第一次路由的绑定并注册清理回调。
     *
     * @param route 第一次事务路由
     */
    private void bindFirstRoute(TransactionRoute route) {
        TransactionRouteState state = new TransactionRouteState(route);
        TransactionSynchronizationManager.bindResource(
                transactionResourceKey,
                state
        );

        try {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {

                        @Override
                        public void suspend() {
                            TransactionSynchronizationManager
                                    .unbindResource(transactionResourceKey);
                        }

                        @Override
                        public void resume() {
                            TransactionSynchronizationManager.bindResource(
                                    transactionResourceKey,
                                    state
                            );
                        }

                        @Override
                        public void afterCompletion(int status) {
                            TransactionSynchronizationManager
                                    .unbindResourceIfPossible(
                                            transactionResourceKey
                                    );
                        }
                    }
            );
        } catch (RuntimeException exception) {
            TransactionSynchronizationManager.unbindResourceIfPossible(
                    transactionResourceKey
            );
            throw exception;
        }
    }

    /**
     * 将 Core 路由决策转换为事务校验所需的最小坐标。
     *
     * @param decision Core 路由决策
     * @return Managed 或 PassThrough 事务路由
     */
    private static TransactionRoute transactionRoute(
            SqlRouteDecision decision
    ) {
        if (decision instanceof ManagedRouteDecision managed) {
            String dataSourceId = managed.routePlan()
                    .units()
                    .get(0)
                    .target()
                    .node()
                    .dataSourceId();
            return new ManagedTransactionRoute(
                    dataSourceId,
                    managed.routePlan().ruleVersion(),
                    managed.routePlan().topologyVersion()
            );
        }
        if (decision instanceof PassThroughDecision passThrough) {
            return new PassThroughTransactionRoute(
                    passThrough.dataSourceId()
            );
        }
        throw new IllegalArgumentException(
                "Unsupported route decision type: "
                        + decision.getClass().getName()
        );
    }

    private sealed interface TransactionRoute
            permits ManagedTransactionRoute,
            PassThroughTransactionRoute {

        String dataSourceId();
    }

    private record ManagedTransactionRoute(
            String dataSourceId,
            String ruleVersion,
            String topologyVersion
    ) implements TransactionRoute {
    }

    private record PassThroughTransactionRoute(
            String dataSourceId
    ) implements TransactionRoute {
    }

    private record ManagedRouteVersion(
            String ruleVersion,
            String topologyVersion
    ) {
    }

    /** 当前 Spring 事务已经固定的路由状态。 */
    private static final class TransactionRouteState {

        private final String dataSourceId;
        private List<ManagedRouteVersion> managedVersions;

        private TransactionRouteState(TransactionRoute initialRoute) {
            dataSourceId = initialRoute.dataSourceId();
            if (initialRoute instanceof ManagedTransactionRoute managed) {
                managedVersions = List.of(versionOf(managed));
            } else {
                managedVersions = List.of();
            }
        }

        /**
         * 校验后续路由并在必要时补充首次 Managed 版本。
         *
         * @param route 后续事务路由
         * @throws CrossShardTransactionException 数据源或版本冲突时抛出
         */
        private void validate(TransactionRoute route) {
            validateDataSource(route);
            if (!(route instanceof ManagedTransactionRoute managed)) {
                return;
            }

            ManagedRouteVersion incoming = versionOf(managed);
            if (managedVersions.isEmpty()) {
                managedVersions = List.of(incoming);
            }
            ManagedRouteVersion bound = managedVersions.get(0);
            if (!bound.equals(incoming)) {
                throw new CrossShardTransactionException(
                        "Transaction is already bound to ruleVersion "
                                + bound.ruleVersion()
                                + " and topologyVersion "
                                + bound.topologyVersion()
                                + " but attempted ruleVersion "
                                + incoming.ruleVersion()
                                + " and topologyVersion "
                                + incoming.topologyVersion()
                );
            }
        }

        private void validateDataSource(TransactionRoute route) {
            if (dataSourceId.equals(route.dataSourceId())) {
                return;
            }
            throw new CrossShardTransactionException(
                    "Transaction is already bound to dataSourceId "
                            + dataSourceId
                            + " and cannot route to "
                            + route.dataSourceId()
            );
        }

        private static ManagedRouteVersion versionOf(
                ManagedTransactionRoute managed
        ) {
            return new ManagedRouteVersion(
                    managed.ruleVersion(),
                    managed.topologyVersion()
            );
        }
    }
}
```

这里不改 `LavShardExecutorInterceptor`。现有拦截器已经在调用真实 Executor 前进入 `MyBatisRouteContext`，而
`MyBatisRouteContext` 已调用 `SpringShardContext.validate`；继续复用这条链路即可保证错误发生在真实连接之前。

## 12. 实现顺序

1. 先新增枚举和异常。
2. 再替换两个决策 record 与 `SqlRouteDecision`。
3. 扩展 `SqlClassification`，保留旧构造器。
4. 替换分类器和路由引擎。
5. 最后修改 Spring 事务守卫。
6. 按本轮交付说明执行聚焦、模块和全项目测试。
