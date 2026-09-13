package io.github.lavyoung.lavshard.core.api.exception;

/**
 * SQL 要求本地事务但当前没有活动事务时抛出。
 *
 * <p>该异常必须在获取真实物理连接之前抛出。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/13
 */
public class TransactionRequiredException
        extends LavShardException {

    /**
     * 创建事务缺失异常。
     *
     * @param message 可读错误信息
     */
    public TransactionRequiredException(
            String message
    ) {
        super(LavShardErrorCode.TRANSACTION_REQUIRED, message);
    }

    /**
     * 创建包含原始原因的事务缺失异常。
     *
     * @param message 可读错误信息
     * @param cause   原始异常
     */
    public TransactionRequiredException(
            String message,
            Throwable cause
    ) {
        super(LavShardErrorCode.TRANSACTION_REQUIRED, message, cause);
    }
}