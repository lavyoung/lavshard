package io.github.lavyoung.lavshard.core.internal.route;

import io.github.lavyoung.lavshard.core.api.algorithm.ShardValue;
import io.github.lavyoung.lavshard.core.api.exception.ShardRuleNotFoundException;
import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import io.github.lavyoung.lavshard.core.api.route.RoutePlan;
import io.github.lavyoung.lavshard.core.api.route.RouteRequest;
import io.github.lavyoung.lavshard.core.api.route.ShardRouteDecision;
import io.github.lavyoung.lavshard.core.api.route.SqlRewriteResult;
import io.github.lavyoung.lavshard.core.api.rule.RuleSnapshot;
import io.github.lavyoung.lavshard.core.api.rule.TableRule;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.internal.binding.ShardValueBinder;
import io.github.lavyoung.lavshard.core.internal.sql.JSqlParserSingleTableUpdateAnalyzer;
import io.github.lavyoung.lavshard.core.internal.sql.JSqlParserSingleTableUpdateRewriter;
import io.github.lavyoung.lavshard.core.internal.sql.SqlAnalysis;
import io.github.lavyoung.lavshard.core.internal.sql.ValueReference;

import java.util.List;
import java.util.Objects;

/**
 * 从逻辑 UPDATE 和有序参数生成单节点物理路由计划。
 *
 * <p>除标准单分片路由流程外，该组件还负责阻止 SET 子句修改
 * 分片键，避免把一次 UPDATE 隐式变成跨分片数据迁移。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public final class SingleUpdateRoutePlanner {

    private final JSqlParserSingleTableUpdateAnalyzer analyzer =
            new JSqlParserSingleTableUpdateAnalyzer();

    private final SingleShardPredicateResolver resolver =
            new SingleShardPredicateResolver();

    private final ShardValueBinder valueBinder =
            new ShardValueBinder();

    private final RuleBasedShardRouter router;
    private final JSqlParserSingleTableUpdateRewriter rewriter;
    private final SingleRoutePlanAssembler assembler;

    public SingleUpdateRoutePlanner(
            RuleBasedShardRouter router,
            JSqlParserSingleTableUpdateRewriter rewriter,
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
     * 生成唯一物理节点的 UPDATE 计划。
     *
     * @param snapshot   不可变规则快照
     * @param sql        逻辑 UPDATE SQL
     * @param parameters 原始 JDBC 参数
     * @return 单节点路由计划
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

        validateShardingColumnNotUpdated(
                analysis,
                rule
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

    private static void validateShardingColumnNotUpdated(
            SqlAnalysis analysis,
            TableRule rule
    ) {
        boolean modifiesShardingColumn =
                analysis.updatedColumns()
                        .stream()
                        .anyMatch(column ->
                                column.equalsIgnoreCase(
                                        rule.shardingColumn()
                                )
                        );

        if (modifiesShardingColumn) {
            throw new UnsupportedSqlException(
                    "UPDATE must not modify sharding column: "
                            + rule.shardingColumn()
            );
        }
    }
}