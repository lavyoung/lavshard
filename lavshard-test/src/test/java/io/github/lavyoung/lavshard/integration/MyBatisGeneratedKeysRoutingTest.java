package io.github.lavyoung.lavshard.integration;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardValue;
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
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
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
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 单行 INSERT 路由后的 JDBC 生成键回填契约。
 * H2 验证 MyBatis/Spring 调用链，不替代 MySQL 驱动验收。
 */
class MyBatisGeneratedKeysRoutingTest {
    private static final AlgorithmConfig ALGORITHM_CONFIG =
            new AlgorithmConfig(2, Murmur3HashShardAlgorithm.HASH_VERSION);

    private CountingDataSource ds0;
    private CountingDataSource ds1;
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
        jdbc0 = new JdbcTemplate(ds0);
        jdbc1 = new JdbcTemplate(ds1);
        createSchema(jdbc0, 100);
        createSchema(jdbc1, 200);
        SpringShardContext guard = new SpringShardContext();
        routeContext = new MyBatisRouteContext(guard::validate);
        user0 = userIdForBucket(0);
        user1 = userIdForBucket(1);
    }

    @AfterEach
    void cleanUp() {
        try {
            assertThat(routeContext.currentDecision()).isEmpty();
            assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        } finally {
            jdbc0.execute("SHUTDOWN");
            jdbc1.execute("SHUTDOWN");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void shouldReturnKeysFromEachSelectedDatabase(Scenario scenario) {
        // Given: independent databases have deliberately distinct identity starting values.
        OrderMapper mapper = mapper(scenario);
        Map<String, Object> first = row(user0, "on-ds0");
        Map<String, Object> second = row(user1, "on-ds1");

        // When
        assertThat(mapper.insert(first)).isEqualTo(1);
        assertThat(mapper.insert(second)).isEqualTo(1);

        // Then: the returned key identifies the actual row in the selected database.
        assertThat(key(first)).isEqualTo(100);
        assertThat(key(second)).isEqualTo(200);
        assertThat(note(jdbc0, key(first))).isEqualTo("on-ds0");
        assertThat(note(jdbc1, key(second))).isEqualTo("on-ds1");
        assertThat(count(jdbc0)).isEqualTo(1);
        assertThat(count(jdbc1)).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void shouldWriteKeyIntoNamedMapWithDynamicAdditionalParameter(Scenario scenario) {
        // Given
        OrderMapper mapper = mapper(scenario);
        Map<String, Object> row = row(user1, "unused");
        int before0 = ds0.attempts;

        // When: <bind> supplies the shard parameter; keyProperty targets the caller's map.
        transaction.executeWithoutResult(status ->
                assertThat(mapper.insertDynamic(row, "dynamic-note")).isEqualTo(1));

        // Then
        assertThat(ds0.attempts).isEqualTo(before0);
        assertThat(key(row)).isEqualTo(200);
        assertThat(note(jdbc1, key(row))).isEqualTo("dynamic-note");
        assertThat(row).doesNotContainKeys("row.id", "routedUser");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void shouldAssignDistinctKeysForRepeatedStatementInSameTransaction(Scenario scenario) {
        // Given
        OrderMapper mapper = mapper(scenario);
        Map<String, Object> first = row(user0, "first");
        Map<String, Object> second = row(user0, "second");
        int before = ds0.attempts;

        // When
        transaction.executeWithoutResult(status -> {
            assertThat(mapper.insert(first)).isEqualTo(1);
            assertThat(mapper.insert(second)).isEqualTo(1);
        });

        // Then
        assertThat(ds0.attempts - before).isEqualTo(1);
        assertThat(key(first)).isEqualTo(100);
        assertThat(key(second)).isEqualTo(101);
        assertThat(note(jdbc0, key(first))).isEqualTo("first");
        assertThat(note(jdbc0, key(second))).isEqualTo("second");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void shouldRollbackRowButKeepAlreadyAssignedJavaKey(Scenario scenario) {
        // Given
        OrderMapper mapper = mapper(scenario);
        Map<String, Object> row = row(user1, "rollback");

        // When
        transaction.executeWithoutResult(status -> {
            mapper.insert(row);
            assertThat(key(row)).isEqualTo(200);
            status.setRollbackOnly();
        });

        // Then: JDBC rollback does not rewind mutations to a Java parameter object.
        assertThat(key(row)).isEqualTo(200);
        assertThat(count(jdbc1)).isZero();
        assertThat(count(jdbc0)).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void shouldRejectNullShardWithoutConnectionOrGeneratedKey(Scenario scenario) {
        // Given
        OrderMapper mapper = mapper(scenario);
        Map<String, Object> invalid = row(null, "rejected");
        int before0 = ds0.attempts;
        int before1 = ds1.attempts;

        // When / Then
        assertThatThrownBy(() -> mapper.insert(invalid)).hasRootCauseInstanceOf(LavShardException.class);
        assertThat(invalid).doesNotContainKey("id");
        assertThat(ds0.attempts).isEqualTo(before0);
        assertThat(ds1.attempts).isEqualTo(before1);
        assertThat(count(jdbc0)).isZero();
        assertThat(count(jdbc1)).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void shouldNotAssignKeyAfterDatabaseFailureAndAllowNextTransaction(Scenario scenario) {
        // Given: NOT NULL fails in JDBC, after routing succeeded.
        OrderMapper mapper = mapper(scenario);
        Map<String, Object> invalid = row(user0, null);

        // When
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> mapper.insert(invalid)))
                .hasRootCauseInstanceOf(SQLException.class);

        // Then: no generated key is published for the failed insert; another shard remains usable.
        assertThat(invalid).doesNotContainKey("id");
        assertThat(count(jdbc0)).isZero();
        Map<String, Object> valid = row(user1, "after-failure");
        transaction.executeWithoutResult(status -> mapper.insert(valid));
        assertThat(key(valid)).isEqualTo(200);
        assertThat(note(jdbc1, key(valid))).isEqualTo("after-failure");
    }

    private static Stream<Scenario> scenarios() {
        return Stream.of(ExecutorType.SIMPLE, ExecutorType.REUSE)
                .flatMap(type -> Stream.of(false, true).map(cache -> new Scenario(type, cache)));
    }

    /**
     * 创建共享逻辑数据源的 Spring 事务与 MyBatis Mapper。
     *
     * @param scenario 执行器及缓存包装配置
     * @return 已绑定路由拦截器的 Mapper
     * @throws RuntimeException MyBatis 配置无效时抛出
     */
    private OrderMapper mapper(Scenario scenario) {
        DataSource routing = new LavShardRoutingDataSource(Map.of("ds0", ds0, "ds1", ds1), routeContext);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(routing));
        Configuration configuration = new Configuration(new Environment("generated-keys",
                new SpringManagedTransactionFactory(), routing));
        configuration.setCacheEnabled(scenario.cache());
        configuration.addInterceptor(new LavShardExecutorInterceptor(
                new SqlRouteEngine(ShardAlgorithmRegistry.withBuiltInAlgorithms(), Set.of(), "ds0"),
                MyBatisGeneratedKeysRoutingTest::snapshot, routeContext));
        configuration.addMapper(OrderMapper.class);
        return new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration), scenario.type())
                .getMapper(OrderMapper.class);
    }

    private static Map<String, Object> row(String user, String note) {
        Map<String, Object> row = new HashMap<>();
        row.put("userId", user);
        row.put("note", note);
        return row;
    }

    private static long key(Map<String, Object> row) {
        assertThat(row.get("id")).isInstanceOf(Number.class);
        return ((Number) row.get("id")).longValue();
    }

    private static String note(JdbcTemplate jdbc, long id) {
        return jdbc.queryForObject("SELECT note FROM t_order_00 WHERE id = ?", String.class, id);
    }

    private static long count(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_order_00", Long.class);
    }

    private static CountingDataSource dataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:keys-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        return new CountingDataSource(ds);
    }

    private static void createSchema(JdbcTemplate jdbc, int start) {
        jdbc.execute("""
                CREATE TABLE t_order_00 (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY (START WITH %d) PRIMARY KEY,
                    user_id VARCHAR(64) NOT NULL,
                    note VARCHAR(128) NOT NULL
                )
                """.formatted(start));
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
        @Insert("""
                INSERT INTO t_order (user_id, note) VALUES (#{userId}, #{note})
                """)
        @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "ID")
        int insert(Map<String, Object> row);

        @Insert("""
                <script>
                <bind name="routedUser" value="row.userId"/>
                INSERT INTO t_order (user_id, note) VALUES (#{routedUser}, #{note})
                </script>
                """)
        @Options(useGeneratedKeys = true, keyProperty = "row.id", keyColumn = "ID")
        int insertDynamic(@Param("row") Map<String, Object> row, @Param("note") String note);
    }

    private static final class CountingDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private int attempts;

        private CountingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            attempts++;
            return delegate.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            attempts++;
            return delegate.getConnection(username, password);
        }
    }
}

