package io.github.lavyoung.lavshard.example.multi.order.mapper;

import io.github.lavyoung.lavshard.example.multi.order.api.OrderView;
import org.apache.ibatis.annotations.*;

import java.util.Optional;

/**
 * 多数据源示例订单 Mapper。
 *
 * <p>业务 SQL 只使用逻辑表 {@code t_order}。两个物理数据库
 * 都包含同名物理表，最终数据库由路由计划中的 dataSourceId
 * 决定。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/09/14
 */
@Mapper
public interface OrderMapper {

    @Insert("""
            INSERT INTO t_order (id, user_id, note)
            VALUES (#{id}, #{userId}, #{note})
            """)
    int insert(OrderView order);

    @Select("""
            SELECT id, user_id, note
            FROM t_order
            WHERE user_id = #{userId}
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = long.class, id = true),
            @Arg(column = "user_id", javaType = String.class),
            @Arg(column = "note", javaType = String.class)
    })
    Optional<OrderView> findByUserId(
            @Param("userId") String userId
    );
}
