package io.github.lavyoung.lavshard.starter.internal.broadcast;

import io.github.lavyoung.lavshard.core.api.exception.ConfigurationException;
import io.github.lavyoung.lavshard.core.api.exception.CrossShardTransactionException;
import io.github.lavyoung.lavshard.core.api.exception.ShardRuleNotFoundException;
import io.github.lavyoung.lavshard.core.api.rule.RuleSnapshot;
import io.github.lavyoung.lavshard.core.api.rule.TableRule;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.api.topology.ShardNode;
import io.github.lavyoung.lavshard.starter.api.broadcast.ShardBroadcastOperations;
import io.github.lavyoung.lavshard.starter.api.broadcast.ShardBroadcastTarget;
import io.github.lavyoung.lavshard.starter.api.broadcast.ShardQueryCallback;
import io.github.lavyoung.lavshard.starter.internal.datasource.LavShardPhysicalDataSourceCatalog;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 基于启动期规则快照的跨分片扫描实现。
 *
 * <p>实现直接使用物理数据源，不经过延迟路由数据源，确保一次回调只访问一个
 * 明确的物理分片目标。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/10/08
 */
public final class DefaultShardBroadcastOperations implements ShardBroadcastOperations {

    private final RuleSnapshot ruleSnapshot;
    private final LavShardPhysicalDataSourceCatalog dataSourceCatalog;

    /**
     * 创建跨分片扫描服务。
     *
     * @param ruleSnapshot      启动期不可变分片规则快照
     * @param dataSourceCatalog 已解析的物理数据源目录
     * @throws NullPointerException 参数为空时抛出
     */
    public DefaultShardBroadcastOperations(RuleSnapshot ruleSnapshot, LavShardPhysicalDataSourceCatalog dataSourceCatalog) {
        this.ruleSnapshot = Objects.requireNonNull(ruleSnapshot, "ruleSnapshot must not be null");
        this.dataSourceCatalog = Objects.requireNonNull(dataSourceCatalog, "dataSourceCatalog must not be null");
    }

    @Override
    public List<ShardBroadcastTarget> targets(QualifiedTableName logicalTable) {
        Objects.requireNonNull(logicalTable, "logicalTable must not be null");

        TableRule tableRule = ruleSnapshot.find(logicalTable).orElseThrow(() -> new ShardRuleNotFoundException("Shard rule does not exist: logicalTable=" + logicalTable));

        return tableRule.topology().nodes().values().stream()
                .sorted(Comparator.comparing(ShardNode::nodeId))
                .map(this::toBroadcastTarget)
                .toList();
    }


    @Override
    public void forEachTarget(QualifiedTableName logicalTable, Consumer<ShardBroadcastTarget> consumer) {
        Objects.requireNonNull(consumer, "consumer must not be null");
        assertNoActiveTransaction();
        targets(logicalTable).forEach(consumer);
    }

    @Override
    public <T> List<T> queryAll(QualifiedTableName logicalTable, ShardQueryCallback<T> queryCallback) {
        Objects.requireNonNull(queryCallback, "queryCallback must not be null");
        assertNoActiveTransaction();

        List<T> result = new ArrayList<>();

        for (ShardBroadcastTarget target : targets(logicalTable)) {
            List<T> shardResults = Objects.requireNonNull(queryCallback.query(target), "queryCallback result must not be null");
            result.addAll(shardResults);
        }

        return List.copyOf(result);
    }

    /**
     * 将拓扑节点转换为可供业务查询的物理扫描目标。
     *
     * @param node 当前拓扑物理节点
     * @return 已绑定物理数据源的扫描目标
     * @throws ConfigurationException 节点引用不存在的数据源时抛出
     */
    private ShardBroadcastTarget toBroadcastTarget(ShardNode node) {
        return new ShardBroadcastTarget(
                node.nodeId(),
                node.dataSourceId(),
                dataSourceCatalog.requireDataSource(node.dataSourceId()),
                node.actualTable()
        );
    }

    /**
     * 拒绝在 Spring 活跃事务中进行跨分片回调。
     *
     * @throws CrossShardTransactionException 检测到活跃事务时抛出
     */
    private void assertNoActiveTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new CrossShardTransactionException(
                    "Shard broadcast operations must not execute inside an active transaction"
            );
        }
    }
}
