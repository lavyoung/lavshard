package io.github.lavyoung.lavshard.starter.api.broadcast;

import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * 一次跨分片遍历中的物理执行目标。
 *
 * <p>一个目标对应拓扑中的一个 {@code ShardNode}。它同时携带物理数据源和
 * 物理表信息，避免分库分表场景下只遍历数据源而遗漏真实表名。</p>
 *
 * @param nodeId       稳定的物理节点标识
 * @param dataSourceId LavShard 数据源标识
 * @param dataSource   当前物理节点实际使用的数据源
 * @param actualTable  当前物理节点上的真实表名
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.1
 * @date 2026/10/08
 */
public record ShardBroadcastTarget(
        String nodeId,
        String dataSourceId,
        DataSource dataSource,
        QualifiedTableName actualTable
) {
    /**
     * 校验跨分片扫描目标的完整性。
     *
     * @throws NullPointerException     数据源或物理表为空时抛出
     * @throws IllegalArgumentException 节点标识或数据源标识为空白时抛出
     */
    public ShardBroadcastTarget {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        if (dataSourceId == null || dataSourceId.isBlank()) {
            throw new IllegalArgumentException("dataSourceId must not be blank");
        }

        Objects.requireNonNull(dataSource, "dataSource must not be null");
        Objects.requireNonNull(actualTable, "actualTable must not be null");
    }
}
