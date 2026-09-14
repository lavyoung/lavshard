package io.github.lavyoung.lavshard.example.multi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardValue;
import io.github.lavyoung.lavshard.core.api.exception.CrossShardTransactionException;
import io.github.lavyoung.lavshard.core.internal.algorithm.Murmur3HashShardAlgorithm;
import io.github.lavyoung.lavshard.example.multi.order.api.CreateOrderRequest;
import io.github.lavyoung.lavshard.example.multi.order.service.OrderService;
import io.github.lavyoung.lavshard.mybatis.internal.routing.LavShardRoutingDataSource;
import io.github.lavyoung.lavshard.starter.internal.datasource.LavShardManagedDataSourceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 两个物理数据库使用同名表时的示例启动、路由和事务验收测试。
 */
@SpringBootTest(
        classes = MultiDataSourceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
@AutoConfigureMockMvc
class MultiDataSourceApplicationTest {

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
    private LavShardRoutingDataSource routingDataSource;

    @Autowired
    private LavShardManagedDataSourceRegistry managedRegistry;

    private HikariDataSource dataSource0;
    private HikariDataSource dataSource1;
    private JdbcTemplate jdbc0;
    private JdbcTemplate jdbc1;

    @BeforeEach
    void resetPhysicalDatabases() {
        Map<String, javax.sql.DataSource> dataSources =
                managedRegistry.dataSources();
        assertThat(dataSources).containsOnlyKeys("ds0", "ds1");
        dataSource0 = (HikariDataSource) dataSources.get("ds0");
        dataSource1 = (HikariDataSource) dataSources.get("ds1");
        jdbc0 = new JdbcTemplate(dataSource0);
        jdbc1 = new JdbcTemplate(dataSource1);
        jdbc0.update("DELETE FROM t_order");
        jdbc1.update("DELETE FROM t_order");
    }

    @Test
    void shouldRouteHttpRequestsToSameNamedTablesInDifferentDatabases()
            throws Exception {
        // Given
        CreateOrderRequest first = new CreateOrderRequest(
                101L,
                userIdForBucket(0),
                "database-zero"
        );
        CreateOrderRequest second = new CreateOrderRequest(
                102L,
                userIdForBucket(1),
                "database-one"
        );

        // When
        assertThat(routingDataSource).isNotNull();
        create(first);
        create(second);

        // Then
        assertOrder(first);
        assertOrder(second);
        assertThat(count(jdbc0)).isEqualTo(1L);
        assertThat(count(jdbc1)).isEqualTo(1L);
        assertThat(note(jdbc0, first.userId()))
                .isEqualTo("database-zero");
        assertThat(note(jdbc1, second.userId()))
                .isEqualTo("database-one");
        assertThat(count(jdbc0, second.userId())).isZero();
        assertThat(count(jdbc1, first.userId())).isZero();
    }

    @Test
    void shouldRejectCrossDatabaseTransactionBeforeBorrowingSecondConnection() {
        // Given
        CreateOrderRequest first = new CreateOrderRequest(
                201L,
                userIdForBucket(0),
                "will-rollback"
        );
        CreateOrderRequest second = new CreateOrderRequest(
                202L,
                userIdForBucket(1),
                "must-not-run"
        );
        int ds1ConnectionsBefore = dataSource1.getHikariPoolMXBean()
                .getTotalConnections();

        // When / Then
        assertThatThrownBy(() -> orderService.createPair(first, second))
                .hasRootCauseInstanceOf(CrossShardTransactionException.class);
        assertThat(count(jdbc0)).isZero();
        assertThat(count(jdbc1)).isZero();
        assertThat(dataSource1.getHikariPoolMXBean().getTotalConnections())
                .isEqualTo(ds1ConnectionsBefore);
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

    private void assertOrder(CreateOrderRequest request) throws Exception {
        mockMvc.perform(get("/orders/{userId}", request.userId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(request.id()))
                .andExpect(jsonPath("$.userId").value(request.userId()))
                .andExpect(jsonPath("$.note").value(request.note()));
    }

    private static long count(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_order",
                Long.class
        );
    }

    private static long count(JdbcTemplate jdbc, String userId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_order WHERE user_id = ?",
                Long.class,
                userId
        );
    }

    private static String note(JdbcTemplate jdbc, String userId) {
        return jdbc.queryForObject(
                "SELECT note FROM t_order WHERE user_id = ?",
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
