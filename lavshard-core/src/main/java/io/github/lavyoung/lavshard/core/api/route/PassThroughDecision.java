package io.github.lavyoung.lavshard.core.api.route;

/**
 * 普通 SQL 透传决策。
 *
 * <p>该决策表示 SQL 不属于分片表，但是其中引用的表已经被
 * 明确配置为普通表，因此可以保持原 SQL 在默认数据源执行。</p>
 *
 * @param dataSourceId 默认数据源标识
 * @param originalSql  保持不变的原始 SQL
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public record PassThroughDecision(
        String dataSourceId,
        String originalSql) implements SqlRouteDecision {

    public PassThroughDecision {
        if (dataSourceId == null
                || dataSourceId.isBlank()) {
            throw new IllegalArgumentException(
                    "dataSourceId must not be blank"
            );
        }

        if (originalSql == null
                || originalSql.isBlank()) {
            throw new IllegalArgumentException(
                    "originalSql must not be blank"
            );
        }
    }
}