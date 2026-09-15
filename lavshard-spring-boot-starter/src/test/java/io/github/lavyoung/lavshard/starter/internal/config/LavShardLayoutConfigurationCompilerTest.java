package io.github.lavyoung.lavshard.starter.internal.config;

import io.github.lavyoung.lavshard.core.api.algorithm.ShardValue;
import io.github.lavyoung.lavshard.core.api.exception.ConfigurationException;
import io.github.lavyoung.lavshard.core.api.rule.TableRule;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.internal.algorithm.Murmur3HashShardAlgorithm;
import io.github.lavyoung.lavshard.core.internal.algorithm.ShardAlgorithmRegistry;
import io.github.lavyoung.lavshard.core.internal.route.SingleShardRouter;
import io.github.lavyoung.lavshard.starter.autoconfigure.config.LavShardProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分片布局模板、默认继承和自动拓扑展开契约。
 */
class LavShardLayoutConfigurationCompilerTest {

    private final LavShardConfigurationCompiler compiler =
            new LavShardConfigurationCompiler();

    @Test
    void shouldBindConciseLayoutConfigurationWithStableDefaults() {
        // Given
        Map<String, Object> source = baseBindingProperties();
        source.put("lavshard.defaults.layout", "standard-2x4");
        source.put("lavshard.layouts[standard-2x4].version", "layout-v1");
        source.put(
                "lavshard.layouts[standard-2x4].data-source-ids[0]",
                "ds0"
        );
        source.put(
                "lavshard.layouts[standard-2x4].data-source-ids[1]",
                "ds1"
        );
        source.put(
                "lavshard.layouts[standard-2x4].tables-per-data-source",
                "4"
        );
        source.put("lavshard.layouts[standard-2x4].bucket-count", "16");
        source.put(
                "lavshard.tables[t_order].sharding-column",
                "user_id"
        );

        // When
        LavShardProperties properties = bind(source);
        LavShardProperties.Layout layout =
                properties.layouts().get("standard-2x4");

        // Then
        assertThat(properties.defaults().layout()).isEqualTo("standard-2x4");
        assertThat(layout.algorithm().name()).isEqualTo("hash_mod");
        assertThat(layout.algorithm().hashVersion())
                .isEqualTo("murmur3_32_v1");
        assertThat(layout.tableSuffix().separator()).isEqualTo("_");
        assertThat(layout.tableSuffix().start()).isZero();
        assertThat(layout.tableSuffix().width()).isEqualTo(2);
        assertThat(layout.tableSuffix().enabled()).isTrue();
        assertThat(layout.placementStrategy())
                .isEqualTo(LavShardProperties.PlacementStrategy.ROUND_ROBIN);
        assertThat(properties.tables().get("t_order").layout()).isEmpty();
    }

    @Test
    void shouldExpandTwoDataSourcesAndFourTablesPerDataSource() {
        // Given
        LavShardProperties properties = conciseProperties(
                new LavShardProperties.Defaults("standard-2x4"),
                Map.of("standard-2x4", standardLayout()),
                Map.of("t_order", conciseTable("user_id"))
        );

        // When
        TableRule rule = rule(compiler.compile(properties), "t_order");

        // Then
        assertThat(rule.ruleVersion()).isEqualTo("layout-v1/t_order/rule");
        assertThat(rule.topology().version())
                .isEqualTo("layout-v1/t_order/topology");
        assertThat(rule.algorithmName()).isEqualTo("hash_mod");
        assertThat(rule.algorithmConfig().hashVersion())
                .isEqualTo("murmur3_32_v1");
        assertThat(rule.algorithmConfig().bucketCount()).isEqualTo(16);
        assertThat(rule.topology().nodes()).containsOnlyKeys(
                "t_order@ds0@00",
                "t_order@ds0@01",
                "t_order@ds0@02",
                "t_order@ds0@03",
                "t_order@ds1@00",
                "t_order@ds1@01",
                "t_order@ds1@02",
                "t_order@ds1@03"
        );
        assertThat(rule.topology().nodes().get("t_order@ds0@00").dataSourceId())
                .isEqualTo("ds0");
        assertThat(rule.topology().nodes().get("t_order@ds0@00")
                .actualTable().table()).isEqualTo("t_order_00");
        assertThat(rule.topology().nodes().get("t_order@ds1@03").dataSourceId())
                .isEqualTo("ds1");
        assertThat(rule.topology().nodes().get("t_order@ds1@03")
                .actualTable().table()).isEqualTo("t_order_03");
    }

    @Test
    void shouldGenerateCompleteRoundRobinBucketPlacements() {
        // Given
        LavShardProperties properties = conciseProperties(
                new LavShardProperties.Defaults("standard-2x4"),
                Map.of("standard-2x4", standardLayout()),
                Map.of("t_order", conciseTable("user_id"))
        );

        // When
        TableRule rule = rule(compiler.compile(properties), "t_order");

        // Then
        assertThat(rule.topology().bucketPlacements()).hasSize(16);
        assertThat(rule.topology().bucketPlacements()).containsEntry(
                0,
                "t_order@ds0@00"
        ).containsEntry(
                7,
                "t_order@ds1@03"
        ).containsEntry(
                8,
                "t_order@ds0@00"
        ).containsEntry(
                15,
                "t_order@ds1@03"
        );
        assertThat(rule.topology().bucketPlacements().keySet())
                .containsExactlyInAnyOrderElementsOf(allBuckets(16));
    }

    @Test
    void shouldAcceptBucketCountThatIsNotPowerOfTwo() {
        // Given
        LavShardProperties.Layout layout = layout(
                "layout-v1",
                List.of("ds0", "ds1"),
                2,
                10,
                null,
                null,
                null
        );

        // When
        TableRule rule = rule(compiler.compile(conciseProperties(
                new LavShardProperties.Defaults("standard"),
                Map.of("standard", layout),
                Map.of("t_order", conciseTable("user_id"))
        )), "t_order");

        // Then
        assertThat(rule.topology().bucketCount()).isEqualTo(10);
        assertThat(rule.topology().bucketPlacements()).hasSize(10);
    }

    @Test
    void shouldUseTableLayoutOverrideInsteadOfDefaultLayout() {
        // Given
        LavShardProperties.Layout defaultLayout = layout(
                "default-v1",
                List.of("ds0"),
                1,
                8,
                null,
                null,
                null
        );
        LavShardProperties.Layout auditLayout = layout(
                "audit-v2",
                List.of("ds1"),
                2,
                8,
                null,
                null,
                null
        );
        LavShardProperties.Table table = new LavShardProperties.Table(
                "",
                "tenant_id",
                "audit",
                null,
                null
        );

        // When
        TableRule rule = rule(compiler.compile(conciseProperties(
                new LavShardProperties.Defaults("standard"),
                Map.of("standard", defaultLayout, "audit", auditLayout),
                Map.of("t_audit", table)
        )), "t_audit");

        // Then
        assertThat(rule.ruleVersion()).isEqualTo("audit-v2/t_audit/rule");
        assertThat(rule.topology().nodes().values())
                .allSatisfy(node -> assertThat(node.dataSourceId())
                        .isEqualTo("ds1"));
        assertThat(rule.topology().nodes().values())
                .extracting(node -> node.actualTable().table())
                .containsExactlyInAnyOrder("t_audit_00", "t_audit_01");
    }

    @Test
    void shouldApplyCustomAlgorithmAndTableSuffix() {
        // Given
        LavShardProperties.Layout layout = layout(
                "custom-v1",
                List.of("ds0"),
                2,
                8,
                new LavShardProperties.Algorithm(
                        "hash_mod",
                        "murmur3_32_v1"
                ),
                new LavShardProperties.TableSuffix("__", 1, 3),
                LavShardProperties.PlacementStrategy.ROUND_ROBIN
        );

        // When
        TableRule rule = rule(compiler.compile(conciseProperties(
                new LavShardProperties.Defaults("custom"),
                Map.of("custom", layout),
                Map.of("t_order", conciseTable("buyer_id"))
        )), "t_order");

        // Then
        assertThat(rule.shardingColumn()).isEqualTo("buyer_id");
        assertThat(rule.topology().nodes().values())
                .extracting(node -> node.actualTable().table())
                .containsExactlyInAnyOrder("t_order__001", "t_order__002");
    }

    @Test
    void shouldBindDisabledTableSuffixForDatabaseOnlyLayout() {
        // Given
        Map<String, Object> source = baseBindingProperties();
        source.put("lavshard.defaults.layout", "database-only");
        source.put("lavshard.layouts[database-only].version", "database-v1");
        source.put("lavshard.layouts[database-only].data-source-ids[0]", "ds0");
        source.put("lavshard.layouts[database-only].data-source-ids[1]", "ds1");
        source.put("lavshard.layouts[database-only].tables-per-data-source", "1");
        source.put("lavshard.layouts[database-only].bucket-count", "16");
        source.put("lavshard.layouts[database-only].table-suffix.enabled", "false");
        source.put("lavshard.tables[t_order].sharding-column", "user_id");

        // When
        LavShardProperties properties = bind(source);

        // Then
        LavShardProperties.TableSuffix suffix = properties.layouts()
                .get("database-only")
                .tableSuffix();
        assertThat(suffix.enabled()).isFalse();
        assertThat(suffix.separator()).isEqualTo("_");
        assertThat(suffix.start()).isZero();
        assertThat(suffix.width()).isEqualTo(2);
    }

    @Test
    void shouldExpandDatabaseOnlyLayoutToSameTableNameOnEveryDataSource() {
        // Given
        LavShardProperties.Layout layout = layout(
                "database-v1",
                List.of("ds0", "ds1"),
                1,
                16,
                null,
                new LavShardProperties.TableSuffix(false, "_", 0, 2),
                null
        );

        // When
        TableRule rule = rule(compiler.compile(conciseProperties(
                new LavShardProperties.Defaults("database-only"),
                Map.of("database-only", layout),
                Map.of("t_order", conciseTable("user_id"))
        )), "t_order");

        // Then
        assertThat(rule.topology().nodes()).containsOnlyKeys(
                "t_order@ds0",
                "t_order@ds1"
        );
        assertThat(rule.topology().nodes().values())
                .extracting(
                        node -> node.dataSourceId(),
                        node -> node.actualTable().table()
                )
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("ds0", "t_order"),
                        org.assertj.core.groups.Tuple.tuple("ds1", "t_order")
                );
        assertThat(rule.topology().bucketPlacements())
                .containsEntry(0, "t_order@ds0")
                .containsEntry(1, "t_order@ds1")
                .containsEntry(14, "t_order@ds0")
                .containsEntry(15, "t_order@ds1");
    }

    @Test
    void shouldRouteDatabaseOnlyLayoutWithoutRewritingLogicalTableName() {
        // Given
        LavShardProperties.Layout layout = layout(
                "database-v1",
                List.of("ds0", "ds1"),
                1,
                16,
                null,
                new LavShardProperties.TableSuffix(false, "_", 0, 2),
                null
        );
        TableRule rule = rule(compiler.compile(conciseProperties(
                new LavShardProperties.Defaults("database-only"),
                Map.of("database-only", layout),
                Map.of("t_order", conciseTable("user_id"))
        )), "t_order");
        SingleShardRouter router = new SingleShardRouter(
                ShardAlgorithmRegistry.withBuiltInAlgorithms()
        );

        // When / Then
        for (long shardValue = 0; shardValue < 100; shardValue++) {
            var target = router.route(rule, ShardValue.of(shardValue));
            assertThat(target.node().dataSourceId()).isIn("ds0", "ds1");
            assertThat(target.node().actualTable().table()).isEqualTo("t_order");
        }
    }

    @Test
    void shouldRejectDisabledSuffixWhenDataSourceContainsMultipleTables() {
        assertLayoutFailure(
                layout(
                        "database-v1",
                        List.of("ds0", "ds1"),
                        2,
                        16,
                        null,
                        new LavShardProperties.TableSuffix(false, "_", 0, 2),
                        null
                ),
                "lavshard.layouts.standard.tables-per-data-source must equal "
                        + "one when table-suffix.enabled is false"
        );
    }

    @Test
    void shouldRetainLegacyTableSuffixConstructorWithSuffixEnabled() {
        LavShardProperties.TableSuffix suffix =
                new LavShardProperties.TableSuffix("__", 1, 3);

        assertThat(suffix.enabled()).isTrue();
        assertThat(suffix.separator()).isEqualTo("__");
        assertThat(suffix.start()).isEqualTo(1);
        assertThat(suffix.width()).isEqualTo(3);
    }

    @Test
    void shouldCompileMultipleTablesFromOneLayout() {
        // Given
        LavShardProperties properties = conciseProperties(
                new LavShardProperties.Defaults("standard-2x4"),
                Map.of("standard-2x4", standardLayout()),
                Map.of(
                        "t_order", conciseTable("user_id"),
                        "t_payment", conciseTable("order_id")
                )
        );

        // When
        LavShardConfigurationSnapshot snapshot = compiler.compile(properties);
        TableRule order = rule(snapshot, "t_order");
        TableRule payment = rule(snapshot, "t_payment");

        // Then
        assertThat(order.shardingColumn()).isEqualTo("user_id");
        assertThat(payment.shardingColumn()).isEqualTo("order_id");
        assertThat(order.topology().nodes().values())
                .extracting(node -> node.actualTable().table())
                .allMatch(name -> name.startsWith("t_order_"));
        assertThat(payment.topology().nodes().values())
                .extracting(node -> node.actualTable().table())
                .allMatch(name -> name.startsWith("t_payment_"));
    }

    @Test
    void shouldProduceDeterministicTopologyIndependentOfDataSourceMapOrder() {
        // Given
        Map<String, LavShardProperties.DataSourceReference> first =
                new LinkedHashMap<>();
        first.put("ds0", new LavShardProperties.DataSourceReference("bean0"));
        first.put("ds1", new LavShardProperties.DataSourceReference("bean1"));
        Map<String, LavShardProperties.DataSourceReference> reversed =
                new LinkedHashMap<>();
        reversed.put("ds1", new LavShardProperties.DataSourceReference("bean1"));
        reversed.put("ds0", new LavShardProperties.DataSourceReference("bean0"));
        LavShardProperties left = conciseProperties(first);
        LavShardProperties right = conciseProperties(reversed);

        // When
        TableRule leftRule = rule(compiler.compile(left), "t_order");
        TableRule rightRule = rule(compiler.compile(right), "t_order");

        // Then
        assertThat(leftRule).isEqualTo(rightRule);
        assertThat(leftRule.topology().bucketPlacements())
                .isEqualTo(rightRule.topology().bucketPlacements());
    }

    @Test
    void shouldRouteUsingExpandedTopology() {
        // Given
        TableRule rule = rule(compiler.compile(conciseProperties(
                new LavShardProperties.Defaults("standard-2x4"),
                Map.of("standard-2x4", standardLayout()),
                Map.of("t_order", conciseTable("user_id"))
        )), "t_order");
        SingleShardRouter router = new SingleShardRouter(
                new ShardAlgorithmRegistry(
                        List.of(new Murmur3HashShardAlgorithm())
                )
        );

        // When
        var target = router.route(rule, ShardValue.of(1001L));

        // Then
        assertThat(target.bucket().value()).isBetween(0, 15);
        assertThat(target.node().dataSourceId()).isIn("ds0", "ds1");
        assertThat(target.node().actualTable().table())
                .matches("t_order_0[0-3]");
        assertThat(target.node())
                .isEqualTo(rule.topology().nodeForBucket(target.bucket().value()));
    }

    @Test
    void shouldKeepExplicitTopologyCompatibleEvenWhenDefaultLayoutExists() {
        // Given
        LavShardProperties.Table explicit = explicitTable();

        // When
        TableRule rule = rule(compiler.compile(conciseProperties(
                new LavShardProperties.Defaults("standard-2x4"),
                Map.of("standard-2x4", standardLayout()),
                Map.of("t_legacy", explicit)
        )), "t_legacy");

        // Then
        assertThat(rule.ruleVersion()).isEqualTo("legacy-rule-v1");
        assertThat(rule.topology().version()).isEqualTo("legacy-topology-v1");
        assertThat(rule.topology().nodes()).containsOnlyKeys("legacy-node");
        assertThat(rule.topology().nodeForBucket(0).actualTable().table())
                .isEqualTo("old_order_a");
    }

    @Test
    void shouldRetainLegacyFourArgumentPropertiesConstructor() {
        // Given
        LavShardProperties properties = new LavShardProperties(
                true,
                new LavShardProperties.Integration("ds0", Set.of()),
                dataSources(),
                Map.of("t_legacy", explicitTable())
        );

        // When
        TableRule rule = rule(compiler.compile(properties), "t_legacy");

        // Then
        assertThat(properties.layouts()).isEmpty();
        assertThat(properties.defaults().layout()).isEmpty();
        assertThat(rule.ruleVersion()).isEqualTo("legacy-rule-v1");
    }

    @Test
    void shouldRejectTableWithLayoutAndExplicitTopology() {
        LavShardProperties.Table conflicting = new LavShardProperties.Table(
                "legacy-rule-v1",
                "user_id",
                "standard-2x4",
                new LavShardProperties.Algorithm(
                        "hash_mod",
                        "murmur3_32_v1"
                ),
                explicitTable().topology()
        );

        assertFailure(
                conciseProperties(
                        new LavShardProperties.Defaults(""),
                        Map.of("standard-2x4", standardLayout()),
                        Map.of("t_order", conflicting)
                ),
                "lavshard.tables.t_order must configure either layout or "
                        + "explicit topology, not both"
        );
    }

    @Test
    void shouldRejectTableWithoutResolvableLayoutOrExplicitTopology() {
        assertFailure(
                conciseProperties(
                        new LavShardProperties.Defaults(""),
                        Map.of(),
                        Map.of("t_order", conciseTable("user_id"))
                ),
                "lavshard.tables.t_order must reference a layout or configure "
                        + "explicit topology"
        );
    }

    @Test
    void shouldRejectUnknownDefaultLayout() {
        assertFailure(
                conciseProperties(
                        new LavShardProperties.Defaults("missing"),
                        Map.of("standard", standardLayout()),
                        Map.of("t_order", conciseTable("user_id"))
                ),
                "lavshard.defaults.layout references unknown layout: missing"
        );
    }

    @Test
    void shouldRejectUnknownTableLayout() {
        LavShardProperties.Table table = new LavShardProperties.Table(
                "",
                "user_id",
                "missing",
                null,
                null
        );
        assertFailure(
                conciseProperties(
                        new LavShardProperties.Defaults("standard"),
                        Map.of("standard", standardLayout()),
                        Map.of("t_order", table)
                ),
                "lavshard.tables.t_order.layout references unknown layout: missing"
        );
    }

    @Test
    void shouldRejectBlankOrDuplicateLayoutDataSourceIds() {
        assertLayoutFailure(
                layout("v1", List.of("ds0", " "), 1, 8, null, null, null),
                "lavshard.layouts.standard.data-source-ids must not contain "
                        + "blank dataSourceId"
        );
        assertLayoutFailure(
                layout(
                        "v1",
                        List.of("ds0", "ds0"),
                        1,
                        8,
                        null,
                        null,
                        null
                ),
                "lavshard.layouts.standard.data-source-ids must not contain "
                        + "duplicates: ds0"
        );
    }

    @Test
    void shouldRejectEmptyOrUnknownLayoutDataSources() {
        assertLayoutFailure(
                layout("v1", List.of(), 1, 8, null, null, null),
                "lavshard.layouts.standard.data-source-ids must not be empty"
        );
        assertLayoutFailure(
                layout(
                        "v1",
                        List.of("missing"),
                        1,
                        8,
                        null,
                        null,
                        null
                ),
                "lavshard.layouts.standard.data-source-ids references unknown "
                        + "dataSourceId: missing"
        );
    }

    @Test
    void shouldRejectInvalidLayoutCountsAndOverflow() {
        assertLayoutFailure(
                layout("v1", List.of("ds0"), 0, 8, null, null, null),
                "lavshard.layouts.standard.tables-per-data-source must be "
                        + "greater than zero"
        );
        assertLayoutFailure(
                layout("v1", List.of("ds0"), 1, 0, null, null, null),
                "lavshard.layouts.standard.bucket-count must be greater than zero"
        );
        assertLayoutFailure(
                layout(
                        "v1",
                        List.of("ds0", "ds1"),
                        Integer.MAX_VALUE,
                        Integer.MAX_VALUE,
                        null,
                        null,
                        null
                ),
                "lavshard.layouts.standard physical node count exceeds supported "
                        + "integer range"
        );
    }

    @Test
    void shouldRejectBucketCountSmallerThanPhysicalNodeCount() {
        assertLayoutFailure(
                layout(
                        "v1",
                        List.of("ds0", "ds1"),
                        4,
                        7,
                        null,
                        null,
                        null
                ),
                "lavshard.layouts.standard.bucket-count must be greater than or "
                        + "equal to physical node count: 8"
        );
    }

    @Test
    void shouldRejectInvalidTableSuffixConfiguration() {
        assertLayoutFailure(
                layout(
                        "v1",
                        List.of("ds0"),
                        1,
                        8,
                        null,
                        new LavShardProperties.TableSuffix("_", -1, 2),
                        null
                ),
                "lavshard.layouts.standard.table-suffix.start must not be negative"
        );
        assertLayoutFailure(
                layout(
                        "v1",
                        List.of("ds0"),
                        1,
                        8,
                        null,
                        new LavShardProperties.TableSuffix("_", 0, 0),
                        null
                ),
                "lavshard.layouts.standard.table-suffix.width must be greater "
                        + "than zero"
        );
    }

    @Test
    void shouldRejectBlankLayoutNameVersionAndShardColumn() {
        assertFailure(
                conciseProperties(
                        new LavShardProperties.Defaults(" "),
                        Map.of(),
                        Map.of("t_order", conciseTable("user_id"))
                ),
                "lavshard.defaults.layout must not be blank"
        );
        assertFailure(
                conciseProperties(
                        new LavShardProperties.Defaults("standard"),
                        Map.of(" ", standardLayout()),
                        Map.of("t_order", conciseTable("user_id"))
                ),
                "lavshard.layouts must not contain blank layout name"
        );
        assertLayoutFailure(
                layout(" ", List.of("ds0"), 1, 8, null, null, null),
                "lavshard.layouts.standard.version must not be blank"
        );
        assertFailure(
                conciseProperties(
                        new LavShardProperties.Defaults("standard"),
                        Map.of("standard", standardLayout()),
                        Map.of("t_order", conciseTable(" "))
                ),
                "lavshard.tables.t_order.sharding-column must not be blank"
        );
    }

    @Test
    void shouldSnapshotMutableLayoutCollections() {
        // Given
        List<String> sourceIds = new ArrayList<>(List.of("ds0", "ds1"));
        Map<String, LavShardProperties.Layout> layouts = new HashMap<>();
        layouts.put(
                "standard",
                layout("v1", sourceIds, 1, 8, null, null, null)
        );

        // When
        LavShardProperties properties = conciseProperties(
                new LavShardProperties.Defaults("standard"),
                layouts,
                Map.of("t_order", conciseTable("user_id"))
        );
        sourceIds.add("ds-mutation");
        layouts.clear();

        // Then
        assertThat(properties.layouts()).containsOnlyKeys("standard");
        assertThat(properties.layouts().get("standard").dataSourceIds())
                .containsExactly("ds0", "ds1");
        assertThatThrownBy(() -> properties.layouts().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> properties.layouts().get("standard")
                .dataSourceIds().add("ds2"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private void assertLayoutFailure(
            LavShardProperties.Layout layout,
            String message
    ) {
        assertFailure(
                conciseProperties(
                        new LavShardProperties.Defaults("standard"),
                        Map.of("standard", layout),
                        Map.of("t_order", conciseTable("user_id"))
                ),
                message
        );
    }

    private void assertFailure(
            LavShardProperties properties,
            String message
    ) {
        assertThatThrownBy(() -> compiler.compile(properties))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage(message);
    }

    private static LavShardProperties conciseProperties(
            Map<String, LavShardProperties.DataSourceReference> dataSources
    ) {
        return new LavShardProperties(
                true,
                new LavShardProperties.Integration("ds0", Set.of()),
                dataSources,
                new LavShardProperties.Defaults("standard-2x4"),
                Map.of("standard-2x4", standardLayout()),
                Map.of("t_order", conciseTable("user_id"))
        );
    }

    private static LavShardProperties conciseProperties(
            LavShardProperties.Defaults defaults,
            Map<String, LavShardProperties.Layout> layouts,
            Map<String, LavShardProperties.Table> tables
    ) {
        return new LavShardProperties(
                true,
                new LavShardProperties.Integration("ds0", Set.of()),
                dataSources(),
                defaults,
                layouts,
                tables
        );
    }

    private static Map<String, LavShardProperties.DataSourceReference>
    dataSources() {
        return Map.of(
                "ds0", new LavShardProperties.DataSourceReference("bean0"),
                "ds1", new LavShardProperties.DataSourceReference("bean1")
        );
    }

    private static LavShardProperties.Layout standardLayout() {
        return layout(
                "layout-v1",
                List.of("ds0", "ds1"),
                4,
                16,
                null,
                null,
                null
        );
    }

    private static LavShardProperties.Layout layout(
            String version,
            List<String> dataSourceIds,
            int tablesPerDataSource,
            int bucketCount,
            LavShardProperties.Algorithm algorithm,
            LavShardProperties.TableSuffix tableSuffix,
            LavShardProperties.PlacementStrategy placementStrategy
    ) {
        return new LavShardProperties.Layout(
                version,
                dataSourceIds,
                tablesPerDataSource,
                bucketCount,
                algorithm,
                tableSuffix,
                placementStrategy
        );
    }

    private static LavShardProperties.Table conciseTable(
            String shardingColumn
    ) {
        return new LavShardProperties.Table(
                "",
                shardingColumn,
                "",
                null,
                null
        );
    }

    private static LavShardProperties.Table explicitTable() {
        return new LavShardProperties.Table(
                "legacy-rule-v1",
                "tenant_id",
                new LavShardProperties.Algorithm(
                        "hash_mod",
                        "murmur3_32_v1"
                ),
                new LavShardProperties.Topology(
                        "legacy-topology-v1",
                        1,
                        Map.of(0, "legacy-node"),
                        Map.of(
                                "legacy-node",
                                new LavShardProperties.Node(
                                        "ds0",
                                        "old_order_a"
                                )
                        )
                )
        );
    }

    private static TableRule rule(
            LavShardConfigurationSnapshot snapshot,
            String logicalTable
    ) {
        return snapshot.ruleSnapshot().find(
                new QualifiedTableName(logicalTable)
        ).orElseThrow();
    }

    private static Set<Integer> allBuckets(int bucketCount) {
        Set<Integer> buckets = new HashSet<>();
        for (int bucket = 0; bucket < bucketCount; bucket++) {
            buckets.add(bucket);
        }
        return Set.copyOf(buckets);
    }

    private static Map<String, Object> baseBindingProperties() {
        Map<String, Object> source = new HashMap<>();
        source.put("lavshard.integration.default-data-source", "ds0");
        source.put("lavshard.data-sources[ds0].bean-name", "bean0");
        source.put("lavshard.data-sources[ds1].bean-name", "bean1");
        return source;
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
}
