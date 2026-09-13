package io.github.lavyoung.lavshard.mybatis.internal.executor;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * MyBatis Mapper 管理范围。
 *
 * <p>范围项既可以是 Mapper 所在包，也可以是完整 Mapper namespace。
 * 空范围表示管理全部 Mapper，用于保持 LavShard 现有默认行为。</p>
 *
 * <p>匹配始终要求点号边界。例如 {@code com.acme.order} 可以匹配
 * {@code com.acme.order.mapper.OrderMapper.select}，但不会匹配
 * {@code com.acme.orders.OrderMapper.select}。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/09/13
 */
public final class MyBatisIntegrationScope {

    private final Set<String> managedMapperPackages;

    private MyBatisIntegrationScope(Collection<String> managedMapperPackages) {
        Objects.requireNonNull(managedMapperPackages, "managedMapperPackages must not be null");

        Set<String> packages = new LinkedHashSet<>();

        for (String packageName : managedMapperPackages) {
            if (packageName == null) {
                throw new NullPointerException(
                        "managedMapperPackages must not contain "
                                + "null package names"
                );
            }

            if (packageName.isBlank()) {
                throw new IllegalArgumentException(
                        "managedMapperPackages must not contain "
                                + "blank package names"
                );
            }

            packages.add(packageName);
        }

        this.managedMapperPackages = Set.copyOf(packages);
    }

    /**
     * 创建管理全部 Mapper 的范围。
     *
     * @return 管理全部 Mapper 的不可变范围
     */
    public static MyBatisIntegrationScope all() {
        return new MyBatisIntegrationScope(Set.of());
    }

    /**
     * 根据 Mapper 包或 namespace 创建管理范围。
     *
     * <p>传入空集合时等价于 {@link #all()}。</p>
     *
     * @param managedMapperPackages 受 LavShard 管理的包或 Mapper namespace
     * @return 不可变 MyBatis 管理范围
     * @throws NullPointerException     集合或集合元素为空时抛出
     * @throws IllegalArgumentException 集合中包含空白名称时抛出
     */
    public static MyBatisIntegrationScope of(Collection<String> managedMapperPackages) {
        return new MyBatisIntegrationScope(managedMapperPackages);
    }


    /**
     * 判断 MappedStatement 是否属于 LavShard 管理范围。
     *
     * @param mappedStatementId MyBatis MappedStatement ID
     * @return 属于管理范围时返回 true
     * @throws NullPointerException     ID 为空时抛出
     * @throws IllegalArgumentException ID 为空白字符串时抛出
     */
    public boolean includes(String mappedStatementId) {
        Objects.requireNonNull(mappedStatementId, "mappedStatementId must not be null");

        if (mappedStatementId.isBlank()) {
            throw new IllegalArgumentException(
                    "mappedStatementId must not be blank"
            );
        }

        if (managedMapperPackages.isEmpty()) {
            return true;
        }

        return managedMapperPackages.stream().anyMatch(packageName -> mappedStatementId.startsWith(packageName + "."));
    }
}
