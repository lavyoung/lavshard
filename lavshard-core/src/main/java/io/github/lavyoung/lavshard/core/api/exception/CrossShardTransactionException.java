package io.github.lavyoung.lavshard.core.api.exception;

/**
 * 本地事务中的路由与已绑定物理目标不兼容时抛出。
 *
 * <p>该异常必须在访问错误物理数据源前抛出。冲突包括数据源变化，
 * 以及同一事务内已固定的规则版本或拓扑版本发生变化。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public class CrossShardTransactionException extends LavShardException {

    /**
     * 创建事务路由冲突异常。
     *
     * @param message 可读错误信息
     */
    public CrossShardTransactionException(String message) {
        super(LavShardErrorCode.TRANSACTION_ROUTE_CONFLICT, message);
    }

    /**
     * 创建包含原始原因的事务路由冲突异常。
     *
     * @param message 可读错误信息
     * @param cause   原始异常
     */
    public CrossShardTransactionException(String message, Throwable cause) {
        super(LavShardErrorCode.TRANSACTION_ROUTE_CONFLICT, message, cause);
    }
}
