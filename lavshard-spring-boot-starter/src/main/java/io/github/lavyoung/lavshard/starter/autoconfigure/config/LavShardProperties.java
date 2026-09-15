package io.github.lavyoung.lavshard.starter.autoconfigure.config;

import org.apache.logging.log4j.util.Strings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.sql.Connection;
import java.util.List;
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
 * @param dataSources 数据源 ID 到物理数据源配置的映射
 * @param defaults    分片表默认配置
 * @param layouts     可复用的分片布局模板
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
        Defaults defaults,
        Map<String, Layout> layouts,
        Map<String, Table> tables
) {

    /**
     * 标准配置绑定构造器。
     */
    @ConstructorBinding
    public LavShardProperties {
        integration = integration == null ? new Integration(Strings.EMPTY, Set.of(), Set.of(), TransactionIsolation.REPEATABLE_READ) : integration;
        dataSources = dataSources == null ? Map.of() : Map.copyOf(dataSources);
        defaults = defaults == null ? new Defaults(Strings.EMPTY) : defaults;
        layouts = layouts == null ? Map.of() : Map.copyOf(layouts);
        tables = tables == null ? Map.of() : Map.copyOf(tables);
    }

    /**
     * 兼容原有四参数程序化构造方式。
     *
     * @param enabled     是否启用
     * @param integration 集成配置
     * @param dataSources 数据源配置
     * @param tables      显式表规则
     */
    public LavShardProperties(
            boolean enabled,
            Integration integration,
            Map<String, DataSourceReference> dataSources,
            Map<String, Table> tables) {
        this(enabled, integration, dataSources, new Defaults(Strings.EMPTY), Map.of(), tables);
    }


    /**
     * MyBatis 和 JDBC 集成配置。
     *
     * @param defaultDataSource           默认数据源 ID
     * @param ordinaryTables              明确允许透传的普通表
     * @param managedMapperPackages       受 LavShard 管理的 Mapper 包或 namespace
     * @param defaultTransactionIsolation 物理连接池的默认事务隔离级别
     */
    public record Integration(
            String defaultDataSource,
            Set<String> ordinaryTables,
            Set<String> managedMapperPackages,
            TransactionIsolation defaultTransactionIsolation
    ) {

        /**
         * 兼容原有两参数程序化构造方式。
         *
         * @param defaultDataSource 默认数据源 ID
         * @param ordinaryTables    普通表集合
         */
        public Integration(
                String defaultDataSource,
                Set<String> ordinaryTables
        ) {
            this(
                    defaultDataSource,
                    ordinaryTables,
                    Set.of(),
                    TransactionIsolation.REPEATABLE_READ
            );
        }

        public Integration(
                String defaultDataSource,
                Set<String> ordinaryTables,
                Set<String> managedMapperPackages
        ) {
            this(
                    defaultDataSource,
                    ordinaryTables,
                    managedMapperPackages,
                    TransactionIsolation.REPEATABLE_READ
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
            defaultTransactionIsolation = Objects.requireNonNullElse(defaultTransactionIsolation, TransactionIsolation.REPEATABLE_READ);
        }
    }

    /**
     * 可由 Starter 配置的 JDBC 事务隔离级别。
     *
     * <p>配置层使用可读枚举，运行时快照保存 JDBC 常量，避免路由热路径
     * 重复解析字符串。</p>
     */
    public enum TransactionIsolation {
        READ_UNCOMMITTED(Connection.TRANSACTION_READ_UNCOMMITTED),
        READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED),
        REPEATABLE_READ(Connection.TRANSACTION_REPEATABLE_READ),
        SERIALIZABLE(Connection.TRANSACTION_SERIALIZABLE);

        private final int jdbcLevel;

        TransactionIsolation(int jdbcLevel) {
            this.jdbcLevel = jdbcLevel;
        }

        /**
         * 返回 JDBC Connection 使用的隔离级别常量。
         *
         * @return JDBC 隔离级别
         */
        public int jdbcLevel() {
            return jdbcLevel;
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
     * 分片表默认配置。
     *
     * @param layout 默认分片布局名称
     */
    public record Defaults(String layout) {

        public Defaults {
            layout = Objects.requireNonNullElse(layout, Strings.EMPTY);
        }
    }

    /**
     * 可复用的分片布局模板。
     *
     * <p>数据源列表的顺序是持久化拓扑契约的一部分，不能依赖
     * data-sources Map 的遍历顺序。</p>
     *
     * @param version             稳定布局版本
     * @param dataSourceIds       按确定顺序排列的数据源 ID
     * @param tablesPerDataSource 每个数据源的物理表数量
     * @param bucketCount         固定逻辑桶数量
     * @param algorithm           分片算法；未配置时使用稳定 Hash 默认值
     * @param tableSuffix         物理表后缀；未配置时使用 _00 起始规则
     * @param placementStrategy   桶放置策略
     */
    public record Layout(
            String version,
            List<String> dataSourceIds,
            int tablesPerDataSource,
            int bucketCount,
            Algorithm algorithm,
            TableSuffix tableSuffix,
            PlacementStrategy placementStrategy
    ) {
        public Layout {
            version = Objects.requireNonNullElse(version, Strings.EMPTY);
            dataSourceIds = dataSourceIds == null ? List.of() : List.copyOf(dataSourceIds);
            algorithm = algorithm == null ? new Algorithm("hash_mod", "murmur3_32_v1")
                    : algorithm;
            tableSuffix = tableSuffix == null ? new TableSuffix(true, "_", 0, 2) : tableSuffix;
            placementStrategy = Objects.requireNonNullElse(placementStrategy, PlacementStrategy.ROUND_ROBIN);
        }
    }

    /**
     * 物理表后缀生成规则。
     *
     * <p>关闭后缀时用于纯分库拓扑：每个数据源只能包含一张物理表，
     * 且物理表名与逻辑表名保持一致。</p>
     *
     * @param enabled   是否在逻辑表名后追加数字后缀
     * @param separator 逻辑表名和数字后缀之间的分隔符
     * @param start     第一个物理表的数字编号
     * @param width     数字后缀的最小补零宽度
     */
    public record TableSuffix(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("_") String separator,
            @DefaultValue("0") int start,
            @DefaultValue("2") int width
    ) {
        @ConstructorBinding
        public TableSuffix {
            separator = Objects.requireNonNullElse(separator, "_");
        }

        /**
         * 兼容原有默认开启后缀的程序化构造方式。
         *
         * @param separator 表名与数字后缀之间的分隔符
         * @param start     起始编号
         * @param width     最小补零宽度
         */
        public TableSuffix(
                String separator,
                int start,
                int width
        ) {
            this(true, separator, start, width);
        }
    }

    /**
     * 初始逻辑桶放置策略。
     */
    public enum PlacementStrategy {
        ROUND_ROBIN
    }

    /**
     * 单张逻辑表的分片规则配置。
     *
     * <p>普通模式可以只配置 shardingColumn，并继承默认布局。
     * 专家模式继续配置 algorithm 和 topology。</p>
     *
     * @param ruleVersion    显式规则版本；布局模式下可以为空
     * @param shardingColumn 分片列
     * @param layout         表级布局覆盖
     * @param algorithm      显式拓扑模式使用的算法
     * @param topology       完整显式拓扑
     */
    public record Table(
            String ruleVersion,
            String shardingColumn,
            String layout,
            Algorithm algorithm,
            Topology topology
    ) {
        @ConstructorBinding
        public Table {
            ruleVersion = Objects.requireNonNullElse(ruleVersion, Strings.EMPTY);
            shardingColumn = Objects.requireNonNullElse(shardingColumn, Strings.EMPTY);
            layout = Objects.requireNonNullElse(layout, Strings.EMPTY);
            algorithm = algorithm == null ? new Algorithm(Strings.EMPTY, Strings.EMPTY) : algorithm;
            topology = topology == null ? new Topology(Strings.EMPTY, 0, Map.of(), Map.of()) : topology;
        }

        /**
         * 兼容原有显式拓扑四参数构造方式。
         *
         * @param ruleVersion    规则版本
         * @param shardingColumn 分片列
         * @param algorithm      分片算法
         * @param topology       显式拓扑
         */
        public Table(
                String ruleVersion,
                String shardingColumn,
                Algorithm algorithm,
                Topology topology
        ) {
            this(
                    ruleVersion,
                    shardingColumn,
                    Strings.EMPTY,
                    algorithm,
                    topology
            );
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
