package io.github.lavyoung.lavshard.starter.api.broadcast;

import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;

import java.util.List;
import java.util.function.Consumer;

/**
 * 有意执行跨分片扫描或广播查询的公开入口。
 *
 * <p>该接口不会改变普通 SQL 的按分片键路由行为。调用方必须在回调中控制
 * 单分片查询量，并自行决定跨分片结果的排序、分页和业务合并策略。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.1
 * @date 2026/10/08
 */
public interface ShardBroadcastOperations {

    /**
     * 获取一个逻辑表对应的全部物理扫描目标。
     *
     * <p>返回结果按节点标识稳定排序；每个物理节点只出现一次，即使它承载多个
     * 逻辑桶也不会重复返回。</p>
     *
     * @param logicalTable 要扫描的逻辑表
     * @return 不可修改的物理扫描目标列表
     * @throws NullPointerException 逻辑表为空时抛出
     * @throws RuntimeException     逻辑表不存在分片规则时抛出
     */
    List<ShardBroadcastTarget> targets(QualifiedTableName logicalTable);

    /**
     * 依次访问一个逻辑表的全部物理扫描目标。
     *
     * <p>该方法不允许在 Spring 活跃事务中执行，避免调用方误以为跨库操作具备
     * 分布式事务能力。</p>
     *
     * @param logicalTable 要扫描的逻辑表
     * @param consumer     每个物理目标的处理回调
     * @throws NullPointerException 逻辑表或回调为空时抛出
     * @throws RuntimeException     逻辑表不存在、存在活跃事务或回调失败时抛出
     */
    void forEachTarget(QualifiedTableName logicalTable, Consumer<ShardBroadcastTarget> consumer);

    /**
     * 在全部物理目标上执行查询并按照目标顺序合并结果。
     *
     * <p>该方法不进行全局排序和全局分页。回调必须返回非空列表；任一分片失败时
     * 立即停止并抛出原始异常。</p>
     *
     * @param logicalTable  要扫描的逻辑表
     * @param queryCallback 每个物理目标的查询回调
     * @param <T>           单条查询结果类型
     * @return 按物理目标顺序合并后的不可修改结果列表
     * @throws NullPointerException 逻辑表、回调或回调结果为空时抛出
     * @throws RuntimeException     逻辑表不存在、存在活跃事务或查询失败时抛出
     */
    <T> List<T> queryAll(QualifiedTableName logicalTable, ShardQueryCallback<T> queryCallback);
}
