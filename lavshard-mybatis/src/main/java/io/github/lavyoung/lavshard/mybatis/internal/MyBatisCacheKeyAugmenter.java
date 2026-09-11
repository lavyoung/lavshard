package io.github.lavyoung.lavshard.mybatis.internal;

import io.github.lavyoung.lavshard.core.api.route.*;
import org.apache.ibatis.cache.CacheKey;

import java.util.Objects;

/**
 * 向 MyBatis CacheKey 追加 LavShard 路由维度。
 *
 * <p>基础 CacheKey 已经包含 Mapper ID、分页信息、物理 SQL
 * 和参数值。本组件继续追加数据源、规则版本和拓扑版本，
 * 防止不同物理路由之间发生缓存串读。</p>
 *
 * <p>该方法直接增强 MyBatis 创建的 CacheKey 并返回同一对象。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public final class MyBatisCacheKeyAugmenter {

    private static final String NAMESPACE = MyBatisCacheKeyAugmenter.class.getName();

    private static final String MANAGED_MARKER = "MANAGED";

    private static final String PASSTHROUGH_MARKER = "PASSTHROUGH";

    /**
     * 将路由上下文追加到 MyBatis CacheKey。
     *
     * @param cacheKey MyBatis 根据物理 SQL 创建的基础缓存键
     * @param decision 当前 SQL 的最终路由决策
     * @return 传入并完成增强的同一个 CacheKey
     * @throws NullPointerException 任一参数为空时抛出
     */
    public CacheKey augment(CacheKey cacheKey, SqlRouteDecision decision) {
        Objects.requireNonNull(cacheKey, "cacheKey must not be null");
        Objects.requireNonNull(decision, "decision must not be null");

        cacheKey.update(NAMESPACE);

        if (decision instanceof ManagedRouteDecision managed) {
            appendManaged(cacheKey, managed);
            return cacheKey;
        }

        appendPassThrough(cacheKey, (PassThroughDecision) decision);
        return cacheKey;
    }

    private static void appendManaged(CacheKey cacheKey, ManagedRouteDecision decision) {
        RoutePlan plan = decision.routePlan();
        RouteUnit unit = plan.units().get(0);

        cacheKey.update(MANAGED_MARKER);
        cacheKey.update(unit.target().node().dataSourceId());
        cacheKey.update(plan.ruleVersion());
        cacheKey.update(plan.topologyVersion());
    }

    private static void appendPassThrough(CacheKey cacheKey, PassThroughDecision decision) {
        cacheKey.update(PASSTHROUGH_MARKER);
        cacheKey.update(decision.dataSourceId());
    }
}
