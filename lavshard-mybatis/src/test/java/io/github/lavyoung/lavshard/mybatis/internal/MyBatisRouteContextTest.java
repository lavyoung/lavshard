package io.github.lavyoung.lavshard.mybatis.internal;

import io.github.lavyoung.lavshard.core.api.route.PassThroughDecision;
import io.github.lavyoung.lavshard.core.api.route.SqlRouteDecision;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MyBatis 当前线程路由作用域契约。
 *
 * <p>作用域必须支持嵌套并严格恢复外层决策，最外层关闭后必须
 * 清理 ThreadLocal，避免线程池复用导致请求间串库。</p>
 */
class MyBatisRouteContextTest {

    private final MyBatisRouteContext context =
            new MyBatisRouteContext();

    @Test
    void shouldBeEmptyBeforeOpeningScope() {
        assertThat(context.currentDecision()).isEmpty();
    }

    @Test
    void shouldExposeDecisionOnlyInsideScope() {
        SqlRouteDecision decision = decision("ds0");

        try (MyBatisRouteContext.Scope ignored =
                     context.open(decision)) {
            assertThat(context.currentDecision())
                    .get()
                    .isSameAs(decision);
        }

        assertThat(context.currentDecision()).isEmpty();
    }

    @Test
    void shouldRestoreOuterDecisionAfterNestedScope() {
        SqlRouteDecision outer = decision("ds0");
        SqlRouteDecision inner = decision("ds1");

        try (MyBatisRouteContext.Scope ignoredOuter =
                     context.open(outer)) {
            try (MyBatisRouteContext.Scope ignoredInner =
                         context.open(inner)) {
                assertThat(context.currentDecision())
                        .get()
                        .isSameAs(inner);
            }

            assertThat(context.currentDecision())
                    .get()
                    .isSameAs(outer);
        }

        assertThat(context.currentDecision()).isEmpty();
    }

    @Test
    void shouldRejectNullDecision() {
        assertThatThrownBy(() -> context.open(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("decision must not be null");
    }

    private static SqlRouteDecision decision(
            String dataSourceId
    ) {
        return new PassThroughDecision(
                dataSourceId,
                "SELECT 1"
        );
    }
}
