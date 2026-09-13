package io.github.lavyoung.lavshard.mybatis.internal.routing;

import io.github.lavyoung.lavshard.core.api.route.SqlRouteDecision;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 当前线程的 MyBatis 路由决策上下文。
 *
 * <p>Executor 执行期间将路由决策绑定到当前线程，后续的
 * 路由数据源和事务守卫可以从这里读取物理数据源。</p>
 *
 * <p>上下文使用栈结构支持嵌套 Mapper 调用。最外层作用域
 * 关闭后必须清理 ThreadLocal，避免线程池复用造成串库。</p>
 *
 * <p>决策守卫在路由决策进入 ThreadLocal 前执行。守卫拒绝
 * 当前决策时，不得创建或污染线程上下文。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public final class MyBatisRouteContext {

    private static final Consumer<SqlRouteDecision> NO_OP_DECISION_GUARD = ignored -> {
    };

    private final ThreadLocal<Deque<SqlRouteDecision>> decisions = new ThreadLocal<>();

    private final Consumer<SqlRouteDecision> decisionGuard;

    /**
     * 创建不包含事务守卫的路由上下文。
     */
    public MyBatisRouteContext() {
        this(NO_OP_DECISION_GUARD);
    }

    /**
     * 创建带决策守卫的路由上下文。
     *
     * @param decisionGuard 决策进入执行作用域前调用的守卫
     * @throws NullPointerException 守卫为空时抛出
     */
    public MyBatisRouteContext(Consumer<SqlRouteDecision> decisionGuard) {
        this.decisionGuard = Objects.requireNonNull(decisionGuard, "decisionGuard must not be null");
    }

    /**
     * 打开一个路由决策作用域。
     *
     * @param decision 当前 SQL 的路由决策
     * @return 必须关闭的作用域
     * @throws NullPointerException decision 为空时抛出
     */
    public Scope open(SqlRouteDecision decision) {
        Objects.requireNonNull(decision, "decision must not be null");

        decisionGuard.accept(decision);

        Deque<SqlRouteDecision> stack = decisions.get();

        if (stack == null) {
            stack = new ArrayDeque<>();
            decisions.set(stack);
        }

        stack.push(decision);

        return new RouteScope(stack, decision);
    }

    /**
     * 获取当前线程最内层的路由决策。
     *
     * @return 当前决策；不在执行作用域时返回空
     */
    public Optional<SqlRouteDecision> currentDecision() {
        Deque<SqlRouteDecision> stack = decisions.get();

        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(stack.peek());
    }

    /**
     * 可关闭的路由作用域。
     */
    public interface Scope extends AutoCloseable {

        @Override
        void close();
    }

    private final class RouteScope implements Scope {

        private final Deque<SqlRouteDecision> stack;
        private final SqlRouteDecision decision;
        private boolean closed;

        private RouteScope(Deque<SqlRouteDecision> stack, SqlRouteDecision decision) {
            this.stack = stack;
            this.decision = decision;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }

            if (stack.peek() != decision) {
                throw new IllegalStateException("route scopes must be closed in reverse order");
            }

            stack.pop();
            closed = true;

            if (stack.isEmpty()) {
                decisions.remove();
            }
        }
    }
}