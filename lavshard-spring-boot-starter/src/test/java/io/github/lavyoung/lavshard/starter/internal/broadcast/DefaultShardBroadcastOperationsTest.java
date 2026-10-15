package io.github.lavyoung.lavshard.starter.internal.broadcast;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.exception.CrossShardTransactionException;
import io.github.lavyoung.lavshard.core.api.exception.ShardRuleNotFoundException;
import io.github.lavyoung.lavshard.core.api.rule.RuleSnapshot;
import io.github.lavyoung.lavshard.core.api.rule.TableRule;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.api.topology.ShardNode;
import io.github.lavyoung.lavshard.core.api.topology.ShardTopology;
import io.github.lavyoung.lavshard.starter.api.broadcast.ShardBroadcastTarget;
import io.github.lavyoung.lavshard.starter.internal.datasource.LavShardPhysicalDataSourceCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * {@link DefaultShardBroadcastOperations} 的跨分片扫描执行契约。
 */
class DefaultShardBroadcastOperationsTest {

    private static final QualifiedTableName LOGICAL_TABLE = new QualifiedTableName("t_order");

    /**
     * Given 多个逻辑桶映射到两个物理节点
     * When 获取逻辑表的扫描目标
     * Then 每个物理节点只返回一次，并按节点标识稳定排序。
     */
    @Test
    void shouldReturnDistinctTargetsInNodeIdOrder() {
        TestFixture fixture = createFixture();

        List<ShardBroadcastTarget> targets = fixture.operations().targets(LOGICAL_TABLE);

        assertThat(targets)
                .extracting(ShardBroadcastTarget::nodeId)
                .containsExactly("node-0", "node-1");
        assertThat(targets)
                .extracting(target -> target.actualTable().table())
                .containsExactly("t_order_00", "t_order_01");
        assertThat(targets)
                .extracting(ShardBroadcastTarget::dataSource)
                .containsExactly(fixture.dataSource0(), fixture.dataSource1());
    }

    /**
     * Given 已配置多个物理分片目标
     * When 在全部目标上执行查询
     * Then 结果按照稳定的物理目标顺序合并。
     */
    @Test
    void shouldMergeQueryResultsInTargetOrder() {
        TestFixture fixture = createFixture();

        List<String> results = fixture.operations().queryAll(
                LOGICAL_TABLE,
                target -> List.of(target.nodeId(), target.actualTable().table())
        );

        assertThat(results).containsExactly(
                "node-0",
                "t_order_00",
                "node-1",
                "t_order_01"
        );
    }

    /**
     * Given 未配置分片规则的逻辑表
     * When 请求该逻辑表的扫描目标
     * Then 抛出稳定的分片规则不存在异常。
     */
    @Test
    void shouldRejectUnknownLogicalTable() {
        TestFixture fixture = createFixture();

        assertThatThrownBy(() -> fixture.operations().targets(
                new QualifiedTableName("t_unknown")
        )).isInstanceOf(ShardRuleNotFoundException.class);
    }

    /**
     * Given Spring 当前线程存在活跃事务
     * When 执行跨分片回调
     * Then 在调用任何业务回调前拒绝执行。
     */
    @Test
    void shouldRejectExecutionInsideActiveTransaction() {
        TestFixture fixture = createFixture();
        AtomicBoolean callbackInvoked = new AtomicBoolean();

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> fixture.operations().forEachTarget(
                    LOGICAL_TABLE,
                    target -> callbackInvoked.set(true)
            )).isInstanceOf(CrossShardTransactionException.class);
            assertThat(callbackInvoked).isFalse();
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    /**
     * 创建用于验证跨分片遍历行为的完整固定夹具。
     *
     * @return 包含规则快照、物理数据源与操作实现的测试夹具
     */
    private static TestFixture createFixture() {
        DataSource dataSource0 = mock(DataSource.class);
        DataSource dataSource1 = mock(DataSource.class);
        ShardTopology topology = new ShardTopology(
                "topology-v1",
                3,
                Map.of(0, "node-1", 1, "node-0", 2, "node-1"),
                Map.of(
                        "node-0", new ShardNode("node-0", "ds0", new QualifiedTableName("t_order_00")),
                        "node-1", new ShardNode("node-1", "ds1", new QualifiedTableName("t_order_01"))
                )
        );
        TableRule tableRule = new TableRule(
                "rule-v1",
                LOGICAL_TABLE,
                "user_id",
                "hash_mod",
                new AlgorithmConfig(3, "murmur3_32_v1"),
                topology
        );
        LavShardPhysicalDataSourceCatalog dataSourceCatalog = new LavShardPhysicalDataSourceCatalog(
                Map.of("ds0", dataSource0, "ds1", dataSource1)
        );

        return new TestFixture(
                new DefaultShardBroadcastOperations(
                        new RuleSnapshot(List.of(tableRule)),
                        dataSourceCatalog
                ),
                dataSource0,
                dataSource1
        );
    }

    /**
     * 跨分片遍历测试所需的固定依赖集合。
     *
     * @param operations  被测跨分片扫描操作
     * @param dataSource0 第一个物理数据源
     * @param dataSource1 第二个物理数据源
     */
    private record TestFixture(
            DefaultShardBroadcastOperations operations,
            DataSource dataSource0,
            DataSource dataSource1
    ) {
    }
}
