package io.github.lavyoung.lavshard.starter.autoconfigure.config;

import org.apache.logging.log4j.util.Strings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
                ? new Integration(Strings.EMPTY, Set.of(), Set.of())
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
     * @param defaultDataSource     默认数据源 ID
     * @param ordinaryTables        明确允许透传的普通表
     * @param managedMapperPackages 受 LavShard 管理的 Mapper 包或 namespace
     */
    public record Integration(
            String defaultDataSource,
            Set<String> ordinaryTables,
            Set<String> managedMapperPackages
    ) {

        public Integration(
                String defaultDataSource,
                Set<String> ordinaryTables
        ) {
            this(
                    defaultDataSource,
                    ordinaryTables,
                    Set.of()
            );
        }

        @ConstructorBinding
        public Integration {
            defaultDataSource = Objects.requireNonNullElse(
                    defaultDataSource,
                    Strings.EMPTY
            );
            ordinaryTables = ordinaryTables == null
                    ? Set.of()
                    : Set.copyOf(ordinaryTables);
            managedMapperPackages = managedMapperPackages == null
                    ? Set.of()
                    : Set.copyOf(managedMapperPackages);
        }
    }

    /**
     * 单个物理数据源配置。
     *
     * <p>每个数据源必须且只能选择引用应用 Bean 或由 Starter
     * 创建连接池两种模式之一。</p>
     *
     * @param beanName 应用提供的 DataSource Bean 名称
     * @param managed  Starter 托管连接池配置
     */
    public record DataSourceReference(String beanName, ManagedDataSource managed) {

        /**
         * 兼容原有只引用 Spring Bean 的构造方式。
         *
         * @param beanName DataSource Bean 名称
         */
        public DataSourceReference(String beanName) {
            this(beanName, null);
        }

        @ConstructorBinding
        public DataSourceReference {
            beanName = Objects.requireNonNullElse(beanName, Strings.EMPTY);
        }
    }

    /**
     * Starter 托管 Hikari 连接池配置。
     *
     * @param url               JDBC URL
     * @param username          数据库用户名
     * @param password          数据库密码
     * @param driverClassName   JDBC 驱动类；空值时由 JDBC URL 自动推断
     * @param maximumPoolSize   最大连接数
     * @param minimumIdle       最小空闲连接数
     * @param connectionTimeout 获取连接的最长等待毫秒数
     */
    public record ManagedDataSource(
            String url,
            String username,
            String password,
            String driverClassName,
            @DefaultValue("10") int maximumPoolSize,
            @DefaultValue("10") int minimumIdle,
            @DefaultValue("30000") long connectionTimeout
    ) {
        public ManagedDataSource {
            url = Objects.requireNonNullElse(url, Strings.EMPTY);
            username = Objects.requireNonNullElse(username, Strings.EMPTY);
            password = Objects.requireNonNullElse(password, Strings.EMPTY);
            driverClassName = Objects.requireNonNullElse(driverClassName, Strings.EMPTY);
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
