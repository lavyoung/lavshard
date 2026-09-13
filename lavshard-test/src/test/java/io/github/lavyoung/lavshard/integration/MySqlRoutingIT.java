package io.github.lavyoung.lavshard.integration;

import com.mysql.cj.jdbc.MysqlDataSource;
import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardValue;
import io.github.lavyoung.lavshard.core.api.exception.CrossShardTransactionException;
import io.github.lavyoung.lavshard.core.api.exception.LavShardException;
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
import io.github.lavyoung.lavshard.mybatis.internal.routing.LavShardRoutingDataSource;
import io.github.lavyoung.lavshard.mybatis.internal.routing.MyBatisRouteContext;
import io.github.lavyoung.lavshard.starter.internal.transaction.SpringShardContext;
import org.apache.ibatis.annotations.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MySQL 单分片发布验收：三种拓扑 × SIMPLE/REUSE。
 * 仅访问本类创建的临时容器；无 Docker 时明确失败，不静默跳过。
 */
@Testcontainers
class MySqlRoutingIT {
    private static final AlgorithmConfig ALGORITHM_CONFIG =
            new AlgorithmConfig(2, Murmur3HashShardAlgorithm.HASH_VERSION);
    // Reproducible acceptance fixture, not a recommendation for a production server version.
    @Container
    private static final MySQLContainer<?> MYSQL0 = new MySQLContainer<>("mysql:8.0.36");
    @Container
    private static final MySQLContainer<?> MYSQL1 = new MySQLContainer<>("mysql:8.0.36");

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
        SpringShardContext guard = new SpringShardContext();
        routeContext = new MyBatisRouteContext(guard::validate);
        ds0 = dataSource(MYSQL0);
        ds1 = dataSource(MYSQL1);
        jdbc0 = new JdbcTemplate(ds0.delegate);
        jdbc1 = new JdbcTemplate(ds1.delegate);
        initialize(jdbc0);
        initialize(jdbc1);
        user0 = userIdForBucket(0);
        user1 = userIdForBucket(1);
    }

    @AfterEach
    void assertContextReleased() {
        if (routeContext != null) {
            assertThat(routeContext.currentDecision()).isEmpty();
        }
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void shouldExecuteCrudAndReturnPhysicalGeneratedKeys(Scenario scenario) {
        // Given
        OrderMapper mapper = mapper(scenario);
        Map<String, Object> first = row(user0, "first");
        Map<String, Object> second = row(user1, "second");

        // When
        assertThat(mapper.insert(first)).isEqualTo(1);
        assertThat(mapper.insert(second)).isEqualTo(1);

        // Then: separate physical tables may generate the same id; neither is globally unique.
        assertThat(first.get("id")).isInstanceOf(Number.class);
        assertThat(second.get("id")).isInstanceOf(Number.class);
        assertThat(mapper.note(user0)).isEqualTo("first");
        assertThat(mapper.note(user1)).isEqualTo("second");
        assertThat(targetJdbc(scenario, 0).queryForObject(
                "SELECT note FROM " + targetTable(scenario, 0) + " WHERE id = ?",
                String.class, first.get("id"))).isEqualTo("first");
        assertThat(targetJdbc(scenario, 1).queryForObject(
                "SELECT note FROM " + targetTable(scenario, 1) + " WHERE id = ?",
                String.class, second.get("id"))).isEqualTo("second");
        assertThat(mapper.update(user0, "updated")).isEqualTo(1);
        assertThat(mapper.note(user0)).isEqualTo("updated");
        assertThat(mapper.note(user1)).isEqualTo("second");
        assertThat(mapper.delete(user1)).isEqualTo(1);
        assertThat(totalRows()).isEqualTo(1L);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void shouldRollbackInnoDbWriteWithoutPublishingUncommittedCache(Scenario scenario) {
        // Given
        OrderMapper mapper = mapper(scenario);
        Map<String, Object> row = row(user1, "rolled-back");

        // When
        transaction.executeWithoutResult(status -> {
            mapper.insert(row);
            assertThat(mapper.note(user1)).isEqualTo("rolled-back");
            status.setRollbackOnly();
        });

        // Then
        assertThat(row.get("id")).isInstanceOf(Number.class);
        assertThat(totalRows()).isZero();
        assertThat(mapper.note(user1)).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void shouldHonorTransactionGuardEvenWhenBothTargetsAreCached(Scenario scenario) {
        // Given
        OrderMapper mapper = mapper(scenario);
        mapper.insert(row(user0, "first"));
        mapper.insert(row(user1, "second"));
        assertThat(mapper.note(user0)).isEqualTo("first");
        assertThat(mapper.note(user1)).isEqualTo("second");
        int before0 = ds0.attempts;
        int before1 = ds1.attempts;

        // When / Then: same database/different tables is legal, different databases is not.
        transaction.executeWithoutResult(status -> {
            assertThat(mapper.note(user0)).isEqualTo("first");
            if (scenario.layout() == Layout.TABLE_ONLY) {
                assertThat(mapper.note(user1)).isEqualTo("second");
            } else {
                assertThatThrownBy(() -> mapper.note(user1))
                        .hasRootCauseInstanceOf(CrossShardTransactionException.class);
            }
        });
        assertThat(ds0.attempts).isEqualTo(before0);
        assertThat(ds1.attempts).isEqualTo(before1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void shouldRejectUnsafeInsertsBeforePhysicalConnection(Scenario scenario) {
        // Given
        OrderMapper mapper = mapper(scenario);
        Map<String, Object> invalid = row(null, "missing-shard");
        int before0 = ds0.attempts;
        int before1 = ds1.attempts;

        // When / Then
        assertThatThrownBy(() -> mapper.insert(invalid)).hasRootCauseInstanceOf(LavShardException.class);
        assertThatThrownBy(() -> mapper.insertMany(user0, user1))
                .hasRootCauseInstanceOf(UnsupportedSqlException.class);
        assertThat(invalid).doesNotContainKey("id");
        assertThat(ds0.attempts).isEqualTo(before0);
        assertThat(ds1.attempts).isEqualTo(before1);
        assertThat(totalRows()).isZero();
    }

    private static Stream<Scenario> scenarios() {
        return Stream.of(Layout.values()).flatMap(layout ->
                Stream.of(ExecutorType.SIMPLE, ExecutorType.REUSE).map(type -> new Scenario(layout, type)));
    }

    /**
     * 构造实际路由规则、共享数据源事务管理器及带二级缓存的 Mapper。
     *
     * @param scenario 测试拓扑与执行器
     * @return 路由后的 Mapper
     * @throws RuntimeException 框架装配失败时抛出
     */
    private OrderMapper mapper(Scenario scenario) {
        DataSource routing = new LavShardRoutingDataSource(Map.of("ds0", ds0, "ds1", ds1), routeContext);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(routing));
        Configuration configuration = new Configuration(new Environment("mysql-acceptance",
                new SpringManagedTransactionFactory(), routing));
        configuration.addInterceptor(new LavShardExecutorInterceptor(
                new SqlRouteEngine(ShardAlgorithmRegistry.withBuiltInAlgorithms(), Set.of(), "ds0"),
                () -> snapshot(scenario), routeContext));
        configuration.addMapper(OrderMapper.class);
        return new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration), scenario.type())
                .getMapper(OrderMapper.class);
    }

    private static RuleSnapshot snapshot(Scenario scenario) {
        ShardNode node0 = new ShardNode("node0", "ds0", new QualifiedTableName("t_order_00"));
        ShardNode node1 = new ShardNode("node1",
                scenario.layout() == Layout.TABLE_ONLY ? "ds0" : "ds1",
                new QualifiedTableName(targetTable(scenario, 1)));
        ShardTopology topology = new ShardTopology("topology-v1", 2,
                Map.of(0, "node0", 1, "node1"), Map.of("node0", node0, "node1", node1));
        return new RuleSnapshot(List.of(new TableRule("rule-v1", new QualifiedTableName("t_order"),
                "user_id", Murmur3HashShardAlgorithm.NAME, ALGORITHM_CONFIG, topology)));
    }

    private JdbcTemplate targetJdbc(Scenario scenario, int bucket) {
        return bucket == 0 || scenario.layout() == Layout.TABLE_ONLY ? jdbc0 : jdbc1;
    }

    private static String targetTable(Scenario scenario, int bucket) {
        return bucket == 0 || scenario.layout() == Layout.DATABASE_ONLY ? "t_order_00" : "t_order_01";
    }

    private long totalRows() {
        return count(jdbc0, "t_order_00") + count(jdbc0, "t_order_01")
                + count(jdbc1, "t_order_00") + count(jdbc1, "t_order_01");
    }

    private static long count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private static Map<String, Object> row(String user, String note) {
        Map<String, Object> row = new HashMap<>();
        row.put("userId", user);
        row.put("note", note);
        return row;
    }

    private static CountingDataSource dataSource(MySQLContainer<?> container) {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setURL(container.getJdbcUrl());
        ds.setUser(container.getUsername());
        ds.setPassword(container.getPassword());
        return new CountingDataSource(ds);
    }

    /**
     * 在本测试独占的临时容器内重置表，表名来自固定列表。
     *
     * @param jdbc 临时容器数据库
     * @throws RuntimeException 初始化 SQL 失败时抛出
     */
    private static void initialize(JdbcTemplate jdbc) {
        for (String table : List.of("t_order_00", "t_order_01")) {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS %s (
                        id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                        user_id VARCHAR(64) NOT NULL,
                        note VARCHAR(128) NOT NULL,
                        INDEX idx_user_id (user_id)
                    ) ENGINE=InnoDB
                    """.formatted(table));
            jdbc.execute("TRUNCATE TABLE " + table);
        }
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


    private enum Layout {TABLE_ONLY, DATABASE_ONLY, DATABASE_AND_TABLE}

    private record Scenario(Layout layout, ExecutorType type) {
    }

    @CacheNamespace(readWrite = false)
    private interface OrderMapper {
        @Insert("INSERT INTO t_order (user_id, note) VALUES (#{userId}, #{note})")
        @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
        int insert(Map<String, Object> row);

        @Select("SELECT note FROM t_order WHERE user_id = #{userId}")
        String note(@Param("userId") String userId);

        @Update("UPDATE t_order SET note = #{note} WHERE user_id = #{userId}")
        int update(@Param("userId") String userId, @Param("note") String note);

        @Delete("DELETE FROM t_order WHERE user_id = #{userId}")
        int delete(@Param("userId") String userId);

        @Insert("""
                INSERT INTO t_order (user_id, note) VALUES (#{first}, 'first'), (#{second}, 'second')
                """)
        int insertMany(@Param("first") String first, @Param("second") String second);
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
