package io.github.lavyoung.lavshard.integration;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.exception.UnsupportedSqlException;
import io.github.lavyoung.lavshard.core.api.rule.RuleSnapshot;
import io.github.lavyoung.lavshard.core.api.rule.TableRule;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.api.topology.ShardNode;
import io.github.lavyoung.lavshard.core.api.topology.ShardTopology;
import io.github.lavyoung.lavshard.core.internal.algorithm.Murmur3HashShardAlgorithm;
import io.github.lavyoung.lavshard.core.internal.algorithm.ShardAlgorithmRegistry;
import io.github.lavyoung.lavshard.core.internal.route.SqlRouteEngine;
import io.github.lavyoung.lavshard.mybatis.internal.executor.LavShardExecutorInterceptor;
import io.github.lavyoung.lavshard.mybatis.internal.executor.MyBatisIntegrationScope;
import io.github.lavyoung.lavshard.mybatis.internal.routing.LavShardRoutingDataSource;
import io.github.lavyoung.lavshard.mybatis.internal.routing.MyBatisRouteContext;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MyBatis Mapper 管理范围的真实执行隔离契约。
 *
 * <p>范围外 Mapper 可以继续使用自己的物理数据源，且必须在
 * 快照读取、SQL 分类、BATCH 拒绝和路由上下文绑定之前绕过
 * LavShard。相同 SQL 位于受管 Mapper 时仍执行严格校验。</p>
 */
class MyBatisIntegrationScopeIsolationTest {

    @ParameterizedTest
    @EnumSource(value = ExecutorType.class, names = {"SIMPLE", "REUSE"})
    void shouldBypassRoutingForExternalCrud(ExecutorType executorType) {
        // Given
        Harness harness = externalHarness();

        // When
        try (SqlSession session = harness.sqlSessionFactory()
                .openSession(executorType, true)) {
            ExternalMapper mapper = session.getMapper(ExternalMapper.class);
            assertThat(mapper.insert(1L, "external")).isEqualTo(1);
            assertThat(mapper.findNote(1L)).isEqualTo("external");
        }

        // Then
        assertCompletelyBypassed(harness);
    }

    @ParameterizedTest
    @EnumSource(value = ExecutorType.class, names = {"SIMPLE", "REUSE"})
    void shouldBypassRoutingForExternalCursor(ExecutorType executorType)
            throws Exception {
        // Given
        Harness harness = externalHarness();
        insertDirectly(harness.physicalDataSource(), 1L, "first");
        insertDirectly(harness.physicalDataSource(), 2L, "second");

        // When
        try (SqlSession session = harness.sqlSessionFactory()
                .openSession(executorType, true);
             Cursor<String> cursor = session
                     .getMapper(ExternalMapper.class)
                     .scanNotes()) {
            assertThat(cursor).containsExactly("first", "second");
        }

        // Then
        assertCompletelyBypassed(harness);
    }

    @Test
    void shouldAllowBatchForExternalMapper() {
        // Given
        Harness harness = externalHarness();

        // When
        try (SqlSession session = harness.sqlSessionFactory()
                .openSession(ExecutorType.BATCH, false)) {
            ExternalMapper mapper = session.getMapper(ExternalMapper.class);
            mapper.insert(1L, "first");
            mapper.insert(2L, "second");
            assertThat(session.flushStatements()).hasSize(1);
            session.commit();
        }

        // Then
        assertThat(countExternalRows(harness.physicalDataSource()))
                .isEqualTo(2);
        assertCompletelyBypassed(harness);
    }

    @Test
    void shouldKeepBypassStateCleanAfterExternalDatabaseFailure() {
        // Given
        Harness harness = externalHarness();
        insertDirectly(harness.physicalDataSource(), 1L, "existing");

        // When / Then
        try (SqlSession session = harness.sqlSessionFactory()
                .openSession(ExecutorType.SIMPLE, true)) {
            ExternalMapper mapper = session.getMapper(ExternalMapper.class);
            assertThatThrownBy(() -> mapper.insert(1L, "duplicate"))
                    .hasRootCauseInstanceOf(SQLException.class);
        }
        assertCompletelyBypassed(harness);
    }

    @Test
    void shouldRouteManagedMapperUsingTheSameInterceptorType() {
        // Given
        Harness harness = managedHarness();
        int attemptsBefore = harness.physicalDataSource()
                .connectionAttempts();

        // When
        try (SqlSession session = harness.sqlSessionFactory()
                .openSession(ExecutorType.SIMPLE, true)) {
            ManagedMapper mapper = session.getMapper(ManagedMapper.class);
            assertThat(mapper.insert("user-1", "managed")).isEqualTo(1);
            assertThat(mapper.findNote("user-1")).isEqualTo("managed");
        }

        // Then
        assertThat(harness.snapshotReads()).hasValue(2);
        assertThat(harness.physicalDataSource().connectionAttempts()
                - attemptsBefore).isEqualTo(1);
        assertThat(harness.routeContext().currentDecision()).isEmpty();
    }

    @Test
    void shouldRejectUnknownTableForManagedMapperBeforeConnection() {
        // Given
        Harness harness = managedHarness();
        int attemptsBefore = harness.physicalDataSource()
                .connectionAttempts();

        // When / Then
        try (SqlSession session = harness.sqlSessionFactory()
                .openSession(ExecutorType.SIMPLE, true)) {
            ManagedMapper mapper = session.getMapper(ManagedMapper.class);
            assertThatThrownBy(() -> mapper.readExternalTable(1L))
                    .rootCause()
                    .isInstanceOf(UnsupportedSqlException.class)
                    .hasMessageContaining(
                            "table is not configured as managed or ordinary"
                    )
                    .hasMessageContaining("private_note");
        }
        assertThat(harness.physicalDataSource().connectionAttempts())
                .isEqualTo(attemptsBefore);
        assertThat(harness.snapshotReads()).hasValue(1);
        assertThat(harness.routeContext().currentDecision()).isEmpty();
    }

    private static Harness externalHarness() {
        CountingDataSource physical = physicalDataSource();
        MyBatisRouteContext routeContext = new MyBatisRouteContext();
        AtomicInteger snapshotReads = new AtomicInteger();
        LavShardExecutorInterceptor interceptor = interceptor(
                routeContext,
                snapshotReads
        );
        SqlSessionFactory factory = sqlSessionFactory(
                physical,
                interceptor,
                ExternalMapper.class
        );
        return new Harness(
                physical,
                factory,
                routeContext,
                snapshotReads
        );
    }

    private static Harness managedHarness() {
        CountingDataSource physical = physicalDataSource();
        MyBatisRouteContext routeContext = new MyBatisRouteContext();
        AtomicInteger snapshotReads = new AtomicInteger();
        LavShardExecutorInterceptor interceptor = interceptor(
                routeContext,
                snapshotReads
        );
        DataSource routingDataSource = new LavShardRoutingDataSource(
                Map.of("ds0", physical),
                routeContext
        );
        SqlSessionFactory factory = sqlSessionFactory(
                routingDataSource,
                interceptor,
                ManagedMapper.class
        );
        return new Harness(
                physical,
                factory,
                routeContext,
                snapshotReads
        );
    }

    private static LavShardExecutorInterceptor interceptor(
            MyBatisRouteContext routeContext,
            AtomicInteger snapshotReads
    ) {
        SqlRouteEngine routeEngine = new SqlRouteEngine(
                ShardAlgorithmRegistry.withBuiltInAlgorithms(),
                Set.of(),
                "ds0"
        );
        MyBatisIntegrationScope scope = MyBatisIntegrationScope.of(
                Set.of(ManagedMapper.class.getName())
        );
        return new LavShardExecutorInterceptor(
                routeEngine,
                () -> {
                    snapshotReads.incrementAndGet();
                    return snapshot();
                },
                routeContext,
                scope
        );
    }

    @SafeVarargs
    private static SqlSessionFactory sqlSessionFactory(
            DataSource dataSource,
            LavShardExecutorInterceptor interceptor,
            Class<?>... mapperTypes
    ) {
        Environment environment = new Environment(
                "scope-isolation-test",
                new JdbcTransactionFactory(),
                dataSource
        );
        Configuration configuration = new Configuration(environment);
        for (Class<?> mapperType : mapperTypes) {
            configuration.addMapper(mapperType);
        }
        configuration.addInterceptor(interceptor);
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static RuleSnapshot snapshot() {
        ShardNode node = new ShardNode(
                "order-node",
                "ds0",
                new QualifiedTableName("t_order_00")
        );
        ShardTopology topology = new ShardTopology(
                "order-topology-v1",
                1,
                Map.of(0, node.nodeId()),
                Map.of(node.nodeId(), node)
        );
        TableRule rule = new TableRule(
                "order-rule-v1",
                new QualifiedTableName("t_order"),
                "user_id",
                Murmur3HashShardAlgorithm.NAME,
                new AlgorithmConfig(
                        1,
                        Murmur3HashShardAlgorithm.HASH_VERSION
                ),
                topology
        );
        return new RuleSnapshot(List.of(rule));
    }

    private static CountingDataSource physicalDataSource() {
        JdbcDataSource delegate = new JdbcDataSource();
        delegate.setURL(
                "jdbc:h2:mem:scope-"
                        + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1"
        );
        delegate.setUser("sa");
        delegate.setPassword("");
        CountingDataSource dataSource = new CountingDataSource(delegate);
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute("""
                    CREATE TABLE t_order_00 (
                        user_id VARCHAR(64) PRIMARY KEY,
                        note VARCHAR(128) NOT NULL
                    )
                    """);
            connection.createStatement().execute("""
                    CREATE TABLE private_note (
                        id BIGINT PRIMARY KEY,
                        note VARCHAR(128) NOT NULL
                    )
                    """);
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to create schema", exception);
        }
        return dataSource;
    }

    private static void insertDirectly(
            DataSource dataSource,
            long id,
            String note
    ) {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO private_note (id, note) VALUES (?, ?)"
             )) {
            statement.setLong(1, id);
            statement.setString(2, note);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to insert test row", exception);
        }
    }

    private static int countExternalRows(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery(
                     "SELECT COUNT(*) FROM private_note"
             )) {
            resultSet.next();
            return resultSet.getInt(1);
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to count test rows", exception);
        }
    }

    private static void assertCompletelyBypassed(Harness harness) {
        assertThat(harness.snapshotReads()).hasValue(0);
        assertThat(harness.routeContext().currentDecision()).isEmpty();
    }

    private interface ManagedMapper {

        @Insert("""
                INSERT INTO t_order (user_id, note)
                VALUES (#{userId}, #{note})
                """)
        int insert(
                @Param("userId") String userId,
                @Param("note") String note
        );

        @Select("""
                SELECT note FROM t_order
                WHERE user_id = #{userId}
                """)
        String findNote(@Param("userId") String userId);

        @Select("""
                SELECT note FROM private_note
                WHERE id = #{id}
                """)
        String readExternalTable(@Param("id") long id);
    }

    private interface ExternalMapper {

        @Insert("""
                INSERT INTO private_note (id, note)
                VALUES (#{id}, #{note})
                """)
        int insert(
                @Param("id") long id,
                @Param("note") String note
        );

        @Select("""
                SELECT note FROM private_note
                WHERE id = #{id}
                """)
        String findNote(@Param("id") long id);

        @Select("""
                SELECT note FROM private_note
                ORDER BY id
                """)
        Cursor<String> scanNotes();
    }

    private record Harness(
            CountingDataSource physicalDataSource,
            SqlSessionFactory sqlSessionFactory,
            MyBatisRouteContext routeContext,
            AtomicInteger snapshotReads
    ) {
    }

    private static final class CountingDataSource
            extends AbstractDataSource {

        private final DataSource delegate;
        private final AtomicInteger connectionAttempts =
                new AtomicInteger();

        private CountingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            connectionAttempts.incrementAndGet();
            return delegate.getConnection();
        }

        @Override
        public Connection getConnection(
                String username,
                String password
        ) throws SQLException {
            connectionAttempts.incrementAndGet();
            return delegate.getConnection(username, password);
        }

        private int connectionAttempts() {
            return connectionAttempts.get();
        }
    }
}
