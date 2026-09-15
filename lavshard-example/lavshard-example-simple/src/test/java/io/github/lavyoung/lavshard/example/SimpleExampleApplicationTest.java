package io.github.lavyoung.lavshard.example;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardValue;
import io.github.lavyoung.lavshard.core.internal.algorithm.Murmur3HashShardAlgorithm;
import io.github.lavyoung.lavshard.example.order.api.CreateOrderRequest;
import io.github.lavyoung.lavshard.example.order.service.OrderService;
import io.github.lavyoung.lavshard.starter.autoconfigure.config.LavShardProperties;
import io.github.lavyoung.lavshard.starter.internal.datasource.LavShardManagedDataSourceRegistry;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Simple 示例从自动配置启动到单库分表 CRUD 和本地事务的验收测试。
 */
@SpringBootTest(
        classes = DemoExampleApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
@AutoConfigureMockMvc
class SimpleExampleApplicationTest {

    private static final AlgorithmConfig ALGORITHM_CONFIG =
            new AlgorithmConfig(
                    2,
                    Murmur3HashShardAlgorithm.HASH_VERSION
            );

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderService orderService;

    @Autowired
    private DataSource routingDataSource;

    @Autowired
    private LavShardManagedDataSourceRegistry managedRegistry;

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Autowired
    private LavShardProperties lavShardProperties;

    private JdbcTemplate physicalJdbc;

    @BeforeEach
    void resetPhysicalTables() {
        DataSource physical = managedRegistry.dataSources().get("ds0");
        assertThat(physical).isNotNull();
        physicalJdbc = new JdbcTemplate(physical);
        for (String table : new String[]{"t_order_00", "t_order_01"}) {
            physicalJdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void shouldIntegrateWithMyBatisPlusAndLavShardInterceptor() {
        assertThat(sqlSessionFactory.getConfiguration())
                .isInstanceOf(MybatisConfiguration.class);
        assertThat(sqlSessionFactory.getConfiguration().getInterceptors())
                .anySatisfy(interceptor -> assertThat(
                        interceptor.getClass().getName()
                ).isEqualTo(
                        "io.github.lavyoung.lavshard.mybatis.internal."
                                + "executor.LavShardExecutorInterceptor"
                ));
        assertThat(sqlSessionFactory.getConfiguration()
                .getEnvironment().getDataSource())
                .isSameAs(routingDataSource);
    }

    @Test
    void shouldUseConciseSingleDatabaseTableLayout() {
        LavShardProperties.Layout layout =
                lavShardProperties.layouts().get("single-database-tables");

        assertThat(lavShardProperties.defaults().layout())
                .isEqualTo("single-database-tables");
        assertThat(layout).isNotNull();
        assertThat(layout.dataSourceIds()).containsExactly("ds0");
        assertThat(layout.tablesPerDataSource()).isEqualTo(2);
        assertThat(layout.bucketCount()).isEqualTo(2);
        assertThat(layout.tableSuffix().enabled()).isTrue();
        assertThat(lavShardProperties.tables().get("t_order"))
                .satisfies(table -> {
                    assertThat(table.shardingColumn()).isEqualTo("user_id");
                    assertThat(table.ruleVersion()).isEmpty();
                    assertThat(table.layout()).isEmpty();
                    assertThat(table.topology().nodes()).isEmpty();
                });
    }

    @Test
    void shouldBootAndRouteCompleteHttpCrudToTwoPhysicalTables()
            throws Exception {
        // Given
        CreateOrderRequest first = new CreateOrderRequest(
                101L,
                userIdForBucket(0),
                "first"
        );
        CreateOrderRequest second = new CreateOrderRequest(
                102L,
                userIdForBucket(1),
                "second"
        );

        // When / Then
        assertThat(routingDataSource).isNotNull();
        create(first);
        create(second);

        mockMvc.perform(get("/orders/{userId}", first.userId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(101L))
                .andExpect(jsonPath("$.userId").value(first.userId()))
                .andExpect(jsonPath("$.note").value("first"));

        mockMvc.perform(put("/orders/{userId}", first.userId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"updated"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("updated"));

        mockMvc.perform(delete("/orders/{userId}", second.userId()))
                .andExpect(status().isNoContent());

        assertThat(note("t_order_00", first.userId())).isEqualTo("updated");
        assertThat(count("t_order_00")).isEqualTo(1L);
        assertThat(count("t_order_01")).isZero();
    }

    @Test
    void shouldRollbackWritesAcrossTwoTablesInSameDatabase() {
        // Given
        physicalJdbc.update(
                "INSERT INTO t_order_01 (id, user_id, note) VALUES (?, ?, ?)",
                202L,
                "existing-user",
                "existing"
        );
        CreateOrderRequest first = new CreateOrderRequest(
                201L,
                userIdForBucket(0),
                "will-rollback"
        );
        CreateOrderRequest conflictingSecond = new CreateOrderRequest(
                202L,
                userIdForBucket(1),
                "conflict"
        );

        // When / Then
        assertThatThrownBy(() -> orderService.createPair(
                first,
                conflictingSecond
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("t_order_00")).isZero();
        assertThat(count("t_order_01")).isEqualTo(1L);
        assertThat(note("t_order_01", "existing-user"))
                .isEqualTo("existing");
    }

    private void create(CreateOrderRequest request) throws Exception {
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(request.id()))
                .andExpect(jsonPath("$.userId").value(request.userId()))
                .andExpect(jsonPath("$.note").value(request.note()));
    }

    private long count(String table) {
        return physicalJdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table,
                Long.class
        );
    }

    private String note(String table, String userId) {
        return physicalJdbc.queryForObject(
                "SELECT note FROM " + table + " WHERE user_id = ?",
                String.class,
                userId
        );
    }

    private static String userIdForBucket(int expectedBucket) {
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
                "No user id found for bucket " + expectedBucket
        );
    }
}
