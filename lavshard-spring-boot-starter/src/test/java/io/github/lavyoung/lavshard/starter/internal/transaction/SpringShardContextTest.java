package io.github.lavyoung.lavshard.starter.internal.transaction;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardBucket;
import io.github.lavyoung.lavshard.core.api.exception.CrossShardTransactionException;
import io.github.lavyoung.lavshard.core.api.route.*;
import io.github.lavyoung.lavshard.core.api.topology.QualifiedTableName;
import io.github.lavyoung.lavshard.core.api.topology.ShardNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Spring 本地事务路由绑定契约。
 *
 * <p>事务第一次访问时固定物理数据源；Managed 路由还必须固定规则
 * 与拓扑版本。后续不兼容路由必须在 Executor 和数据源访问前失败，
 * 事务完成后绑定必须可靠清理。</p>
 */
class SpringShardContextTest {

    private final SpringShardContext context =
            new SpringShardContext();

    @AfterEach
    void cleanTransactionState() {
        completeTransaction();
        TransactionSynchronizationManager.clear();
    }

    @Test
    void shouldNotBindRoutesOutsideTransaction() {
        assertThatCode(() -> {
            context.validate(passThrough("ds0"));
            context.validate(passThrough("ds1"));
        }).doesNotThrowAnyException();
    }

    @Test
    void shouldAllowRepeatedManagedRoutesToSameTransactionTarget() {
        beginTransaction();

        assertThatCode(() -> {
            context.validate(managed("ds0", "rule-v1", "topology-v1"));
            context.validate(managed("ds0", "rule-v1", "topology-v1"));
        }).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectManagedRouteToDifferentDataSource() {
        beginTransaction();
        context.validate(managed("ds0", "rule-v1", "topology-v1"));

        assertThatThrownBy(() -> context.validate(
                managed("ds1", "rule-v1", "topology-v1")
        ))
                .isInstanceOf(CrossShardTransactionException.class)
                .hasMessageContaining("ds0")
                .hasMessageContaining("ds1");
    }

    @Test
    void shouldLogTransactionBindingAndRejectionWithoutSqlText() {
        Logger logger = (Logger) LoggerFactory.getLogger(SpringShardContext.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);

        try {
            beginTransaction();
            context.validate(managed("ds0", "rule-v1", "topology-v1"));

            assertThatThrownBy(() -> context.validate(
                    managed("ds1", "rule-v1", "topology-v1")
            )).isInstanceOf(CrossShardTransactionException.class);

            List<String> messages = appender.list.stream()
                    .map(event -> event.getFormattedMessage())
                    .toList();
            assertThat(messages).anySatisfy(message -> assertThat(message)
                    .contains(
                            "transaction route bound",
                            "decision=MANAGED",
                            "dataSourceId=ds0",
                            "ruleVersion=rule-v1",
                            "topologyVersion=topology-v1"
                    ));
            assertThat(messages).anySatisfy(message -> assertThat(message)
                    .contains(
                            "reason=CROSS_DATA_SOURCE",
                            "boundDataSourceId=ds0",
                            "requestedDataSourceId=ds1"
                    ));
            assertThat(String.join("\n", messages))
                    .doesNotContain("SELECT * FROM");
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }
    }

    @Test
    void shouldApplyDataSourceGuardToPassThroughDecision() {
        beginTransaction();
        context.validate(managed("ds0", "rule-v1", "topology-v1"));

        assertThatThrownBy(() ->
                context.validate(passThrough("ds-default")))
                .isInstanceOf(CrossShardTransactionException.class)
                .hasMessageContaining("ds0")
                .hasMessageContaining("ds-default");
    }

    @Test
    void shouldAllowManagedAndPassThroughRoutesOnSameDataSource() {
        beginTransaction();

        assertThatCode(() -> {
            context.validate(passThrough("ds0"));
            context.validate(managed("ds0", "rule-v1", "topology-v1"));
            context.validate(passThrough("ds0"));
        }).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectRuleOrTopologyVersionChangeInTransaction() {
        beginTransaction();
        context.validate(managed("ds0", "rule-v1", "topology-v1"));

        assertThatThrownBy(() -> context.validate(
                managed("ds0", "rule-v2", "topology-v1")
        ))
                .isInstanceOf(CrossShardTransactionException.class)
                .hasMessageContaining("rule-v1")
                .hasMessageContaining("rule-v2");
        assertThatThrownBy(() -> context.validate(
                managed("ds0", "rule-v1", "topology-v2")
        ))
                .isInstanceOf(CrossShardTransactionException.class)
                .hasMessageContaining("topology-v1")
                .hasMessageContaining("topology-v2");
    }

    @Test
    void shouldClearBindingAfterTransactionCompletion() {
        beginTransaction();
        context.validate(managed("ds0", "rule-v1", "topology-v1"));
        completeTransaction();

        beginTransaction();

        assertThatCode(() -> context.validate(
                managed("ds1", "rule-v2", "topology-v2")
        )).doesNotThrowAnyException();
    }

    @Test
    void shouldRequireActiveSpringSynchronizationBeforeBinding() {
        TransactionSynchronizationManager
                .setActualTransactionActive(true);

        assertThatThrownBy(() ->
                context.validate(passThrough("ds0")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "Transaction synchronization is not active"
                );
    }

    private static void beginTransaction() {
        TransactionSynchronizationManager
                .setActualTransactionActive(true);
        TransactionSynchronizationManager
                .initSynchronization();
    }

    @Test
    void shouldSuspendResourceAndResumeOriginalVersionBinding() {
        // Given: capture the exact binding, not only its dataSourceId.
        beginTransaction();
        context.validate(managed("ds0", "rule-v1", "topology-v1"));
        var originalResources = TransactionSynchronizationManager.getResourceMap();
        var synchronization = TransactionSynchronizationManager.getSynchronizations().get(0);

        // When / Then: suspend removes only this context's resource.
        Object unrelatedKey = new Object();
        Object unrelatedValue = new Object();
        TransactionSynchronizationManager.bindResource(unrelatedKey, unrelatedValue);
        try {
            synchronization.suspend();
            try {
                assertThat(TransactionSynchronizationManager.getResourceMap())
                        .containsOnlyKeys(unrelatedKey);
            } finally {
                synchronization.resume();
            }
            originalResources.forEach((key, value) ->
                    assertThat(TransactionSynchronizationManager.getResource(key)).isSameAs(value));
            assertThat(TransactionSynchronizationManager.getResource(unrelatedKey)).isSameAs(unrelatedValue);
            assertThatCode(() -> context.validate(managed("ds0", "rule-v1", "topology-v1")))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> context.validate(managed("ds0", "rule-v2", "topology-v1")))
                    .isInstanceOf(CrossShardTransactionException.class);
            assertThatThrownBy(() -> context.validate(managed("ds0", "rule-v1", "topology-v2")))
                    .isInstanceOf(CrossShardTransactionException.class);
        } finally {
            TransactionSynchronizationManager.unbindResourceIfPossible(unrelatedKey);
        }
    }

    private static void completeTransaction() {
        if (TransactionSynchronizationManager
                .isSynchronizationActive()) {
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager
                            .getSynchronizations();
            synchronizations.forEach(
                    TransactionSynchronization::beforeCompletion
            );
            synchronizations.forEach(synchronization ->
                    synchronization.afterCompletion(
                            TransactionSynchronization.STATUS_COMMITTED
                    )
            );
            TransactionSynchronizationManager
                    .clearSynchronization();
        }

        TransactionSynchronizationManager
                .setActualTransactionActive(false);
    }

    private static PassThroughDecision passThrough(
            String dataSourceId
    ) {
        return new PassThroughDecision(
                dataSourceId,
                "SELECT 1"
        );
    }

    private static ManagedRouteDecision managed(
            String dataSourceId,
            String ruleVersion,
            String topologyVersion
    ) {
        ShardNode node = new ShardNode(
                "node-" + dataSourceId,
                dataSourceId,
                new QualifiedTableName("t_order_00")
        );
        RouteUnit unit = new RouteUnit(
                new ShardTarget(
                        new ShardBucket(0),
                        node
                ),
                new SqlRewriteResult(
                        "SELECT * FROM t_order_00 WHERE user_id = ?",
                        List.of(0)
                )
        );
        return new ManagedRouteDecision(new RoutePlan(
                RouteMode.SINGLE,
                ruleVersion,
                topologyVersion,
                List.of(unit)
        ));
    }
}
