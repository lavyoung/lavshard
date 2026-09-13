package io.github.lavyoung.lavshard.mybatis.internal.executor;

import io.github.lavyoung.lavshard.core.api.algorithm.ShardBucket;
import io.github.lavyoung.lavshard.core.api.route.*;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.api.topology.ShardNode;
import org.apache.ibatis.cache.CacheKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LavShard 路由维度的 MyBatis CacheKey 隔离契约。
 *
 * <p>物理 SQL 相同时，数据源、规则版本、拓扑版本或决策类型
 * 任一不同，都必须得到不同缓存键。</p>
 */
class MyBatisCacheKeyAugmenterTest {

    private final MyBatisCacheKeyAugmenter augmenter =
            new MyBatisCacheKeyAugmenter();

    @Test
    void shouldUseVersionedProtocolForManagedCacheKey() {
        // Given: the cache protocol is independent of Java package names.
        CacheKey expected = baseCacheKey();
        expected.updateAll(new Object[]{
                "lavshard:mybatis:route:v1", "MANAGED", "ds0", "rule-v1", "topology-v1"
        });

        // When
        CacheKey actual = augmenter.augment(
                baseCacheKey(), managed("ds0", "rule-v1", "topology-v1")
        );

        // Then
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void shouldUseVersionedProtocolForPassThroughCacheKey() {
        // Given
        CacheKey expected = baseCacheKey();
        expected.updateAll(new Object[]{"lavshard:mybatis:route:v1", "PASSTHROUGH", "ds0"});

        // When
        CacheKey actual = augmenter.augment(
                baseCacheKey(), new PassThroughDecision("ds0", "SELECT 1")
        );

        // Then
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void shouldLeaveCacheKeyUnchangedWhenDecisionIsNull() {
        // Given
        CacheKey actual = baseCacheKey();
        CacheKey expected = baseCacheKey();

        // When / Then
        assertThatThrownBy(() -> augmenter.augment(actual, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("decision must not be null");
        assertThat(actual).isEqualTo(expected);
        assertThat(actual.getUpdateCount()).isEqualTo(expected.getUpdateCount());
    }

    @Test
    void shouldReturnAndMutateProvidedCacheKey() {
        CacheKey cacheKey = baseCacheKey();

        CacheKey augmented = augmenter.augment(
                cacheKey,
                managed("ds0", "rule-v1", "topology-v1")
        );

        assertThat(augmented).isSameAs(cacheKey);
        assertThat(cacheKey.getUpdateCount())
                .isGreaterThan(3);
    }

    @Test
    void shouldProduceEqualKeysForSameManagedContext() {
        CacheKey first = augmenter.augment(
                baseCacheKey(),
                managed("ds0", "rule-v1", "topology-v1")
        );
        CacheKey second = augmenter.augment(
                baseCacheKey(),
                managed("ds0", "rule-v1", "topology-v1")
        );

        assertThat(first).isEqualTo(second);
    }

    @Test
    void shouldIsolateManagedKeysByDataSource() {
        CacheKey first = augmenter.augment(
                baseCacheKey(),
                managed("ds0", "rule-v1", "topology-v1")
        );
        CacheKey second = augmenter.augment(
                baseCacheKey(),
                managed("ds1", "rule-v1", "topology-v1")
        );

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void shouldIsolateManagedKeysByRuleVersion() {
        CacheKey first = augmenter.augment(
                baseCacheKey(),
                managed("ds0", "rule-v1", "topology-v1")
        );
        CacheKey second = augmenter.augment(
                baseCacheKey(),
                managed("ds0", "rule-v2", "topology-v1")
        );

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void shouldIsolateManagedKeysByTopologyVersion() {
        CacheKey first = augmenter.augment(
                baseCacheKey(),
                managed("ds0", "rule-v1", "topology-v1")
        );
        CacheKey second = augmenter.augment(
                baseCacheKey(),
                managed("ds0", "rule-v1", "topology-v2")
        );

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void shouldIsolatePassThroughKeysByDataSource() {
        CacheKey first = augmenter.augment(
                baseCacheKey(),
                new PassThroughDecision(
                        "ds0",
                        "SELECT 1"
                )
        );
        CacheKey second = augmenter.augment(
                baseCacheKey(),
                new PassThroughDecision(
                        "ds1",
                        "SELECT 1"
                )
        );

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void shouldIsolateManagedFromPassThroughDecision() {
        CacheKey managedKey = augmenter.augment(
                baseCacheKey(),
                managed("ds0", "rule-v1", "topology-v1")
        );
        CacheKey passThroughKey = augmenter.augment(
                baseCacheKey(),
                new PassThroughDecision(
                        "ds0",
                        "SELECT * FROM t_order_00"
                )
        );

        assertThat(managedKey)
                .isNotEqualTo(passThroughKey);
    }

    @Test
    void shouldRejectInvalidArguments() {
        assertThatThrownBy(() ->
                augmenter.augment(
                        null,
                        managed(
                                "ds0",
                                "rule-v1",
                                "topology-v1"
                        )
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("cacheKey must not be null");

        assertThatThrownBy(() ->
                augmenter.augment(baseCacheKey(), null)
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("decision must not be null");
    }

    private static CacheKey baseCacheKey() {
        return new CacheKey(new Object[]{
                "OrderMapper.select",
                "SELECT * FROM t_order_00 WHERE user_id = ?",
                "user-123"
        });
    }

    private static ManagedRouteDecision managed(
            String dataSourceId,
            String ruleVersion,
            String topologyVersion
    ) {
        ShardNode node = new ShardNode(
                "order-node",
                dataSourceId,
                new QualifiedTableName("t_order_00")
        );
        RouteUnit unit = new RouteUnit(
                new ShardTarget(
                        new ShardBucket(46),
                        node
                ),
                new SqlRewriteResult(
                        "SELECT * FROM t_order_00",
                        List.of()
                )
        );
        RoutePlan plan = new RoutePlan(
                RouteMode.SINGLE,
                ruleVersion,
                topologyVersion,
                List.of(unit)
        );

        return new ManagedRouteDecision(plan);
    }
}
