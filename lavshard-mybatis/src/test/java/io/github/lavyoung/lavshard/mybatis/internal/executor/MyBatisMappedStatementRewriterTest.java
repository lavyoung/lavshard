package io.github.lavyoung.lavshard.mybatis.internal.executor;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 物理 MappedStatement 重建契约。
 *
 * <p>只允许替换 SqlSource；其余会影响执行、生成键和缓存语义的
 * MappedStatement 元数据必须完整保留。</p>
 */
class MyBatisMappedStatementRewriterTest {

    private final Configuration configuration =
            new Configuration();

    private final MyBatisMappedStatementRewriter rewriter =
            new MyBatisMappedStatementRewriter();

    @Test
    void shouldExposeProvidedPhysicalBoundSql() {
        MappedStatement original = mappedStatement();
        Object parameterObject = Map.of("id", 1001L);
        BoundSql physicalBoundSql = new BoundSql(
                configuration,
                "UPDATE t_order_00 SET status = ? WHERE id = ?",
                List.of(),
                parameterObject
        );

        MappedStatement physical = rewriter.rewrite(
                original,
                physicalBoundSql
        );

        BoundSql resolved =
                physical.getBoundSql(parameterObject);

        assertThat(physical).isNotSameAs(original);
        assertThat(resolved.getSql())
                .isEqualTo(physicalBoundSql.getSql());
        assertThat(resolved.getParameterObject())
                .isSameAs(parameterObject);
        assertThat(resolved.getParameterMappings())
                .containsExactlyElementsOf(
                        physicalBoundSql
                                .getParameterMappings()
                );
    }

    @Test
    void shouldPreserveIdentityAndExecutionMetadata() {
        MappedStatement original = mappedStatement();

        MappedStatement physical = rewriter.rewrite(
                original,
                physicalBoundSql()
        );

        assertThat(physical.getConfiguration())
                .isSameAs(original.getConfiguration());
        assertThat(physical.getId())
                .isEqualTo(original.getId());
        assertThat(physical.getResource())
                .isEqualTo(original.getResource());
        assertThat(physical.getSqlCommandType())
                .isEqualTo(original.getSqlCommandType());
        assertThat(physical.getStatementType())
                .isEqualTo(original.getStatementType());
        assertThat(physical.getResultSetType())
                .isEqualTo(original.getResultSetType());
        assertThat(physical.getFetchSize())
                .isEqualTo(original.getFetchSize());
        assertThat(physical.getTimeout())
                .isEqualTo(original.getTimeout());
    }

    @Test
    void shouldPreserveMappingsCacheAndFlags() {
        MappedStatement original = mappedStatement();

        MappedStatement physical = rewriter.rewrite(
                original,
                physicalBoundSql()
        );

        assertThat(physical.getParameterMap())
                .isSameAs(original.getParameterMap());
        assertThat(physical.getResultMaps())
                .containsExactlyElementsOf(
                        original.getResultMaps()
                );
        assertThat(physical.getCache())
                .isSameAs(original.getCache());
        assertThat(physical.isFlushCacheRequired())
                .isEqualTo(
                        original.isFlushCacheRequired()
                );
        assertThat(physical.isUseCache())
                .isEqualTo(original.isUseCache());
        assertThat(physical.isResultOrdered())
                .isEqualTo(original.isResultOrdered());
        assertThat(physical.isDirtySelect())
                .isEqualTo(original.isDirtySelect());
    }

    @Test
    void shouldPreserveGeneratedKeyAndExtensionMetadata() {
        MappedStatement original = mappedStatement();

        MappedStatement physical = rewriter.rewrite(
                original,
                physicalBoundSql()
        );

        assertThat(physical.getKeyGenerator())
                .isSameAs(original.getKeyGenerator());
        assertThat(physical.getKeyProperties())
                .containsExactly("id", "version");
        assertThat(physical.getKeyColumns())
                .containsExactly("id_col", "version_col");
        assertThat(physical.getDatabaseId())
                .isEqualTo(original.getDatabaseId());
        assertThat(physical.getLang())
                .isSameAs(original.getLang());
        assertThat(physical.getResultSets())
                .containsExactly("first", "second");
    }

    @Test
    void shouldRejectInvalidArguments() {
        assertThatThrownBy(() ->
                rewriter.rewrite(
                        null,
                        physicalBoundSql()
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage(
                        "mappedStatement must not be null"
                );

        assertThatThrownBy(() ->
                rewriter.rewrite(
                        mappedStatement(),
                        null
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage(
                        "boundSql must not be null"
                );
    }

    private MappedStatement mappedStatement() {
        ParameterMap parameterMap =
                new ParameterMap.Builder(
                        configuration,
                        "order-parameter-map",
                        Map.class,
                        List.of()
                ).build();

        ResultMap resultMap =
                new ResultMap.Builder(
                        configuration,
                        "order-result-map",
                        Map.class,
                        List.of()
                ).build();

        Cache cache = new PerpetualCache("order-cache");

        return new MappedStatement.Builder(
                configuration,
                "OrderMapper.update",
                ignored -> new BoundSql(
                        configuration,
                        "UPDATE t_order SET status = ? WHERE id = ?",
                        List.of(),
                        ignored
                ),
                SqlCommandType.UPDATE
        )
                .resource("OrderMapper.xml")
                .parameterMap(parameterMap)
                .resultMaps(List.of(resultMap))
                .fetchSize(200)
                .timeout(30)
                .statementType(StatementType.PREPARED)
                .resultSetType(
                        ResultSetType.SCROLL_INSENSITIVE
                )
                .cache(cache)
                .flushCacheRequired(true)
                .useCache(true)
                .resultOrdered(true)
                .keyGenerator(new RecordingKeyGenerator())
                .keyProperty("id,version")
                .keyColumn("id_col,version_col")
                .databaseId("mysql")
                .lang(configuration.getLanguageDriver(null))
                .resultSets("first,second")
                .dirtySelect(true)
                .build();
    }

    private BoundSql physicalBoundSql() {
        return new BoundSql(
                configuration,
                "UPDATE t_order_00 SET status = ? WHERE id = ?",
                List.of(),
                Map.of("id", 1001L)
        );
    }

    private static final class RecordingKeyGenerator
            implements KeyGenerator {

        @Override
        public void processBefore(
                Executor executor,
                MappedStatement mappedStatement,
                Statement statement,
                Object parameter
        ) {
        }

        @Override
        public void processAfter(
                Executor executor,
                MappedStatement mappedStatement,
                Statement statement,
                Object parameter
        ) {
        }
    }
}
