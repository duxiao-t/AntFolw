package com.antflow.integration.wecom;

import com.antflow.integration.wecom.WecomClient.WecomUser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link WecomManagerSync#managerUpdate} 的特征测试。
 *
 * <p>这段原来是 {@code WecomService.syncManagers} 的一部分，**没有任何单测**——它决定"谁的上级写谁"，
 * 判错的表现是"组织树里上级挂错人"，而集成测试只覆盖少数几条路径。这里把每个短路分支固定下来。
 *
 * <p>只测判定，不测并发拉取与批量写：那两部分由 {@code PostgresTransactionalIntegrityIntegrationTest}
 * 在真库上覆盖。走完整 {@code sync()} 需要 mock 线程池与事务回调，成本高于收益。
 */
class WecomManagerSyncTest {

    private final WecomManagerSync sync = new WecomManagerSync(
        mock(JdbcTemplate.class), mock(WecomClient.class),
        mock(TransactionTemplate.class), mock(WecomSyncLease.class));

    /** 构造外部成员：directLeaders 为企微返回的直属上级 userId 列表。 */
    private static WecomUser user(String userId, List<String> leaders, int status) {
        return new WecomUser(userId, "成员" + userId, List.of(1L), 1, "", "", "", "0", status, leaders);
    }

    @Nested
    class SkipAndClear {

        @Test
        void skipsMemberWithoutLocalMapping() {
            // 成员本人没同步成功时整条跳过，不能凭外部 id 猜一个本地 id。
            assertThat(sync.managerUpdate(1L, user("u1", List.of("u2"), 1), Map.of(), Map.of())).isNull();
        }

        @Test
        void clearsWhenNoLeaderConfigured() {
            WecomManagerSync.ManagerUpdate update =
                sync.managerUpdate(1L, user("u1", List.of(), 1), Map.of("u1", 10L), Map.of());

            assertThat(update.userId()).isEqualTo(10L);
            assertThat(update.managerId()).isNull();
            // 没配上级是正常状态，不该产生一条"问题"打扰用户。
            assertThat(update.issue()).isNull();
        }
    }

    @Nested
    class RejectsUnusableLeader {

        @Test
        void clearsWhenLeaderIsNotInCurrentBatch() {
            WecomManagerSync.ManagerUpdate update =
                sync.managerUpdate(1L, user("u1", List.of("ghost"), 1), Map.of("u1", 10L), Map.of());

            assertThat(update.managerId()).isNull();
            assertThat(update.issue()).contains("未同步或已停用");
        }

        @Test
        void clearsWhenLeaderIsDisabledUpstream() {
            // status != 1（企微里已停用/未激活）时不能写上级，否则本地组织树挂到离职账号上。
            WecomManagerSync.ManagerUpdate update = sync.managerUpdate(1L, user("u1", List.of("u2"), 1),
                Map.of("u1", 10L, "u2", 20L), Map.of("u2", user("u2", List.of(), 4)));

            assertThat(update.managerId()).isNull();
            assertThat(update.issue()).contains("未同步或已停用");
        }

        @Test
        void clearsWhenLeaderHasNoLocalMapping() {
            WecomManagerSync.ManagerUpdate update = sync.managerUpdate(1L, user("u1", List.of("u2"), 1),
                Map.of("u1", 10L), Map.of("u2", user("u2", List.of(), 1)));

            assertThat(update.managerId()).isNull();
            assertThat(update.issue()).contains("未同步或已停用");
        }
    }

    @Nested
    class ReportsPartialConfiguration {

        @Test
        void reportsMultipleLeadersWhileKeepingTheFirst() {
            // 企微允许多个直属上级，本地只存一个：保留第一位并明确告知，不能静默丢弃。
            WecomManagerSync.ManagerUpdate update = sync.managerUpdate(1L,
                user("u1", List.of("u2", "u3"), 1), Map.of("u1", 10L, "u3", 30L),
                Map.of("u2", user("u2", List.of(), 4), "u3", user("u3", List.of(), 4)));

            assertThat(update.issue()).contains("配置多位直属上级");
        }
    }
}
