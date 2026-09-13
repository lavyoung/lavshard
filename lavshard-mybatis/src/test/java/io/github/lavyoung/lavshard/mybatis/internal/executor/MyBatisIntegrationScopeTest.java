package io.github.lavyoung.lavshard.mybatis.internal.executor;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MyBatis Mapper 管理范围契约。
 */
class MyBatisIntegrationScopeTest {

    @Test
    void shouldManageEveryStatementWhenScopeIsAllOrEmpty() {
        assertThat(MyBatisIntegrationScope.all().includes(
                "com.acme.order.OrderMapper.selectById"
        )).isTrue();
        assertThat(MyBatisIntegrationScope.of(Set.of()).includes(
                "legacy.Mapper.select"
        )).isTrue();
    }

    @Test
    void shouldMatchMapperPackageAndItsSubpackages() {
        MyBatisIntegrationScope scope = MyBatisIntegrationScope.of(Set.of(
                "com.acme.order.mapper"
        ));

        assertThat(scope.includes(
                "com.acme.order.mapper.OrderMapper.selectById"
        )).isTrue();
        assertThat(scope.includes(
                "com.acme.order.mapper.archive.OrderArchiveMapper.insert"
        )).isTrue();
    }

    @Test
    void shouldSupportExactMapperNamespaceAsNarrowScope() {
        MyBatisIntegrationScope scope = MyBatisIntegrationScope.of(Set.of(
                "com.acme.order.mapper.OrderMapper"
        ));

        assertThat(scope.includes(
                "com.acme.order.mapper.OrderMapper.selectById"
        )).isTrue();
        assertThat(scope.includes(
                "com.acme.order.mapper.OrderHistoryMapper.selectById"
        )).isFalse();
    }

    @Test
    void shouldNotMatchOutsideSiblingOrRawPrefixCollision() {
        MyBatisIntegrationScope scope = MyBatisIntegrationScope.of(Set.of(
                "com.acme.order"
        ));

        assertThat(scope.includes(
                "com.acme.customer.CustomerMapper.select"
        )).isFalse();
        assertThat(scope.includes(
                "com.acme.orders.OrderMapper.select"
        )).isFalse();
        assertThat(scope.includes(
                "com.acme.ordering.OrderMapper.select"
        )).isFalse();
    }

    @Test
    void shouldDefensivelyCopyConfiguredPackages() {
        Set<String> packages = new HashSet<>();
        packages.add("com.acme.order");
        MyBatisIntegrationScope scope = MyBatisIntegrationScope.of(packages);

        packages.clear();
        packages.add("com.acme.customer");

        assertThat(scope.includes(
                "com.acme.order.OrderMapper.select"
        )).isTrue();
        assertThat(scope.includes(
                "com.acme.customer.CustomerMapper.select"
        )).isFalse();
    }

    @Test
    void shouldRejectInvalidManagedMapperPackages() {
        Set<String> packagesWithNull = new HashSet<>();
        packagesWithNull.add(null);

        assertThatThrownBy(() -> MyBatisIntegrationScope.of(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("managedMapperPackages must not be null");
        assertThatThrownBy(() -> MyBatisIntegrationScope.of(packagesWithNull))
                .isInstanceOf(NullPointerException.class)
                .hasMessage(
                        "managedMapperPackages must not contain null package names"
                );
        assertThatThrownBy(() -> MyBatisIntegrationScope.of(Set.of(" ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "managedMapperPackages must not contain blank package names"
                );
    }

    @Test
    void shouldRejectInvalidMappedStatementId() {
        MyBatisIntegrationScope scope = MyBatisIntegrationScope.all();

        assertThatThrownBy(() -> scope.includes(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("mappedStatementId must not be null");
        assertThatThrownBy(() -> scope.includes(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("mappedStatementId must not be blank");
    }
}
