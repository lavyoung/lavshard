package io.github.lavyoung.lavshard.example.multi.order.api;

/**
 * 创建订单请求。
 *
 * <p>示例由应用生成全局 ID，不依赖单个物理表的自增主键。</p>
 *
 * @param id     订单 ID
 * @param userId 分片键
 * @param note   订单说明
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/09/14
 */
public record CreateOrderRequest(
        long id,
        String userId,
        String note
) {
}
