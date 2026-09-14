package io.github.lavyoung.lavshard.example.multi.order.api;

/**
 * 订单查询结果。
 *
 * @param id     订单 ID
 * @param userId 分片键
 * @param note   订单说明
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/09/14
 */
public record OrderView(
        long id,
        String userId,
        String note
) {
}
