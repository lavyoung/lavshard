package io.github.lavyoung.lavshard.starter;

import org.apache.logging.log4j.util.Strings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * LavShard 外部配置模型。
 *
 * <p>该类型只负责承载配置，不执行 Bean 查找或 Core 模型构建。
 * 所有集合在绑定完成后都会转换为不可变快照。</p>
 *
 * @param enabled     是否启用 LavShard
 * @param integration 集成层配置
 * @param dataSources 数据源 ID 到 Spring Bean 引用的映射
 * @param tables      逻辑表到分片规则配置的映射
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/10
 */
@ConfigurationProperties(prefix = "lavshard")
public record LavShardProperties(
        @DefaultValue("true") boolean enabled,
        Integration integration,
        Map<String, DataSourceReference> dataSources,
        Map<String, Table> tables
) {

    public LavShardProperties {
        integration = integration == null
                ? new Integration(Strings.EMPTY, Set.of())
                : integration;
        dataSources = dataSources == null
                ? Map.of()
                : Map.copyOf(dataSources);
        tables = tables == null
                ? Map.of()
                : Map.copyOf(tables);
    }


    /**
     * MyBatis 集成范围配置。
     *
     * @param defaultDataSource 默认数据源 ID
     * @param ordinaryTables    明确允许透传的普通表
     */
    public record Integration(String defaultDataSource, Set<String> ordinaryTables) {
        public Integration {
            defaultDataSource = Objects.requireNonNullElse(defaultDataSource, Strings.EMPTY);
            ordinaryTables = ordinaryTables == null ? Set.of() : Set.copyOf(ordinaryTables);
        }
    }

    /**
     * Spring DataSource Bean 引用。
     *
     * @param beanName DataSource Bean 名称
     */
    public record DataSourceReference(String beanName) {
        public DataSourceReference {
            beanName = Objects.requireNonNullElse(beanName, Strings.EMPTY);
        }
    }

    /**
     * 单张逻辑表的分片规则配置。
     *
     * @param ruleVersion    规则版本
     * @param shardingColumn 分片列
     * @param algorithm      分片算法配置
     * @param topology       物理拓扑配置
     */
    public record Table(
            String ruleVersion,
            String shardingColumn,
            Algorithm algorithm,
            Topology topology
    ) {
        public Table {
            ruleVersion = Objects.requireNonNullElse(ruleVersion, Strings.EMPTY);
            shardingColumn = Objects.requireNonNullElse(shardingColumn, Strings.EMPTY);
            algorithm = algorithm == null ? new Algorithm(Strings.EMPTY, Strings.EMPTY) : algorithm;
            topology = topology == null ? new Topology(Strings.EMPTY, 0, Map.of(), Map.of()) : topology;
        }
    }

    /**
     * 分片算法配置。
     *
     * @param name        算法注册名
     * @param hashVersion Hash 持久化语义版本
     */
    public record Algorithm(
            String name,
            String hashVersion
    ) {
        public Algorithm {
            name = Objects.requireNonNullElse(name, Strings.EMPTY);
            hashVersion = Objects.requireNonNullElse(hashVersion, Strings.EMPTY);
        }
    }

    /**
     * 分片拓扑配置。
     *
     * @param version          拓扑版本
     * @param bucketCount      固定逻辑桶数量
     * @param bucketPlacements 逻辑桶到节点 ID 的完整映射
     * @param nodes            节点 ID 到物理节点配置的映射
     */
    public record Topology(
            String version,
            int bucketCount,
            Map<Integer, String> bucketPlacements,
            Map<String, Node> nodes
    ) {

        public Topology {
            version = Objects.requireNonNullElse(version, Strings.EMPTY);
            bucketPlacements = bucketPlacements == null ? Map.of() : Map.copyOf(bucketPlacements);
            nodes = nodes == null ? Map.of() : Map.copyOf(nodes);
        }
    }

    /**
     * 单个物理节点配置。
     *
     * @param dataSource  数据源 ID
     * @param actualTable 物理表名
     */
    public record Node(String dataSource, String actualTable) {
        public Node {
            dataSource = Objects.requireNonNullElse(dataSource, Strings.EMPTY);
            actualTable = Objects.requireNonNullElse(actualTable, Strings.EMPTY);
        }
    }
}
