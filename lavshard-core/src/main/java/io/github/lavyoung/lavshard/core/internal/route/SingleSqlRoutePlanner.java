package io.github.lavyoung.lavshard.core.internal.route;

import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import io.github.lavyoung.lavshard.core.api.route.RoutePlan;
import io.github.lavyoung.lavshard.core.api.rule.RuleSnapshot;
import io.github.lavyoung.lavshard.core.internal.algorithm.ShardAlgorithmRegistry;
import io.github.lavyoung.lavshard.core.internal.sql.JSqlParserSingleRowInsertRewriter;
import io.github.lavyoung.lavshard.core.internal.sql.JSqlParserSingleTableDeleteRewriter;
import io.github.lavyoung.lavshard.core.internal.sql.JSqlParserSingleTableSelectRewriter;
import io.github.lavyoung.lavshard.core.internal.sql.JSqlParserSingleTableUpdateRewriter;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;

import java.util.List;
import java.util.Objects;

/**
 * v0.1 四类单分片 SQL 的统一路由入口。
 *
 * <p>该编排器负责识别 SQL 语句类型，并委派给对应的
 * SELECT、INSERT、UPDATE 或 DELETE 专用路由编排器。</p>
 *
 * <p>该类不执行数据库操作，不持有连接，也不重复实现
 * SQL 分析、参数绑定、分片计算和 SQL 改写逻辑。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public class SingleSqlRoutePlanner {

    private final SingleSelectRoutePlanner selectPlanner;
    private final SingleInsertRoutePlanner insertPlanner;
    private final SingleUpdateRoutePlanner updatePlanner;
    private final SingleDeleteRoutePlanner deletePlanner;


    public SingleSqlRoutePlanner(
            ShardAlgorithmRegistry algorithmRegistry
    ) {
        Objects.requireNonNull(
                algorithmRegistry,
                "algorithmRegistry must not be null"
        );

        RuleBasedShardRouter router = new RuleBasedShardRouter(new SingleShardRouter(algorithmRegistry));

        SingleRoutePlanAssembler assembler = new SingleRoutePlanAssembler();

        this.selectPlanner = new SingleSelectRoutePlanner(
                router,
                new JSqlParserSingleTableSelectRewriter(),
                assembler
        );

        this.insertPlanner =
                new SingleInsertRoutePlanner(
                        router,
                        new JSqlParserSingleRowInsertRewriter(),
                        assembler
                );

        this.updatePlanner =
                new SingleUpdateRoutePlanner(
                        router,
                        new JSqlParserSingleTableUpdateRewriter(),
                        assembler
                );

        this.deletePlanner =
                new SingleDeleteRoutePlanner(
                        router,
                        new JSqlParserSingleTableDeleteRewriter(),
                        assembler
                );
    }

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

        Statement statement = parse(sql);

        if (statement instanceof Select) {
            return selectPlanner.plan(snapshot, sql, parameters);
        }

        if (statement instanceof Insert) {
            return insertPlanner.plan(snapshot, sql, parameters);
        }

        if (statement instanceof Update) {
            return updatePlanner.plan(snapshot, sql, parameters);
        }

        if (statement instanceof Delete) {
            return deletePlanner.plan(snapshot, sql, parameters);
        }

        throw new UnsupportedSqlException(
                "v0.1 only supports SELECT, INSERT, UPDATE and DELETE"
        );
    }

    /**
     * 使用 JSQLParser 解析 SQL，用于识别语句的真实类型。
     * <p>
     * SingleSqlRoutePlanner：第一次解析，用于判断 SQL 类型
     * 专用 Planner：第二次解析，用于提取条件并完成安全校验
     * 后续优化
     *
     * @param sql 逻辑 SQL
     * @return JSQLParser 语句对象
     * @throws UnsupportedSqlException SQL 为空或解析失败时抛出
     */
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

}
