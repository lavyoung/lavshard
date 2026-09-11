package io.github.lavyoung.lavshard.mybatis.internal;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlSource;

import java.util.Objects;

/**
 * 使用物理 BoundSql 重建 MyBatis MappedStatement。
 *
 * <p>重建时只替换 SqlSource，其余会影响 SQL 执行、
 * 结果映射、缓存和生成键的元数据必须完整保留。</p>
 *
 * <p>每次路由创建独立的 MappedStatement，不修改原始对象，
 * 避免多个并发请求共享可变的物理 SQL 状态。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public final class MyBatisMappedStatementRewriter {


    public MappedStatement rewrite(
            MappedStatement mappedStatement,
            BoundSql boundSql
    ) {
        Objects.requireNonNull(mappedStatement, "mappedStatement must not be null");
        Objects.requireNonNull(boundSql, "boundSql must not be null");

        SqlSource physicalSqlSource = ignoredParameterObject -> boundSql;

        return new MappedStatement.Builder(
                mappedStatement.getConfiguration(),
                mappedStatement.getId(),
                physicalSqlSource,
                mappedStatement.getSqlCommandType()
        )
                .resource(mappedStatement.getResource())
                .parameterMap(mappedStatement.getParameterMap())
                .resultMaps(mappedStatement.getResultMaps())
                .fetchSize(mappedStatement.getFetchSize())
                .timeout(mappedStatement.getTimeout())
                .statementType(mappedStatement.getStatementType())
                .resultSetType(mappedStatement.getResultSetType())
                .cache(mappedStatement.getCache())
                .flushCacheRequired(mappedStatement.isFlushCacheRequired())
                .useCache(mappedStatement.isUseCache())
                .resultOrdered(mappedStatement.isResultOrdered())
                .keyGenerator(mappedStatement.getKeyGenerator())
                .keyProperty(join(mappedStatement.getKeyProperties()))
                .keyColumn(join(mappedStatement.getKeyColumns()))
                .databaseId(mappedStatement.getDatabaseId())
                .lang(mappedStatement.getLang())
                .resultSets(join(mappedStatement.getResultSets()))
                .dirtySelect(mappedStatement.isDirtySelect())
                .build();
    }

    private static String join(String[] values) {
        return values == null ? null : String.join(",", values);
    }
}
