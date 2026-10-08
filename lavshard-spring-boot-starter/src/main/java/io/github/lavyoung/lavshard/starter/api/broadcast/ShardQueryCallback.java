package io.github.lavyoung.lavshard.starter.api.broadcast;

import java.util.List;

/**
 * 在一个物理分片目标上执行查询的回调。
 *
 * <p>回调直接得到物理数据源与真实表名，执行期间不会再进入 LavShard 的
 * 常规 SQL 路由链路，因此不会发生二次路由。</p>
 *
 * @param <T> 单条查询结果类型
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.1
 * @date 2026/10/08
 */
@FunctionalInterface
public interface ShardQueryCallback<T> {

    /**
     * 查询一个物理分片目标。
     *
     * @param target 当前物理分片目标
     * @return 当前分片的查询结果，不能为空集合
     * @throws RuntimeException 查询失败时抛出
     */
    List<T> query(ShardBroadcastTarget target);
}
