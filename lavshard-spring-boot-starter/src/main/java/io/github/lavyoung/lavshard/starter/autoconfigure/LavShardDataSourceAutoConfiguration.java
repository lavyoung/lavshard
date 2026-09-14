package io.github.lavyoung.lavshard.starter.autoconfigure;

import io.github.lavyoung.lavshard.core.api.exception.ConfigurationException;
import io.github.lavyoung.lavshard.mybatis.internal.routing.LavShardRoutingDataSource;
import io.github.lavyoung.lavshard.mybatis.internal.routing.MyBatisRouteContext;
import io.github.lavyoung.lavshard.starter.internal.config.LavShardConfigurationSnapshot;
import io.github.lavyoung.lavshard.starter.internal.datasource.LavShardManagedDataSourceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanNotOfRequiredTypeException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * LavShard 延迟路由数据源自动配置。
 *
 * <p>该配置严格按照启动期配置快照解析应用提供的数据源 Bean
 * 和 Starter 托管的数据源，并创建供 MyBatis 与 Spring
 * 事务管理器共同使用的主路由数据源。</p>
 *
 * <p>应用提供的数据源仍由应用管理生命周期；Starter 只负责
 * 创建和关闭 {@code managed} 模式声明的 Hikari 连接池。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/09/12
 */
@AutoConfiguration(
        after = LavShardAutoConfiguration.class,
        before = DataSourceAutoConfiguration.class,
        beforeName = {
                "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration",
                "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration",
                "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration"
        }
)
@ConditionalOnProperty(prefix = "lavshard", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LavShardDataSourceAutoConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(LavShardDataSourceAutoConfiguration.class);

    /**
     * 创建应用统一使用的延迟路由数据源。
     *
     * <p>{@link Primary} 保证存在多个物理 DataSource Bean 时，
     * Spring 事务管理器和 MyBatis 默认选择路由数据源，而不是任意
     * 一个物理连接池。</p>
     *
     * @param snapshot        启动期不可变配置快照
     * @param routeContext    MyBatis 当前线程路由上下文
     * @param beanFactory     Spring Bean 查询入口
     * @param managedRegistry Starter 托管连接池注册表
     * @return 延迟路由数据源
     * @throws ConfigurationException 配置引用的数据源 Bean 不存在
     *                                或类型错误时抛出
     */
    @Bean(name = "lavShardDataSource")
    @Primary
    @ConditionalOnMissingBean(LavShardRoutingDataSource.class)
    public LavShardRoutingDataSource lavShardDataSource(LavShardConfigurationSnapshot snapshot, MyBatisRouteContext routeContext, ConfigurableListableBeanFactory beanFactory, LavShardManagedDataSourceRegistry managedRegistry) {
        Map<String, DataSource> physicalDataSources = resolvePhysicalDataSources(snapshot, beanFactory, managedRegistry);

        LOGGER.info(
                "LavShard routing data source initialized: dataSourceIds={}, defaultDataSourceId={}, managedTableCount={}, ordinaryTableCount={}",
                new TreeSet<>(physicalDataSources.keySet()),
                snapshot.defaultDataSourceId(),
                snapshot.ruleSnapshot().rules().size(),
                snapshot.ordinaryTables().size()
        );

        return new LavShardRoutingDataSource(physicalDataSources, routeContext, snapshot.defaultTransactionIsolation());
    }

    /**
     * 严格解析配置中声明的全部物理数据源。
     *
     * <p>只解析快照明确列出的 Bean 和托管数据源，不收集容器中的
     * 全部 DataSource，防止无关数据源意外成为可路由目标。
     * 方法返回不可变映射。</p>
     *
     * @param snapshot        配置快照
     * @param beanFactory     Spring Bean 查询入口
     * @param managedRegistry Starter 托管连接池注册表
     * @return dataSourceId 到物理 DataSource 的不可变映射
     * @throws ConfigurationException Bean 不存在或类型错误时抛出
     */
    private static Map<String, DataSource> resolvePhysicalDataSources(LavShardConfigurationSnapshot snapshot, ConfigurableListableBeanFactory beanFactory, LavShardManagedDataSourceRegistry managedRegistry) {
        Map<String, DataSource> resolved = new LinkedHashMap<>(managedRegistry.dataSources());

        snapshot.dataSourceBeanNames().forEach((dataSourceId, beanName) -> resolved.put(dataSourceId, resolveDataSource(dataSourceId, beanName, beanFactory)));

        return Map.copyOf(resolved);
    }

    /**
     * 按配置的 Bean 名称解析一个物理数据源。
     *
     * @param dataSourceId LavShard 数据源标识
     * @param beanName     Spring Bean 名称
     * @param beanFactory  Spring Bean 查询入口
     * @return 对应的物理 DataSource
     * @throws ConfigurationException Bean 不存在或类型错误时抛出
     */
    private static DataSource resolveDataSource(String dataSourceId, String beanName, ConfigurableListableBeanFactory beanFactory) {
        if (!beanFactory.containsBean(beanName)) {
            throw new ConfigurationException("Configured DataSource bean does not exist: " + "dataSourceId=" + dataSourceId + ", beanName=" + beanName);
        }

        try {
            return beanFactory.getBean(beanName, DataSource.class);
        } catch (BeanNotOfRequiredTypeException exception) {
            throw new ConfigurationException("Configured DataSource bean has incompatible type: " + "dataSourceId=" + dataSourceId + ", beanName=" + beanName, exception);
        }
    }

    /**
     * 创建并托管配置中声明的 Hikari 物理连接池。
     *
     * @param snapshot 已编译配置快照
     * @return 托管连接池注册表
     */
    @Bean(destroyMethod = "close")
    public LavShardManagedDataSourceRegistry lavShardManagedDataSourceRegistry(LavShardConfigurationSnapshot snapshot) {
        return new LavShardManagedDataSourceRegistry(snapshot.managedDataSources(), snapshot.defaultTransactionIsolation());
    }
}
