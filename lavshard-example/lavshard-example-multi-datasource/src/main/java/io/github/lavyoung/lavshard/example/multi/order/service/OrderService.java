package io.github.lavyoung.lavshard.example.multi.order.service;

import io.github.lavyoung.lavshard.example.multi.order.api.CreateOrderRequest;
import io.github.lavyoung.lavshard.example.multi.order.api.OrderView;
import io.github.lavyoung.lavshard.example.multi.order.mapper.OrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 多数据源示例订单服务。
 *
 * <p>普通单条调用可以分别访问不同数据库。本地事务首次访问
 * 一个数据库后，后续 SQL 不允许切换到另一个数据库。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/09/14
 */
@Service
public class OrderService {

    private final OrderMapper orderMapper;

    public OrderService(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    /**
     * 创建一个订单。
     *
     * @param request 创建请求
     * @return 已创建订单
     * @throws IllegalStateException 写入行数不符合预期时抛出
     */
    public OrderView create(CreateOrderRequest request) {
        return insert(request);
    }

    /**
     * 按分片键查询订单。
     *
     * @param userId 用户 ID
     * @return 订单；不存在时返回空
     */
    public Optional<OrderView> findByUserId(String userId) {
        return orderMapper.findByUserId(userId);
    }

    /**
     * 尝试在一个 Spring 本地事务中创建两个订单。
     *
     * <p>如果两个分片键命中不同数据库，LavShard 必须在第二条
     * SQL 获取物理连接前抛出跨分片事务异常，并回滚第一条写入。</p>
     *
     * @param first  第一个订单
     * @param second 第二个订单
     * @throws RuntimeException 第二个订单命中不同数据库时抛出
     */
    @Transactional
    public void createPair(
            CreateOrderRequest first,
            CreateOrderRequest second
    ) {
        insert(first);
        insert(second);
    }

    private OrderView insert(CreateOrderRequest request) {
        OrderView order = new OrderView(
                request.id(),
                request.userId(),
                request.note()
        );

        int affectedRows = orderMapper.insert(order);
        if (affectedRows != 1) {
            throw new IllegalStateException(
                    "Expected one inserted order but got "
                            + affectedRows
            );
        }

        return order;
    }
}