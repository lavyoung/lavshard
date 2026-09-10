package io.github.lavyoung.lavshard.core.api.topology;

import java.util.Objects;

/**
 * 一个可被路由到的稳定物理节点
 *
 * <p>节点仅保存数据源标识，不直接持有DataSource、Connection或其他数据库执行资源</p>
 *
 * @param nodeId       稳定节点标识
 * @param dataSourceId 数据源标识
 * @param actualTable  物理表名称
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @data 2026/9/10
 */
public record ShardNode(
        String nodeId,
        String dataSourceId,
        QualifiedTableName actualTable
) {

    public ShardNode {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be null or empty");
        }
        if (dataSourceId == null || dataSourceId.isBlank()) {
            throw new IllegalArgumentException("dataSourceId must not be null or empty");
        }
        Objects.requireNonNull(actualTable, "actualTable must not be null");
    }
}
