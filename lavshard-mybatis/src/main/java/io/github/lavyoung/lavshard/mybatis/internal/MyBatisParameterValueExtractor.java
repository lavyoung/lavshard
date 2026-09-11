package io.github.lavyoung.lavshard.mybatis.internal;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 按 MyBatis ParameterMapping 顺序提取 JDBC 参数值。
 *
 * <p>读取顺序与 MyBatis 默认参数处理器保持一致：</p>
 *
 * <ol>
 *     <li>优先读取 BoundSql additional parameters</li>
 *     <li>参数对象为空时返回 null</li>
 *     <li>参数对象有直接 TypeHandler 时使用参数对象本身</li>
 *     <li>其他情况通过 MetaObject 读取属性</li>
 * </ol>
 *
 * <p>该类只做框架参数对象到有序值列表的适配，
 * 不负责分片算法、SQL 分析或参数类型转换。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/9/11
 */
public final class MyBatisParameterValueExtractor {

    private final Configuration configuration;

    /**
     * 创建参数值提取器。
     *
     * @param configuration MyBatis 配置
     * @throws NullPointerException configuration 为空时抛出
     */
    public MyBatisParameterValueExtractor(Configuration configuration) {
        this.configuration = Objects.requireNonNull(
                configuration,
                "configuration must not be null"
        );
    }

    /**
     * 按 ParameterMapping 顺序提取真实参数值。
     *
     * @param boundSql MyBatis 已生成的 BoundSql
     * @return 不可修改且允许包含 null 的有序参数列表
     * @throws NullPointerException boundSql 为空时抛出
     */
    public List<Object> extract(BoundSql boundSql) {
        Objects.requireNonNull(
                boundSql,
                "boundSql must not be null"
        );

        Object parameterObject = boundSql.getParameterObject();
        TypeHandlerRegistry typeHandlerRegistry = configuration.getTypeHandlerRegistry();
        boolean directTypeHandler = parameterObject != null
                && typeHandlerRegistry.hasTypeHandler(parameterObject.getClass());
        MetaObject metaObject = parameterObject == null || directTypeHandler
                ? null
                : configuration.newMetaObject(parameterObject);

        List<Object> values = new ArrayList<>(boundSql.getParameterMappings().size());

        for (ParameterMapping mapping : boundSql.getParameterMappings()) {
            String property = mapping.getProperty();

            Object value;

            if (boundSql.hasAdditionalParameter(property)) {
                value = boundSql.getAdditionalParameter(property);
            } else if (parameterObject == null) {
                value = null;
            } else if (directTypeHandler) {
                value = parameterObject;
            } else {
                value = metaObject.getValue(property);
            }
            values.add(value);
        }

        // 结果不可修改。能保留合法的 null 参数值
        return Collections.unmodifiableList(values);
    }
}
