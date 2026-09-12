package io.github.lavyoung.lavshard.starter;

import io.github.lavyoung.lavshard.core.api.exception.ConfigurationException;
import io.github.lavyoung.lavshard.core.api.rule.TableRule;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
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
