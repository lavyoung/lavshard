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
 * <p>该引擎首先判断 SQL 是否属于 LavShard 管理范围：</p>
 *
 * <ul>
 *     <li>命中分片规则时，委派给 SingleSqlRoutePlanner</li>
 *     <li>只引用普通表时，生成默认数据源透传决策</li>
 *     <li>引用未知表或者混合表时，由分类器立即拒绝</li>
 * </ul>
 *
 * <p>该类只负责流程编排，不执行数据库操作。</p>
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
                new JSqlParserStatementClassifier(
                        ordinaryTables
                );

        this.routePlanner =
                new SingleSqlRoutePlanner(
                        algorithmRegistry
                );

        this.defaultDataSourceId =
                defaultDataSourceId;
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
                classifier.classify(
                        snapshot,
                        sql
                );

        return switch (classification.type()) {
            case MANAGED -> new ManagedRouteDecision(
                    routePlanner.plan(
                            snapshot,
                            sql,
                            parameters
                    )
            );

            case PASSTHROUGH -> new PassThroughDecision(
                    defaultDataSourceId,
                    sql
            );
        };
    }
}