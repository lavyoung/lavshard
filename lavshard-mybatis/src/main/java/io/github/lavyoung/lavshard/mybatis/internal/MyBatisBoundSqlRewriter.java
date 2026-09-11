package io.github.lavyoung.lavshard.mybatis.internal;

import io.github.lavyoung.lavshard.core.api.route.SqlRewriteResult;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.reflection.property.PropertyTokenizer;
import org.apache.ibatis.session.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 根据 Core 的 SQL 改写结果重建 MyBatis BoundSql。
 *
 * <p>重建过程完成以下工作：</p>
 *
 * <ol>
 *     <li>应用物理 SQL</li>
 *     <li>根据原始参数下标投影 ParameterMapping</li>
 *     <li>保留原始参数对象</li>
 *     <li>复制仍然被物理 SQL 引用的 additional parameters</li>
 * </ol>
 *
 * <p>该步骤必须发生在 MyBatis 创建 CacheKey 之前。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public final class MyBatisBoundSqlRewriter {

    private final Configuration configuration;

    /**
     * 创建 BoundSql 重建器。
     *
     * @param configuration MyBatis 配置
     * @throws NullPointerException configuration 为空时抛出
     */
    public MyBatisBoundSqlRewriter(
            Configuration configuration
    ) {
        this.configuration = Objects.requireNonNull(
                configuration,
                "configuration must not be null"
        );
    }

    /**
     * 将框架无关的 SQL 改写结果应用到 MyBatis BoundSql。
     *
     * @param boundSql 原始逻辑 BoundSql
     * @param rewrite  Core 生成的物理 SQL 和参数投影
     * @return 重建后的物理 BoundSql
     * @throws NullPointerException     参数为空时抛出
     * @throws IllegalArgumentException 投影下标越界时抛出
     */
    public BoundSql rewrite(
            BoundSql boundSql,
            SqlRewriteResult rewrite
    ) {
        Objects.requireNonNull(boundSql, "boundSql must not be null");
        Objects.requireNonNull(rewrite, "rewrite must not be null");

        List<ParameterMapping> projectedMappings = projectMappings(
                boundSql.getParameterMappings(),
                rewrite.sourceParameterIndexes());

        BoundSql physicalBoundSql = new BoundSql(
                configuration,
                rewrite.sql(),
                projectedMappings,
                boundSql.getParameterObject()
        );

        copyReferencedAdditionalParameters(
                boundSql,
                physicalBoundSql,
                projectedMappings
        );

        return physicalBoundSql;
    }

    private static List<ParameterMapping> projectMappings(
            List<ParameterMapping> sourceMappings,
            List<Integer> sourceIndexes
    ) {
        List<ParameterMapping> projectedMappings =
                new ArrayList<>(sourceIndexes.size());

        for (Integer sourceIndex : sourceIndexes) {
            if (sourceIndex >= sourceMappings.size()) {
                throw new IllegalArgumentException(
                        "source parameter index out of bounds: "
                                + sourceIndex
                );
            }

            projectedMappings.add(sourceMappings.get(sourceIndex));
        }

        return projectedMappings;
    }

    private static void copyReferencedAdditionalParameters(
            BoundSql source,
            BoundSql target,
            List<ParameterMapping> projectedMappings
    ) {
        for (ParameterMapping mapping : projectedMappings) {
            String property = mapping.getProperty();

            if (!source.hasAdditionalParameter(property)) {
                continue;
            }

            String rootProperty = new PropertyTokenizer(property).getName();

            Object rootValue = source.getAdditionalParameter(rootProperty);

            target.setAdditionalParameter(
                    rootProperty,
                    rootValue
            );
        }
    }
}
