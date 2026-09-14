package io.github.lavyoung.lavshard.example.multi.order.web;

import io.github.lavyoung.lavshard.example.multi.order.api.CreateOrderRequest;
import io.github.lavyoung.lavshard.example.multi.order.api.OrderView;
import io.github.lavyoung.lavshard.example.multi.order.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 多数据源示例订单 HTTP API。
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/09/14
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderView create(
            @RequestBody CreateOrderRequest request
    ) {
        return orderService.create(request);
    }

    @GetMapping("/{userId}")
    public OrderView findByUserId(
            @PathVariable String userId
    ) {
        return orderService.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Order does not exist: userId=" + userId
                ));
    }
}