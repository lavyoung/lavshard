package io.github.lavyoung.lavshard.starter;

import io.github.lavyoung.lavshard.core.api.algorithm.ShardAlgorithm;
import io.github.lavyoung.lavshard.core.api.exception.ConfigurationException;
import io.github.lavyoung.lavshard.core.internal.algorithm.Murmur3HashShardAlgorithm;
import io.github.lavyoung.lavshard.core.internal.algorithm.ShardAlgorithmRegistry;
import io.github.lavyoung.lavshard.core.internal.route.SqlRouteEngine;
import io.github.lavyoung.lavshard.mybatis.internal.LavShardExecutorInterceptor;
import io.github.lavyoung.lavshard.mybatis.internal.MyBatisRouteContext;
import io.github.lavyoung.lavshard.starter.support.SpringShardContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * LavShard 核心路由基础设施自动配置。
 *
 * <p>该配置负责把外部配置编译为不可变运行时快照，并装配算法注册表、
 * Core 路由引擎、Spring 事务路由守卫和 MyBatis Executor 拦截器。</p>
 *
 * <p>数据源 Bean 解析和延迟路由数据源替换不属于本配置阶段，
 * 将由后续数据源自动配置闭环负责。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/09/12
 */
@AutoConfiguration
@EnableConfigurationProperties(LavShardProperties.class)
@ConditionalOnProperty(
        prefix = "lavshard",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class LavShardAutoConfiguration {

    /**
     * 创建启动期配置编译器。
     *
     * @return LavShard 配置编译器
     */
    @Bean
    @ConditionalOnMissingBean
    public LavShardConfigurationCompiler lavShardConfigurationCompiler() {
        return new LavShardConfigurationCompiler();
    }

    /**
     * 在容器启动阶段编译不可变配置快照。
     *
     * @param properties 外部配置绑定结果
     * @param compiler   配置编译器
     * @return 不可变运行时配置快照
     */
    @Bean
    @ConditionalOnMissingBean
    public LavShardConfigurationSnapshot lavShardConfigurationSnapshot(
            LavShardProperties properties,
            LavShardConfigurationCompiler compiler
    ) {
        return compiler.compile(properties);
    }

    /**
     * 合并内置算法与应用提供的扩展算法。
     *
     * <p>内置算法始终首先注册。任何同名算法都会由注册表明确拒绝，
     * 不允许通过不透明的 Bean 顺序覆盖持久化 Hash 语义。</p>
     *
     * @param customAlgorithms 应用提供的分片算法
     * @return 不可变算法注册表
     * @throws IllegalArgumentException 算法名称重复时抛出
     */
    @Bean
    @ConditionalOnMissingBean
    public ShardAlgorithmRegistry lavShardAlgorithmRegistry(ObjectProvider<ShardAlgorithm> customAlgorithms) {
        List<ShardAlgorithm> algorithms = Stream.concat(Stream.of(new Murmur3HashShardAlgorithm()),
                customAlgorithms.orderedStream()
        ).toList();

        return new ShardAlgorithmRegistry(algorithms);
    }

    /**
     * 创建校验器并在启动阶段校验全部规则的算法配置。
     *
     * @param snapshot          不可变配置快照
     * @param algorithmRegistry 算法注册表
     * @return 已完成校验的配置校验器
     * @throws ConfigurationException 规则引用未知算法或算法配置不兼容时抛出
     */
    @Bean
    @ConditionalOnMissingBean
    public LavShardAlgorithmConfigurationValidator lavShardAlgorithmConfigurationValidator(LavShardConfigurationSnapshot snapshot,
                                                                                           ShardAlgorithmRegistry algorithmRegistry) {
        LavShardAlgorithmConfigurationValidator validator = new LavShardAlgorithmConfigurationValidator();
        validator.validate(snapshot.ruleSnapshot(), algorithmRegistry);

        return validator;
    }

    /**
     * 根据算法注册表和已编译集成配置创建 SQL 路由引擎。
     *
     * @param algorithmRegistry 算法注册表
     * @param snapshot          不可变配置快照
     * @return SQL 统一路由引擎
     */
    @Bean
    @ConditionalOnMissingBean
    public SqlRouteEngine lavShardSqlRouteEngine(ShardAlgorithmRegistry algorithmRegistry,
                                                 LavShardConfigurationSnapshot snapshot,
                                                 LavShardAlgorithmConfigurationValidator validator) {
        Objects.requireNonNull(
                validator,
                "configurationValidator must not be null"
        );
        return new SqlRouteEngine(
                algorithmRegistry,
                snapshot.ordinaryTables(),
                snapshot.defaultDataSourceId()
        );
    }

    /**
     * 创建 Spring 本地事务路由守卫。
     *
     * @return Spring 事务路由上下文
     */
    @Bean
    @ConditionalOnMissingBean
    public SpringShardContext springShardContext() {
        return new SpringShardContext();
    }

    /**
     * 创建 MyBatis 线程路由上下文并接入 Spring 事务守卫。
     *
     * @param springShardContext Spring 事务路由守卫
     * @return MyBatis 路由上下文
     */
    @Bean
    @ConditionalOnMissingBean
    public MyBatisRouteContext myBatisRouteContext(SpringShardContext springShardContext) {
        return new MyBatisRouteContext(springShardContext::validate);
    }

    /**
     * 创建 MyBatis Executor 路由拦截器。
     *
     * <p>规则快照通过 Supplier 提供，以保持拦截器与未来动态快照
     * 切换能力之间的演进接口；当前实现返回启动期不可变快照。</p>
     *
     * @param routeEngine  SQL 路由引擎
     * @param snapshot     不可变配置快照
     * @param routeContext MyBatis 路由上下文
     * @return Executor 路由拦截器
     */
    @Bean
    @ConditionalOnMissingBean
    public LavShardExecutorInterceptor lavShardExecutorInterceptor(
            SqlRouteEngine routeEngine,
            LavShardConfigurationSnapshot snapshot,
            MyBatisRouteContext routeContext
    ) {
        return new LavShardExecutorInterceptor(
                routeEngine,
                snapshot::ruleSnapshot,
                routeContext
        );
    }
}
