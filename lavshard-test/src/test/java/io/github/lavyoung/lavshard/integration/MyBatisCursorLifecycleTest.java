package io.github.lavyoung.lavshard.integration;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardValue;
import io.github.lavyoung.lavshard.core.api.exception.CrossShardTransactionException;
import io.github.lavyoung.lavshard.core.api.exception.LavShardException;
import io.github.lavyoung.lavshard.core.api.rule.RuleSnapshot;
import io.github.lavyoung.lavshard.core.api.rule.TableRule;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.api.topology.ShardNode;
import io.github.lavyoung.lavshard.core.api.topology.ShardTopology;
import io.github.lavyoung.lavshard.core.internal.algorithm.Murmur3HashShardAlgorithm;
import io.github.lavyoung.lavshard.core.internal.algorithm.ShardAlgorithmRegistry;
import io.github.lavyoung.lavshard.core.internal.route.SqlRouteEngine;
import io.github.lavyoung.lavshard.mybatis.internal.executor.LavShardExecutorInterceptor;
import io.github.lavyoung.lavshard.mybatis.internal.routing.LavShardRoutingDataSource;
import io.github.lavyoung.lavshard.mybatis.internal.routing.MyBatisRouteContext;
import io.github.lavyoung.lavshard.starter.internal.transaction.SpringShardContext;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 游标返回、遍历、关闭和事务结束之间的资源生命周期契约。
 * H2 验证框架集成，不证明 MySQL 驱动按服务器游标流式传输。
 */
class MyBatisCursorLifecycleTest {
    private static final AlgorithmConfig ALGORITHM_CONFIG =
            new AlgorithmConfig(2, Murmur3HashShardAlgorithm.HASH_VERSION);
    private TrackingDataSource ds0;
    private TrackingDataSource ds1;
    private JdbcTemplate jdbc0;
    private JdbcTemplate jdbc1;
    private MyBatisRouteContext routeContext;
    private TransactionTemplate transaction;
    private String user0;
    private String user1;

    @BeforeEach
    void setUp() {
        ds0 = dataSource();
        ds1 = dataSource();
        jdbc0 = new JdbcTemplate(ds0.delegate);
        jdbc1 = new JdbcTemplate(ds1.delegate);
        user0 = userIdForBucket(0);
        user1 = userIdForBucket(1);
        createSchema(jdbc0, user0, "ds0");
        createSchema(jdbc1, user1, "ds1");
        SpringShardContext guard = new SpringShardContext();
        routeContext = new MyBatisRouteContext(guard::validate);
    }

    @AfterEach
    void cleanUp() throws SQLException {
        try {
            assertThat(routeContext.currentDecision()).isEmpty();
            assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
            ds0.assertReleased();
            ds1.assertReleased();
        } finally {
            jdbc0.execute("SHUTDOWN");
            jdbc1.execute("SHUTDOWN");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void shouldConsumeSelectedShardAfterRouteScopeHasClosed(Scenario scenario) {
        // Given
        OrderMapper mapper = mapper(scenario);
        List<String> notes = new ArrayList<>();

        // When
        transaction.executeWithoutResult(status -> withCursor(mapper.notes(user1), cursor -> {
            assertThat(routeContext.currentDecision()).isEmpty();
            cursor.forEach(note -> {
                assertThat(routeContext.currentDecision()).isEmpty();
                notes.add(note);
            });
            assertThat(cursor.isConsumed()).isTrue();
        }));

        // Then
        assertThat(notes).containsExactly("ds1-1", "ds1-2", "ds1-3");
        assertThat(ds0.connections).isEmpty();
        assertThat(ds1.connections).hasSize(1);
        assertThat(ds1.results).isNotEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void shouldCloseEarlyAndAllowAnotherCursorOnSameConnection(Scenario scenario) {
        // Given
        OrderMapper mapper = mapper(scenario);
        List<String> notes = new ArrayList<>();

        // When
        transaction.executeWithoutResult(status -> {
            withCursor(mapper.notes(user0), cursor ->
                    assertThat(cursor.iterator().next()).isEqualTo("ds0-1"));
            ds0.assertResultsClosed();
            withCursor(mapper.notes(user0), cursor -> cursor.forEach(notes::add));
        });

        // Then: REUSE may retain a statement until transaction completion, not its old result set.
        assertThat(notes).containsExactly("ds0-1", "ds0-2", "ds0-3");
        assertThat(ds0.connections).hasSize(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void shouldCloseUnconsumedCursorAtTransactionCompletion(Scenario scenario) {
        // Given
        OrderMapper mapper = mapper(scenario);

        // When: intentionally return a cursor without closing or consuming it.
        Cursor<String> escaped = Objects.requireNonNull(
                transaction.execute(status -> mapper.notes(user0)));

        // Then: Spring/MyBatis closes the owning session at transaction completion.
        assertThat(escaped.isOpen()).isFalse();
        assertThatThrownBy(() -> escaped.iterator().hasNext()).isInstanceOf(IllegalStateException.class);
        ds0.assertResultsClosed();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void shouldExposeClosedCursorWhenTemplateHasNoTransaction(Scenario scenario) {
        // Given
        OrderMapper mapper = mapper(scenario);

        // When: SqlSessionTemplate releases its nontransactional session on method return.
        Cursor<String> cursor = mapper.notes(user0);

        // Then: callers must consume inside an active transaction or explicitly owned session.
        assertThat(cursor.isOpen()).isFalse();
        assertThatThrownBy(() -> cursor.iterator().hasNext()).isInstanceOf(IllegalStateException.class);
        ds0.assertResultsClosed();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void shouldRejectOtherShardWithoutBreakingActiveCursor(Scenario scenario) {
        // Given
        OrderMapper mapper = mapper(scenario);
        List<String> notes = new ArrayList<>();

        // When
        transaction.executeWithoutResult(status -> withCursor(mapper.notes(user0), cursor -> {
            Iterator<String> iterator = cursor.iterator();
            notes.add(iterator.next());
            assertThatThrownBy(() -> mapper.notes(user1))
                    .hasRootCauseInstanceOf(CrossShardTransactionException.class);
            iterator.forEachRemaining(notes::add);
        }));

        // Then
        assertThat(ds1.connections).isEmpty();
        assertThat(notes).containsExactly("ds0-1", "ds0-2", "ds0-3");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void shouldCloseCursorAndRollbackWhenConsumerFails(Scenario scenario) {
        // Given
        OrderMapper mapper = mapper(scenario);
        IllegalStateException failure = new IllegalStateException("consumer failed");

        // When
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            mapper.insert(user0);
            withCursor(mapper.notes(user0), cursor -> {
                assertThat(cursor.iterator().next()).isEqualTo("ds0-1");
                throw failure;
            });
        })).isSameAs(failure);

        // Then
        assertThat(jdbc0.queryForObject("SELECT COUNT(*) FROM t_order_00", Long.class)).isEqualTo(3L);
        ds0.assertResultsClosed();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void shouldRejectInvalidShardBeforeOpeningCursorResources(Scenario scenario) {
        // Given
        OrderMapper mapper = mapper(scenario);

        // When / Then
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> mapper.notes(null)))
                .hasRootCauseInstanceOf(LavShardException.class);
        assertThat(ds0.connections).isEmpty();
        assertThat(ds1.connections).isEmpty();
    }

    private static void withCursor(Cursor<String> cursor, Consumer<Cursor<String>> consumer) {
        try (cursor) {
            consumer.accept(cursor);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Stream<Scenario> scenarios() {
        return Stream.of(ExecutorType.SIMPLE, ExecutorType.REUSE)
                .flatMap(type -> Stream.of(false, true).map(cache -> new Scenario(type, cache)));
    }

    /**
     * 创建共用路由数据源的 Mapper 和事务模板。
     *
     * @param scenario MyBatis 执行器及缓存包装选项
     * @return 用于实际游标查询的 Mapper
     * @throws RuntimeException 框架装配失败时抛出
     */
    private OrderMapper mapper(Scenario scenario) {
        DataSource routing = new LavShardRoutingDataSource(Map.of("ds0", ds0, "ds1", ds1), routeContext);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(routing));
        Configuration configuration = new Configuration(new Environment("cursor-test",
                new SpringManagedTransactionFactory(), routing));
        configuration.setCacheEnabled(scenario.cache());
        configuration.addInterceptor(new LavShardExecutorInterceptor(
                new SqlRouteEngine(ShardAlgorithmRegistry.withBuiltInAlgorithms(), Set.of(), "ds0"),
                MyBatisCursorLifecycleTest::snapshot, routeContext));
        configuration.addMapper(OrderMapper.class);
        return new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration), scenario.type())
                .getMapper(OrderMapper.class);
    }

    private static TrackingDataSource dataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:cursor-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        return new TrackingDataSource(ds);
    }

    private static void createSchema(JdbcTemplate jdbc, String user, String prefix) {
        jdbc.execute("""
                CREATE TABLE t_order_00 (
                    id BIGINT PRIMARY KEY,
                    user_id VARCHAR(64) NOT NULL,
                    note VARCHAR(64) NOT NULL
                )
                """);
        for (int id = 1; id <= 3; id++) {
            jdbc.update("INSERT INTO t_order_00 (id, user_id, note) VALUES (?, ?, ?)", id, user, prefix + "-" + id);
        }
    }

    private static RuleSnapshot snapshot() {
        ShardNode node0 = new ShardNode(
                "order-node-0",
                "ds0",
                new QualifiedTableName("t_order_00")
        );
        ShardNode node1 = new ShardNode(
                "order-node-1",
                "ds1",
                new QualifiedTableName("t_order_00")
        );
        ShardTopology topology = new ShardTopology(
                "order-topology-v1",
                2,
                Map.of(
                        0, node0.nodeId(),
                        1, node1.nodeId()
                ),
                Map.of(
                        node0.nodeId(), node0,
                        node1.nodeId(), node1
                )
        );
        TableRule rule = new TableRule(
                "order-rule-v1",
                new QualifiedTableName("t_order"),
                "user_id",
                Murmur3HashShardAlgorithm.NAME,
                ALGORITHM_CONFIG,
                topology
        );
        return new RuleSnapshot(List.of(rule));
    }

    private static String userIdForBucket(
            int expectedBucket
    ) {
        Murmur3HashShardAlgorithm algorithm =
                new Murmur3HashShardAlgorithm();

        for (int candidate = 0; candidate < 100; candidate++) {
            String userId = "user-" + candidate;
            int bucket = algorithm.calculate(
                    ShardValue.of(userId),
                    ALGORITHM_CONFIG
            ).value();

            if (bucket == expectedBucket) {
                return userId;
            }
        }

        throw new IllegalStateException(
                "No user id found for bucket "
                        + expectedBucket
        );
    }


    private record Scenario(ExecutorType type, boolean cache) {
    }

    private interface OrderMapper {
        @Select("""
                SELECT note FROM t_order WHERE user_id = #{userId} ORDER BY id
                """)
        Cursor<String> notes(@Param("userId") String userId);

        @Insert("""
                INSERT INTO t_order (id, user_id, note) VALUES (99, #{userId}, 'rollback')
                """)
        int insert(@Param("userId") String userId);
    }

    /**
     * 记录实际 JDBC 对象，用真实 isClosed 验证释放，避免仅凭代理调用次数推断。
     */
    private static final class TrackingDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private final List<Connection> connections = new ArrayList<>();
        private final List<PreparedStatement> statements = new ArrayList<>();
        private final List<ResultSet> results = new ArrayList<>();

        private TrackingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return track(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return track(delegate.getConnection(username, password));
        }

        private Connection track(Connection physical) {
            connections.add(physical);
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        Object value = call(physical, method, args);
                        return value instanceof PreparedStatement statement ? track(statement) : value;
                    });
        }

        private PreparedStatement track(PreparedStatement physical) {
            statements.add(physical);
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                        Object value = call(physical, method, args);
                        if (value instanceof ResultSet resultSet) {
                            results.add(resultSet);
                        }
                        return value;
                    });
        }

        private void assertResultsClosed() {
            for (ResultSet result : results) {
                try {
                    assertThat(result.isClosed()).isTrue();
                } catch (SQLException exception) {
                    throw new AssertionError("Cannot inspect ResultSet state", exception);
                }
            }
        }

        private void assertReleased() throws SQLException {
            assertResultsClosed();
            for (PreparedStatement statement : statements) {
                assertThat(statement.isClosed()).isTrue();
            }
            for (Connection connection : connections) {
                assertThat(connection.isClosed()).isTrue();
            }
        }

        private static Object call(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }
}

