package io.github.lavyoung.lavshard.starter.autoconfigure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starter 自动配置发现文件和配置元数据的发布契约。
 */
class LavShardStarterMetadataTest {

    private static final String AUTO_CONFIGURATION_IMPORTS =
            "META-INF/spring/org.springframework.boot.autoconfigure."
                    + "AutoConfiguration.imports";
    private static final String CONFIGURATION_METADATA =
            "META-INF/spring-configuration-metadata.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldPublishAutoConfigurationsInDependencyOrder()
            throws IOException {
        // Given
        String imports = readTextResource(AUTO_CONFIGURATION_IMPORTS);

        // When
        List<String> configurationClasses = imports.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();

        // Then
        assertThat(configurationClasses).containsExactly(
                LavShardAutoConfiguration.class.getName(),
                LavShardDataSourceAutoConfiguration.class.getName(),
                LavShardHealthAutoConfiguration.class.getName()
        );
        assertThat(configurationClasses).doesNotHaveDuplicates();
    }

    @Test
    void shouldPublishCompleteConfigurationMetadata() throws IOException {
        // Given
        JsonNode metadata = readJsonResource(CONFIGURATION_METADATA);

        // When
        Map<String, JsonNode> properties = nodesByName(
                metadata.path("properties")
        );

        // Then
        assertThat(properties).containsKeys(
                "lavshard.enabled",
                "lavshard.integration.default-data-source",
                "lavshard.integration.default-transaction-isolation",
                "lavshard.integration.managed-mapper-packages",
                "lavshard.integration.ordinary-tables",
                "lavshard.data-sources",
                "lavshard.defaults",
                "lavshard.layouts",
                "lavshard.tables"
        );
        assertThat(properties.values()).allSatisfy(property ->
                assertThat(property.path("description").asText())
                        .as(property.path("name").asText())
                        .isNotBlank()
        );
        assertThat(properties.get("lavshard.enabled")
                .path("defaultValue").asBoolean()).isTrue();
        assertThat(properties.get(
                "lavshard.integration.default-transaction-isolation"
        ).path("defaultValue").asText()).isEqualTo("repeatable-read");
        assertThat(properties.get(
                "lavshard.data-sources"
        ).path("type").asText()).contains(
                "LavShardProperties$DataSourceReference"
        );
        assertThat(properties.get(
                "lavshard.layouts"
        ).path("type").asText()).contains("LavShardProperties$Layout");
        assertThat(properties.get(
                "lavshard.tables"
        ).path("type").asText()).contains("LavShardProperties$Table");
    }

    @Test
    void shouldPublishTransactionIsolationValueHints() throws IOException {
        // Given
        JsonNode metadata = readJsonResource(CONFIGURATION_METADATA);

        // When
        JsonNode isolationHint = nodesByName(metadata.path("hints")).get(
                "lavshard.integration.default-transaction-isolation"
        );

        // Then
        assertThat(isolationHint).isNotNull();
        assertThat(StreamSupport.stream(
                isolationHint.path("values").spliterator(),
                false
        ).map(value -> value.path("value").asText()).toList())
                .containsExactly(
                        "read-uncommitted",
                        "read-committed",
                        "repeatable-read",
                        "serializable"
                );
    }

    @Test
    void shouldPublishCompleteMapValueLeafMetadata() throws IOException {
        // Given
        JsonNode metadata = readJsonResource(CONFIGURATION_METADATA);

        // When
        Map<String, JsonNode> properties = nodesByName(
                metadata.path("properties")
        );

        // Then
        assertThat(properties).containsKeys(
                "lavshard.data-sources.*.bean-name",
                "lavshard.data-sources.*.managed.url",
                "lavshard.data-sources.*.managed.username",
                "lavshard.data-sources.*.managed.password",
                "lavshard.data-sources.*.managed.driver-class-name",
                "lavshard.data-sources.*.managed.maximum-pool-size",
                "lavshard.data-sources.*.managed.minimum-idle",
                "lavshard.data-sources.*.managed.connection-timeout",
                "lavshard.layouts.*.version",
                "lavshard.layouts.*.data-source-ids",
                "lavshard.layouts.*.tables-per-data-source",
                "lavshard.layouts.*.bucket-count",
                "lavshard.layouts.*.algorithm.name",
                "lavshard.layouts.*.algorithm.hash-version",
                "lavshard.layouts.*.table-suffix.enabled",
                "lavshard.layouts.*.table-suffix.separator",
                "lavshard.layouts.*.table-suffix.start",
                "lavshard.layouts.*.table-suffix.width",
                "lavshard.layouts.*.placement-strategy",
                "lavshard.tables.*.rule-version",
                "lavshard.tables.*.sharding-column",
                "lavshard.tables.*.layout",
                "lavshard.tables.*.algorithm.name",
                "lavshard.tables.*.algorithm.hash-version",
                "lavshard.tables.*.topology.version",
                "lavshard.tables.*.topology.bucket-count",
                "lavshard.tables.*.topology.bucket-placements",
                "lavshard.tables.*.topology.nodes"
        );
        assertThat(properties.entrySet())
                .filteredOn(entry -> entry.getKey().contains(".*."))
                .allSatisfy(entry -> assertThat(
                        entry.getValue().path("description").asText()
                ).as(entry.getKey()).isNotBlank());

        assertProperty(properties, "lavshard.layouts.*.bucket-count",
                "java.lang.Integer", null);
        assertProperty(properties, "lavshard.layouts.*.table-suffix.enabled",
                "java.lang.Boolean", true);
        assertProperty(properties, "lavshard.layouts.*.table-suffix.separator",
                "java.lang.String", "_");
        assertProperty(properties, "lavshard.layouts.*.table-suffix.start",
                "java.lang.Integer", 0);
        assertProperty(properties, "lavshard.layouts.*.table-suffix.width",
                "java.lang.Integer", 2);
        assertProperty(properties,
                "lavshard.data-sources.*.managed.maximum-pool-size",
                "java.lang.Integer", 10);
        assertProperty(properties,
                "lavshard.data-sources.*.managed.connection-timeout",
                "java.lang.Long", 30000L);
    }

    @Test
    void shouldPublishLayoutAlgorithmAndPlacementValueHints()
            throws IOException {
        // Given
        JsonNode metadata = readJsonResource(CONFIGURATION_METADATA);

        // When
        Map<String, JsonNode> hints = nodesByName(metadata.path("hints"));

        // Then
        assertHintValues(
                hints,
                "lavshard.layouts.*.placement-strategy",
                "round-robin"
        );
        assertHintValues(
                hints,
                "lavshard.layouts.*.algorithm.name",
                "hash_mod"
        );
        assertHintValues(
                hints,
                "lavshard.layouts.*.algorithm.hash-version",
                "murmur3_32_v1"
        );
        assertHintValues(
                hints,
                "lavshard.tables.*.algorithm.name",
                "hash_mod"
        );
        assertHintValues(
                hints,
                "lavshard.tables.*.algorithm.hash-version",
                "murmur3_32_v1"
        );
    }

    private static void assertProperty(
            Map<String, JsonNode> properties,
            String name,
            String type,
            Object defaultValue
    ) {
        JsonNode property = properties.get(name);
        assertThat(property).as(name).isNotNull();
        assertThat(property.path("type").asText()).as(name).isEqualTo(type);
        if (defaultValue != null) {
            assertThat(property.path("defaultValue"))
                    .as(name)
                    .isEqualTo(OBJECT_MAPPER.valueToTree(defaultValue));
        }
    }

    private static void assertHintValues(
            Map<String, JsonNode> hints,
            String name,
            String... expectedValues
    ) {
        JsonNode hint = hints.get(name);
        assertThat(hint).as(name).isNotNull();
        assertThat(StreamSupport.stream(
                hint.path("values").spliterator(),
                false
        ).map(value -> value.path("value").asText()).toList())
                .containsExactly(expectedValues);
    }

    private static Map<String, JsonNode> nodesByName(JsonNode nodes) {
        Map<String, JsonNode> indexed = new LinkedHashMap<>();
        nodes.forEach(node -> indexed.put(node.path("name").asText(), node));
        return Map.copyOf(indexed);
    }

    private static JsonNode readJsonResource(String resource)
            throws IOException {
        try (InputStream input = resourceStream(resource)) {
            return OBJECT_MAPPER.readTree(input);
        }
    }

    private static String readTextResource(String resource)
            throws IOException {
        try (InputStream input = resourceStream(resource)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static InputStream resourceStream(String resource) {
        return Objects.requireNonNull(
                LavShardStarterMetadataTest.class.getClassLoader()
                        .getResourceAsStream(resource),
                "Missing classpath resource: " + resource
        );
    }
}
