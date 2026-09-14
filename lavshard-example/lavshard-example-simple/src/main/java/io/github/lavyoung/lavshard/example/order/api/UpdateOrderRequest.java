package io.github.lavyoung.lavshard.example.order.api;

/**
 * 修改订单说明请求。
 *
 * @param note 新订单说明
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/09/14
 */
public record UpdateOrderRequest(String note) {
}
