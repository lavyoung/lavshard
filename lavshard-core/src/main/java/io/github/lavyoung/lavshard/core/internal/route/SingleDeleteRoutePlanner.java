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
import io.github.lavyoung.lavshard.core.internal.sql.JSqlParserSingleTableDeleteAnalyzer;
import io.github.lavyoung.lavshard.core.internal.sql.JSqlParserSingleTableDeleteRewriter;
import io.github.lavyoung.lavshard.core.internal.sql.SqlAnalysis;
import io.github.lavyoung.lavshard.core.internal.sql.ValueReference;

import java.util.List;
import java.util.Objects;

/**
 * 从逻辑 DELETE 和有序参数生成单节点物理路由计划。
 *
 * <p>该编排器依次执行 DELETE 安全分析、规则查找、唯一
 * 分片键解析、参数绑定、逻辑桶计算、拓扑映射、物理表
 * 改写和最终路由计划组装。</p>
 *
 * <p>不支持广播 DELETE。任何缺少安全分片键或可能引用
 * 多个物理节点的结构都会在数据库执行前失败。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public final class SingleDeleteRoutePlanner {

    private final JSqlParserSingleTableDeleteAnalyzer analyzer =
            new JSqlParserSingleTableDeleteAnalyzer();

    private final SingleShardPredicateResolver resolver =
            new SingleShardPredicateResolver();

    private final ShardValueBinder valueBinder =
            new ShardValueBinder();

    private final RuleBasedShardRouter router;
    private final JSqlParserSingleTableDeleteRewriter rewriter;
    private final SingleRoutePlanAssembler assembler;

    public SingleDeleteRoutePlanner(
            RuleBasedShardRouter router,
            JSqlParserSingleTableDeleteRewriter rewriter,
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
     * 为严格单表 DELETE 生成唯一物理执行计划。
     *
     * @param snapshot   本次路由使用的不可变规则快照
     * @param sql        逻辑 DELETE SQL
     * @param parameters 按原始 JDBC 顺序排列的参数
     * @return 只包含一个物理执行单元的路由计划
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

        ShardRouteDecision decision =
                router.route(
                        snapshot,
                        request
                );

        SqlRewriteResult rewriteResult =
                rewriter.rewrite(
                        sql,
                        logicalTable,
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