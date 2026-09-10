package io.github.lavyoung.lavshard.core.internal.route;

import io.github.lavyoung.lavshard.core.api.route.RoutePlan;
import io.github.lavyoung.lavshard.core.api.route.RouteRequest;
import io.github.lavyoung.lavshard.core.api.route.ShardRouteDecision;
import io.github.lavyoung.lavshard.core.api.route.SqlRewriteResult;
import io.github.lavyoung.lavshard.core.api.rule.RuleSnapshot;
import io.github.lavyoung.lavshard.core.internal.sql.JSqlParserSingleTableSelectRewriter;

import java.util.Objects;

/**
 *
 * 从逻辑 SELECT 和已绑定分片值生成最终单节点 RoutePlan。
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 1.0.0
 * @date 2026/09/10
 */
public final class SingleSelectRoutePlanner {

    private final RuleBasedShardRouter router;
    private final JSqlParserSingleTableSelectRewriter rewriter;
    private final SingleRoutePlanAssembler assembler;

    public SingleSelectRoutePlanner(
            RuleBasedShardRouter router,
            JSqlParserSingleTableSelectRewriter rewriter,
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


    public RoutePlan plan(RuleSnapshot snapshot, RouteRequest request, String sql) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        ShardRouteDecision decision =
                router.route(snapshot, request);

        SqlRewriteResult rewrittenSql = rewriter.rewrite(
                sql,
                request.logicalTable(),
                decision.target().node().actualTable()
        );

        return assembler.assemble(
                decision,
                rewrittenSql
        );
    }

}
