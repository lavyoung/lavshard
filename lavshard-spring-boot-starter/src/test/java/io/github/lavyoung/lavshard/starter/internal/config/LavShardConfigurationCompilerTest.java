package io.github.lavyoung.lavshard.starter.internal.config;

import io.github.lavyoung.lavshard.core.api.exception.ConfigurationException;
import io.github.lavyoung.lavshard.core.api.rule.TableRule;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.starter.autoconfigure.config.LavShardProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LavShard 外部配置到不可变运行时快照的编译契约。
 */
class LavShardConfigurationCompilerTest {

    private final LavShardConfigurationCompiler compiler =
            new LavShardConfigurationCompiler();

    @Test
    void shouldBindKebabCasePropertiesAndCompileCompleteSnapshot() {
        Map<String, Object> source = Map.ofEntries(
                Map.entry("lavshard.enabled", "true"),
                Map.entry("lavshard.integration.default-data-source", "ds0"),
                Map.entry("lavshard.integration.ordinary-tables[0]", "sys_dict"),
                Map.entry("lavshard.data-sources[ds0].bean-name", "orderDataSource0"),
                Map.entry("lavshard.data-sources[ds1].bean-name", "orderDataSource1"),
                Map.entry("lavshard.tables[t_order].rule-version", "order-rule-v1"),
                Map.entry("lavshard.tables[t_order].sharding-column", "user_id"),
                Map.entry("lavshard.tables[t_order].algorithm.name", "hash_mod"),
                Map.entry("lavshard.tables[t_order].algorithm.hash-version", "murmur3_32_v1"),
                Map.entry("lavshard.tables[t_order].topology.version", "order-topology-v1"),
                Map.entry("lavshard.tables[t_order].topology.bucket-count", "2"),
                Map.entry("lavshard.tables[t_order].topology.bucket-placements[0]", "node0"),
                Map.entry("lavshard.tables[t_order].topology.bucket-placements[1]", "node1"),
                Map.entry("lavshard.tables[t_order].topology.nodes[node0].data-source", "ds0"),
                Map.entry("lavshard.tables[t_order].topology.nodes[node0].actual-table", "t_order_00"),
                Map.entry("lavshard.tables[t_order].topology.nodes[node1].data-source", "ds1"),
                Map.entry("lavshard.tables[t_order].topology.nodes[node1].actual-table", "t_order_00")
        );
        LavShardProperties properties = new Binder(
                new MapConfigurationPropertySource(source)
        ).bind(
                "lavshard",
                Bindable.of(LavShardProperties.class)
        ).orElseThrow(() -> new AssertionError(
                "LavShard properties were not bound"
        ));

        LavShardConfigurationSnapshot snapshot =
                compiler.compile(properties);

        assertThat(properties.enabled()).isTrue();
        assertThat(snapshot.dataSourceBeanNames()).containsExactlyInAnyOrderEntriesOf(
                Map.of(
                        "ds0", "orderDataSource0",
                        "ds1", "orderDataSource1"
                )
        );
        assertThat(snapshot.defaultDataSourceId()).isEqualTo("ds0");
        assertThat(snapshot.ordinaryTables()).containsExactly(
                new QualifiedTableName("sys_dict")
        );

        TableRule rule = snapshot.ruleSnapshot()
                .find(new QualifiedTableName("t_order"))
                .orElseThrow();
        assertThat(rule.ruleVersion()).isEqualTo("order-rule-v1");
        assertThat(rule.shardingColumn()).isEqualTo("user_id");
        assertThat(rule.algorithmName()).isEqualTo("hash_mod");
        assertThat(rule.algorithmConfig().bucketCount()).isEqualTo(2);
        assertThat(rule.algorithmConfig().hashVersion()).isEqualTo("murmur3_32_v1");
        assertThat(rule.topology().nodeForBucket(0).dataSourceId()).isEqualTo("ds0");
        assertThat(rule.topology().nodeForBucket(1).dataSourceId()).isEqualTo("ds1");
    }

    @Test
    void shouldBindAndCompileMixedReferencedAndManagedDataSources() {
        Map<String, Object> source = new HashMap<>();
        source.put("lavshard.integration.default-data-source", "ds0");
        source.put("lavshard.data-sources[ds0].bean-name", "applicationDataSource");
        source.put("lavshard.data-sources[ds1].managed.url", "jdbc:h2:mem:managed");
        source.put("lavshard.data-sources[ds1].managed.username", "sa");
        source.put("lavshard.data-sources[ds1].managed.password", "secret");
        source.put("lavshard.data-sources[ds1].managed.driver-class-name", "org.h2.Driver");
        source.put("lavshard.data-sources[ds1].managed.maximum-pool-size", "6");
        source.put("lavshard.data-sources[ds1].managed.minimum-idle", "2");
        source.put("lavshard.data-sources[ds1].managed.connection-timeout", "5000");
        LavShardProperties properties = bind(source);

        LavShardConfigurationSnapshot snapshot = compiler.compile(properties);

        assertThat(snapshot.dataSourceBeanNames())
                .containsExactly(Map.entry("ds0", "applicationDataSource"));
        assertThat(snapshot.managedDataSources())
                .containsOnlyKeys("ds1");
        LavShardProperties.ManagedDataSource managed =
                snapshot.managedDataSources().get("ds1");
        assertThat(managed.url()).isEqualTo("jdbc:h2:mem:managed");
        assertThat(managed.username()).isEqualTo("sa");
        assertThat(managed.password()).isEqualTo("secret");
        assertThat(managed.driverClassName()).isEqualTo("org.h2.Driver");
        assertThat(managed.maximumPoolSize()).isEqualTo(6);
        assertThat(managed.minimumIdle()).isEqualTo(2);
        assertThat(managed.connectionTimeout()).isEqualTo(5000L);
        assertThatThrownBy(() -> snapshot.managedDataSources().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldApplyStableManagedPoolDefaults() {
        Map<String, Object> source = new HashMap<>();
        source.put("lavshard.integration.default-data-source", "ds0");
        source.put("lavshard.data-sources[ds0].managed.url", "jdbc:h2:mem:defaulted");

        LavShardConfigurationSnapshot snapshot = compiler.compile(bind(source));
        LavShardProperties.ManagedDataSource managed =
                snapshot.managedDataSources().get("ds0");

        assertThat(managed.username()).isEmpty();
        assertThat(managed.password()).isEmpty();
        assertThat(managed.driverClassName()).isEmpty();
        assertThat(managed.maximumPoolSize()).isEqualTo(10);
        assertThat(managed.minimumIdle()).isEqualTo(10);
        assertThat(managed.connectionTimeout()).isEqualTo(30000L);
    }

    @Test
    void shouldRejectDataSourceWithBothReferenceAndManagedPool() {
        assertDataSourceConfigurationFailure(
                new LavShardProperties.DataSourceReference(
                        "applicationDataSource",
                        managedDataSource("jdbc:h2:mem:duplicate")
                ),
                "lavshard.data-sources.ds0 must configure exactly one of "
                        + "bean-name or managed"
        );
    }

    @Test
    void shouldRejectDataSourceWithoutReferenceOrManagedPool() {
        assertDataSourceConfigurationFailure(
                new LavShardProperties.DataSourceReference("", null),
                "lavshard.data-sources.ds0 must configure exactly one of "
                        + "bean-name or managed"
        );
    }

    @Test
    void shouldRejectInvalidManagedPoolConfigurationWithoutLeakingPassword() {
        LavShardProperties.ManagedDataSource managed =
                new LavShardProperties.ManagedDataSource(
                        " ",
                        "user",
                        "top-secret-password",
                        "",
                        0,
                        -1,
                        100L
                );
        LavShardProperties properties = propertiesWithDataSource(
                new LavShardProperties.DataSourceReference("", managed)
        );

        assertThatThrownBy(() -> compiler.compile(properties))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage(
                        "lavshard.data-sources.ds0.managed.url must not be blank"
                )
                .hasMessageNotContaining("top-secret-password");
    }

    @Test
    void shouldRejectEveryInvalidManagedPoolSizeBoundary() {
        assertManagedFailure(
                new LavShardProperties.ManagedDataSource(
                        "jdbc:h2:mem:max", "", "", "", 0, 0, 30000L
                ),
                "lavshard.data-sources.ds0.managed.maximum-pool-size "
                        + "must be greater than zero"
        );
        assertManagedFailure(
                new LavShardProperties.ManagedDataSource(
                        "jdbc:h2:mem:min", "", "", "", 4, -1, 30000L
                ),
                "lavshard.data-sources.ds0.managed.minimum-idle "
                        + "must be between zero and maximum-pool-size"
        );
        assertManagedFailure(
                new LavShardProperties.ManagedDataSource(
                        "jdbc:h2:mem:min", "", "", "", 4, 5, 30000L
                ),
                "lavshard.data-sources.ds0.managed.minimum-idle "
                        + "must be between zero and maximum-pool-size"
        );
        assertManagedFailure(
                new LavShardProperties.ManagedDataSource(
                        "jdbc:h2:mem:timeout", "", "", "", 4, 1, 249L
                ),
                "lavshard.data-sources.ds0.managed.connection-timeout "
                        + "must be at least 250 milliseconds"
        );
    }

    @Test
    void shouldSnapshotMutableConfigurationCollections() {
        Map<String, LavShardProperties.DataSourceReference> dataSources =
                new HashMap<>();
        dataSources.put(
                "ds0",
                new LavShardProperties.DataSourceReference("orderDataSource0")
        );
        Set<String> ordinaryTables = new HashSet<>();
        ordinaryTables.add("sys_dict");
        LavShardProperties properties = new LavShardProperties(
                true,
                new LavShardProperties.Integration("ds0", ordinaryTables),
                dataSources,
                Map.of("t_order", singleDataSourceTable())
        );

        dataSources.put(
                "ds1",
                new LavShardProperties.DataSourceReference("orderDataSource1")
        );
        ordinaryTables.add("sys_config");
        LavShardConfigurationSnapshot snapshot = compiler.compile(properties);

        assertThat(properties.dataSources()).containsOnlyKeys("ds0");
        assertThat(properties.integration().ordinaryTables()).containsExactly("sys_dict");
        assertThat(snapshot.dataSourceBeanNames()).containsOnlyKeys("ds0");
        assertThatThrownBy(() -> snapshot.dataSourceBeanNames().put("ds2", "other"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.ordinaryTables().add(
                new QualifiedTableName("other")
        )).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldCompileMultipleManagedTables() {
        LavShardProperties properties = validProperties(
                new LavShardProperties.Integration("ds0", Set.of("sys_dict")),
                Map.of(
                        "t_order", validTable(),
                        "t_payment", validTable("payment-rule-v1", "payment_id")
                )
        );

        LavShardConfigurationSnapshot snapshot = compiler.compile(properties);

        assertThat(snapshot.ruleSnapshot().find(
                new QualifiedTableName("t_order")
        )).isPresent();
        assertThat(snapshot.ruleSnapshot().find(
                new QualifiedTableName("t_payment")
        )).isPresent();
    }

    @Test
    void shouldRejectBlankDefaultDataSource() {
        LavShardProperties properties = validProperties(
                new LavShardProperties.Integration(" ", Set.of("sys_dict")),
                Map.of("t_order", validTable())
        );

        assertConfigurationFailure(
                properties,
                "lavshard.integration.default-data-source must not be blank"
        );
    }

    @Test
    void shouldRejectUnknownDefaultDataSource() {
        LavShardProperties properties = validProperties(
                new LavShardProperties.Integration("missing", Set.of("sys_dict")),
                Map.of("t_order", validTable())
        );

        assertConfigurationFailure(
                properties,
                "lavshard.integration.default-data-source references unknown dataSourceId: missing"
        );
    }

    @Test
    void shouldRejectBlankDataSourceBeanName() {
        LavShardProperties properties = new LavShardProperties(
                true,
                new LavShardProperties.Integration("ds0", Set.of("sys_dict")),
                Map.of("ds0", new LavShardProperties.DataSourceReference(" ")),
                Map.of("t_order", validTable())
        );

        assertConfigurationFailure(
                properties,
                "lavshard.data-sources.ds0.bean-name must not be blank"
        );
    }

    @Test
    void shouldRejectBlankOrdinaryTableName() {
        LavShardProperties properties = validProperties(
                new LavShardProperties.Integration("ds0", Set.of(" ")),
                Map.of("t_order", validTable())
        );

        assertConfigurationFailure(
                properties,
                "lavshard.integration.ordinary-tables must not contain blank table names"
        );
    }

    @Test
    void shouldRejectBlankLogicalTableName() {
        LavShardProperties properties = validProperties(
                new LavShardProperties.Integration("ds0", Set.of("sys_dict")),
                Map.of(" ", validTable())
        );

        assertConfigurationFailure(
                properties,
                "lavshard.tables must not contain blank logical table names"
        );
    }

    @Test
    void shouldRejectBlankAlgorithmNameWithTablePath() {
        LavShardProperties.Table table = new LavShardProperties.Table(
                "order-rule-v1",
                "user_id",
                new LavShardProperties.Algorithm(" ", "murmur3_32_v1"),
                validTopology()
        );

        assertConfigurationFailure(
                validProperties(
                        new LavShardProperties.Integration("ds0", Set.of()),
                        Map.of("t_order", table)
                ),
                "lavshard.tables.t_order.algorithm.name must not be blank"
        );
    }

    @Test
    void shouldRejectNodeReferencingUnknownDataSource() {
        LavShardProperties.Topology topology = new LavShardProperties.Topology(
                "order-topology-v1",
                2,
                Map.of(0, "node0", 1, "node1"),
                Map.of(
                        "node0", new LavShardProperties.Node("ds0", "t_order_00"),
                        "node1", new LavShardProperties.Node("missing", "t_order_00")
                )
        );
        LavShardProperties.Table table = new LavShardProperties.Table(
                "order-rule-v1",
                "user_id",
                validAlgorithm(),
                topology
        );

        assertConfigurationFailure(
                validProperties(
                        new LavShardProperties.Integration("ds0", Set.of()),
                        Map.of("t_order", table)
                ),
                "lavshard.tables.t_order.topology.nodes.node1.data-source "
                        + "references unknown dataSourceId: missing"
        );
    }

    @Test
    void shouldRejectBlankActualTableName() {
        LavShardProperties.Topology topology = new LavShardProperties.Topology(
                "order-topology-v1",
                1,
                Map.of(0, "node0"),
                Map.of("node0", new LavShardProperties.Node("ds0", " "))
        );
        LavShardProperties.Table table = new LavShardProperties.Table(
                "order-rule-v1",
                "user_id",
                validAlgorithm(),
                topology
        );

        assertConfigurationFailure(
                validProperties(
                        new LavShardProperties.Integration("ds0", Set.of()),
                        Map.of("t_order", table)
                ),
                "lavshard.tables.t_order.topology.nodes.node0.actual-table must not be blank"
        );
    }

    @Test
    void shouldWrapIncompleteBucketPlacementWithTablePath() {
        LavShardProperties.Topology topology = new LavShardProperties.Topology(
                "order-topology-v1",
                2,
                Map.of(0, "node0"),
                Map.of("node0", new LavShardProperties.Node("ds0", "t_order_00"))
        );
        LavShardProperties.Table table = new LavShardProperties.Table(
                "order-rule-v1",
                "user_id",
                validAlgorithm(),
                topology
        );

        assertThatThrownBy(() -> compiler.compile(
                validProperties(
                        new LavShardProperties.Integration("ds0", Set.of()),
                        Map.of("t_order", table)
                )
        ))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage(
                        "Invalid lavshard.tables.t_order: "
                                + "bucket placements must cover every bucket"
                )
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    private void assertConfigurationFailure(
            LavShardProperties properties,
            String message
    ) {
        assertThatThrownBy(() -> compiler.compile(properties))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage(message);
    }

    private void assertDataSourceConfigurationFailure(
            LavShardProperties.DataSourceReference dataSource,
            String message
    ) {
        assertConfigurationFailure(
                propertiesWithDataSource(dataSource),
                message
        );
    }

    private void assertManagedFailure(
            LavShardProperties.ManagedDataSource managed,
            String message
    ) {
        assertDataSourceConfigurationFailure(
                new LavShardProperties.DataSourceReference("", managed),
                message
        );
    }

    private static LavShardProperties propertiesWithDataSource(
            LavShardProperties.DataSourceReference dataSource
    ) {
        return new LavShardProperties(
                true,
                new LavShardProperties.Integration("ds0", Set.of()),
                Map.of("ds0", dataSource),
                Map.of()
        );
    }

    private static LavShardProperties.ManagedDataSource managedDataSource(
            String url
    ) {
        return new LavShardProperties.ManagedDataSource(
                url,
                "",
                "",
                "",
                10,
                10,
                30000L
        );
    }

    private static LavShardProperties bind(Map<String, Object> source) {
        return new Binder(
                new MapConfigurationPropertySource(source)
        ).bind(
                "lavshard",
                Bindable.of(LavShardProperties.class)
        ).orElseThrow(() -> new AssertionError(
                "LavShard properties were not bound"
        ));
    }

    private static LavShardProperties validProperties(
            LavShardProperties.Integration integration,
            Map<String, LavShardProperties.Table> tables
    ) {
        return new LavShardProperties(
                true,
                integration,
                Map.of(
                        "ds0",
                        new LavShardProperties.DataSourceReference("orderDataSource0"),
                        "ds1",
                        new LavShardProperties.DataSourceReference("orderDataSource1")
                ),
                tables
        );
    }

    private static LavShardProperties.Table validTable() {
        return validTable("order-rule-v1", "user_id");
    }

    private static LavShardProperties.Table singleDataSourceTable() {
        return new LavShardProperties.Table(
                "order-rule-v1",
                "user_id",
                validAlgorithm(),
                new LavShardProperties.Topology(
                        "order-topology-v1",
                        2,
                        Map.of(0, "node0", 1, "node0"),
                        Map.of(
                                "node0",
                                new LavShardProperties.Node(
                                        "ds0",
                                        "t_order_00"
                                )
                        )
                )
        );
    }

    private static LavShardProperties.Table validTable(
            String ruleVersion,
            String shardingColumn
    ) {
        return new LavShardProperties.Table(
                ruleVersion,
                shardingColumn,
                validAlgorithm(),
                validTopology()
        );
    }

    private static LavShardProperties.Algorithm validAlgorithm() {
        return new LavShardProperties.Algorithm(
                "hash_mod",
                "murmur3_32_v1"
        );
    }

    private static LavShardProperties.Topology validTopology() {
        return new LavShardProperties.Topology(
                "order-topology-v1",
                2,
                Map.of(0, "node0", 1, "node1"),
                Map.of(
                        "node0",
                        new LavShardProperties.Node("ds0", "t_order_00"),
                        "node1",
                        new LavShardProperties.Node("ds1", "t_order_00")
                )
        );
    }
}
