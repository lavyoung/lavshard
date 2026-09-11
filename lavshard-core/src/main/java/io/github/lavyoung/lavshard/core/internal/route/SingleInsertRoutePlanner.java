package io.github.lavyoung.lavshard.core.internal.route;

import io.github.lavyoung.lavshard.core.api.algorithm.ShardValue;
import io.github.lavyoung.lavshard.core.api.exception.ShardRuleNotFoundException;
import io.github.lavyoung.lavshard.core.api.route.RoutePlan;
import io.github.lavyoung.lavshard.core.api.route.RouteRequest;
import io.github.lavyoung.lavshard.core.api.route.ShardRouteDecision;
import io.github.lavyoung.lavshard.core.api.route.SqlRewriteResult;
import io.github.lavyoung.lavshard.core.api.rule.RuleSnapshot;
import io.github.lavyoung.lavshard.core.api.rule.TableRule;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.internal.binding.ShardValueBinder;
import io.github.lavyoung.lavshard.core.internal.sql.JSqlParserSingleRowInsertAnalyzer;
import io.github.lavyoung.lavshard.core.internal.sql.JSqlParserSingleRowInsertRewriter;
import io.github.lavyoung.lavshard.core.internal.sql.SqlAnalysis;
import io.github.lavyoung.lavshard.core.internal.sql.ValueReference;

import java.util.List;
import java.util.Objects;

/**
 * 从逻辑单行 INSERT 和有序参数生成单节点物理路由计划。
 *
 * <p>该编排器依次执行 SQL 安全分析、规则查找、分片键解析、
 * 参数绑定、逻辑桶计算、物理节点映射和 INSERT 表名改写。</p>
 *
 * <p>所有失败均发生在数据库执行之前。该组件不持有数据库
 * 连接，也不依赖 MyBatis、Spring 或具体连接池。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public final class SingleInsertRoutePlanner {

    private final JSqlParserSingleRowInsertAnalyzer analyzer =
            new JSqlParserSingleRowInsertAnalyzer();

    private final SingleShardPredicateResolver resolver =
            new SingleShardPredicateResolver();

    private final ShardValueBinder valueBinder =
            new ShardValueBinder();

    private final RuleBasedShardRouter router;
    private final JSqlParserSingleRowInsertRewriter rewriter;
    private final SingleRoutePlanAssembler assembler;

    public SingleInsertRoutePlanner(
            RuleBasedShardRouter router,
            JSqlParserSingleRowInsertRewriter rewriter,
            SingleRoutePlanAssembler assembler
    ) {
        this.router = Objects.requireNonNull(
                router,
                "router must not be null"
        );
        this.rewriter = Objects.requireNonNull(
                rewriter,
                "rewriter must not be null"
        );
        this.assembler = Objects.requireNonNull(
                assembler,
                "assembler must not be null"
        );
    }

    /**
     * 为单行 INSERT 生成唯一物理执行计划。
     *
     * @param snapshot   本次路由使用的不可变规则快照
     * @param sql        逻辑 INSERT SQL
     * @param parameters 按原始 JDBC 顺序排列的参数
     * @return 只包含一个物理执行单元的路由计划
     * @throws NullPointerException snapshot 或 parameters 为空
     */
    public RoutePlan plan(
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

        SqlAnalysis analysis =
                analyzer.analyze(sql);

        QualifiedTableName logicalTable =
                analysis.tables().get(0);

        TableRule rule = snapshot.find(logicalTable)
                .orElseThrow(() ->
                        new ShardRuleNotFoundException(
                                "shard rule not found for SQL table: "
                                        + logicalTable
                        )
                );

        ValueReference reference =
                resolver.resolve(
                        analysis,
                        rule
                );

        ShardValue shardValue =
                valueBinder.bind(
                        reference,
                        parameters
                );

        RouteRequest request =
                new RouteRequest(
                        logicalTable,
                        shardValue
                );

        return createPlan(
                snapshot,
                request,
                sql
        );
    }

    private RoutePlan createPlan(
            RuleSnapshot snapshot,
            RouteRequest request,
            String sql
    ) {
        ShardRouteDecision decision =
                router.route(
                        snapshot,
                        request
                );

        SqlRewriteResult rewriteResult =
                rewriter.rewrite(
                        sql,
                        request.logicalTable(),
                        decision.target()
                                .node()
                                .actualTable()
                );

        return assembler.assemble(
                decision,
                rewriteResult
        );
    }
}