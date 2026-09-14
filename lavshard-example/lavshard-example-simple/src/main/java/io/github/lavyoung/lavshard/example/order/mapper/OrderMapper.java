package io.github.lavyoung.lavshard.example.order.mapper;

import io.github.lavyoung.lavshard.example.order.api.OrderView;
import org.apache.ibatis.annotations.*;

import java.util.Optional;

/**
 * 订单单分片 Mapper。
 *
 * <p>Mapper 始终使用逻辑表 {@code t_order}，物理表名由
 * LavShard 在 Executor 执行前完成改写。</p>
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

    @Update("""
            UPDATE t_order
            SET note = #{note}
            WHERE user_id = #{userId}
            """)
    int updateNote(
            @Param("userId") String userId,
            @Param("note") String note
    );

    @Delete("""
            DELETE FROM t_order
            WHERE user_id = #{userId}
            """)
    int deleteByUserId(
            @Param("userId") String userId
    );
}
