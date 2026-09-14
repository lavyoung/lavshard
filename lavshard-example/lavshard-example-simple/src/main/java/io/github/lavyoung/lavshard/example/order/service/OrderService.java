package io.github.lavyoung.lavshard.example.order.service;

import io.github.lavyoung.lavshard.example.order.api.CreateOrderRequest;
import io.github.lavyoung.lavshard.example.order.api.OrderView;
import io.github.lavyoung.lavshard.example.order.mapper.OrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 示例订单应用服务。
 *
 * <p>事务管理器操作的是 LavShard 逻辑数据源。同一事务中的两个
 * 订单可以进入不同物理表，只要它们仍属于同一个物理数据库。</p>
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
     * 创建订单。
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
     * 修改订单说明。
     *
     * @param userId 用户 ID
     * @param note   新说明
     * @return 修改后的订单；订单不存在时返回空
     */
    public Optional<OrderView> updateNote(
            String userId,
            String note
    ) {
        if (orderMapper.updateNote(userId, note) == 0) {
            return Optional.empty();
        }
        return orderMapper.findByUserId(userId);
    }

    /**
     * 删除订单。
     *
     * @param userId 用户 ID
     * @return 是否删除了一条记录
     */
    public boolean deleteByUserId(String userId) {
        return orderMapper.deleteByUserId(userId) == 1;
    }

    /**
     * 在一个本地事务中创建两个订单。
     *
     * <p>两个订单允许命中同一数据库中的不同物理表。如果第二次
     * 插入失败，第一次插入也必须回滚。</p>
     *
     * @param first  第一个订单
     * @param second 第二个订单
     * @throws RuntimeException 任意一次写入失败时抛出并回滚事务
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