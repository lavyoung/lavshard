package io.github.lavyoung.lavshard.mybatis.internal.executor;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MyBatis 参数值提取契约。
 *
 * <p>提取顺序必须与 {@link BoundSql#getParameterMappings()} 一致；
 * 动态 SQL 产生的 additional parameter 优先于原始参数对象。</p>
 */
class MyBatisParameterValueExtractorTest {

    private final Configuration configuration =
            new Configuration();

    private final MyBatisParameterValueExtractor extractor =
            new MyBatisParameterValueExtractor(configuration);

    @Test
    void shouldExtractPojoPropertiesInMappingOrder() {
        BoundSql boundSql = boundSql(
                List.of(
                        mapping("status", String.class),
                        mapping("userId", String.class)
                ),
                new OrderQuery("user-123", "PAID")
        );

        List<Object> values =
                extractor.extract(boundSql);

        assertThat(values)
                .containsExactly("PAID", "user-123");
    }

    @Test
    void shouldExtractMapAndParamStyleValues() {
        Map<String, Object> parameters =
                new LinkedHashMap<>();
        parameters.put("tenantId", 7L);
        parameters.put("userId", "user-123");

        BoundSql boundSql = boundSql(
                List.of(
                        mapping("userId", String.class),
                        mapping("tenantId", Long.class)
                ),
                parameters
        );

        assertThat(extractor.extract(boundSql))
                .containsExactly("user-123", 7L);
    }

    @Test
    void shouldPreferAdditionalParameter() {
        BoundSql boundSql = boundSql(
                List.of(mapping("item", String.class)),
                Map.of("item", "from-parameter-object")
        );
        boundSql.setAdditionalParameter(
                "item",
                "from-additional-parameter"
        );

        assertThat(extractor.extract(boundSql))
                .containsExactly(
                        "from-additional-parameter"
                );
    }

    @Test
    void shouldReadNestedForeachAdditionalParameter() {
        BoundSql boundSql = boundSql(
                List.of(
                        mapping(
                                "__frch_item_0.id",
                                Long.class
                        )
                ),
                Map.of()
        );
        boundSql.setAdditionalParameter(
                "__frch_item_0",
                Map.of("id", 99L)
        );

        assertThat(extractor.extract(boundSql))
                .containsExactly(99L);
    }

    @Test
    void shouldUseScalarParameterForRegisteredTypeHandler() {
        BoundSql boundSql = boundSql(
                List.of(mapping("ignored", String.class)),
                "user-123"
        );

        assertThat(extractor.extract(boundSql))
                .containsExactly("user-123");
    }

    @Test
    void shouldPreserveNullParameterValues() {
        BoundSql boundSql = boundSql(
                List.of(
                        mapping("missing", Object.class),
                        mapping("tenantId", Long.class)
                ),
                Map.of("tenantId", 7L)
        );

        List<Object> values =
                extractor.extract(boundSql);

        assertThat(values)
                .containsExactly(null, 7L);
        assertThatThrownBy(() -> values.add("forbidden"))
                .isInstanceOf(
                        UnsupportedOperationException.class
                );
    }

    @Test
    void shouldReturnNullValuesForNullParameterObject() {
        BoundSql boundSql = boundSql(
                List.of(mapping("userId", String.class)),
                null
        );

        assertThat(extractor.extract(boundSql))
                .containsExactly((Object) null);
    }

    @Test
    void shouldRejectInvalidArguments() {
        assertThatThrownBy(() ->
                new MyBatisParameterValueExtractor(null)
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage(
                        "configuration must not be null"
                );

        assertThatThrownBy(() ->
                extractor.extract(null)
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("boundSql must not be null");
    }

    private BoundSql boundSql(
            List<ParameterMapping> mappings,
            Object parameterObject
    ) {
        return new BoundSql(
                configuration,
                "SELECT 1",
                mappings,
                parameterObject
        );
    }

    private ParameterMapping mapping(
            String property,
            Class<?> javaType
    ) {
        return new ParameterMapping.Builder(
                configuration,
                property,
                javaType
        ).build();
    }

    private record OrderQuery(
            String userId,
            String status
    ) {
    }
}
