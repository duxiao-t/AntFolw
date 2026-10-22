package com.antflow.integration.wecom;

import com.antflow.audit.AuditService;
import com.antflow.authz.AuthorizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Real database writes with controlled upstream responses and lease expiry. */
public final class WecomSyncLeaseFixture {
    public static void verifyRecovery(JdbcTemplate jdbc, TransactionTemplate transactions) {
        long company = jdbc.queryForObject("INSERT INTO t_company(name) VALUES (?) RETURNING id",
            Long.class, "sync-fixture-" + UUID.randomUUID());
        jdbc.update("INSERT INTO t_wecom_config(company_id, corp_id, secret_encrypted) VALUES (?, 'fixture', 'fixture')",
            company);
        long job = jdbc.queryForObject("INSERT INTO t_wecom_sync_job(company_id) VALUES (?) RETURNING id",
            Long.class, company);
        WecomClient client = mock(WecomClient.class);
        WecomClient.Session session = mock(WecomClient.Session.class);
        WecomSecretCipher cipher = mock(WecomSecretCipher.class);
        when(cipher.decrypt("fixture", company)).thenReturn("fixture");
        when(client.connect(anyString(), anyString())).thenReturn(session);
        when(client.departments(session)).thenReturn(List.of(
            new WecomClient.WecomDepartment(1, 0, 0, "部门", List.of())));
        var member = new WecomClient.WecomUser("sync_" + UUID.randomUUID().toString().replace("-", ""),
            "同步成员", List.of(1L), 1, "", "", "", "0", 1, List.of());
        when(client.user(any(), any())).thenReturn(member);
        WecomSyncLease lease = new WecomSyncLease(jdbc);
        WecomService service = new WecomService(jdbc, cipher, client, mock(AuthorizationService.class),
            mock(AuditService.class), new ObjectMapper(),
            transactions, Runnable::run, new WecomProperties(), lease,
            new WecomManagerSync(jdbc, client, transactions, lease),
            new WecomDirectorySync(jdbc, new BCryptPasswordEncoder(4), transactions, lease),
            new WecomDepartmentSync(jdbc, transactions, lease));
        when(client.users(session, List.of(1L))).thenAnswer(call -> {
            jdbc.update("UPDATE t_wecom_sync_job SET lease_expires_at = now() - interval '1 second' WHERE id = ?",
                job);
            service.renewSyncLeases();
            assertThat(jdbc.queryForObject(
                "SELECT lease_expires_at <= now() FROM t_wecom_sync_job WHERE id = ?", Boolean.class, job))
                .isTrue();
            assertThat(service.claimJob(job)).isNotNull();
            return List.of(member);
        });
        ReflectionTestUtils.invokeMethod(service, "run", job, null);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM t_wecom_user_mapping WHERE company_id = ?", Long.class, company)).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT status FROM t_wecom_sync_job WHERE id = ?", String.class, job)).isEqualTo("RUNNING");

        doReturn(List.of(member)).when(client).users(session, List.of(1L));
        jdbc.update("UPDATE t_wecom_sync_job SET lease_expires_at = now() - interval '1 second' WHERE id = ?", job);
        service.recoverInterruptedJobs();
        assertThat(jdbc.queryForMap(
            "SELECT status, processed_users, created_users FROM t_wecom_sync_job WHERE id = ?", job))
            .containsEntry("status", "SUCCESS").containsEntry("processed_users", 1).containsEntry("created_users", 1);
        long localUser = jdbc.queryForObject(
            "SELECT user_id FROM t_wecom_user_mapping WHERE company_id = ?", Long.class, company);
        String password = jdbc.queryForObject("SELECT password_hash FROM t_user WHERE id = ?", String.class, localUser);

        long replay = jdbc.queryForObject(
            "INSERT INTO t_wecom_sync_job(company_id, sync_mode) VALUES (?, 'FULL') RETURNING id", Long.class, company);
        ReflectionTestUtils.invokeMethod(service, "run", replay, null);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM t_wecom_user_mapping WHERE company_id = ?", Long.class, company)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM t_user WHERE id = ?", String.class, localUser))
            .isEqualTo(password);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM t_department WHERE company_id = ?", Long.class, company)).isEqualTo(1);

        long failed = jdbc.queryForObject(
            "INSERT INTO t_wecom_sync_job(company_id) VALUES (?) RETURNING id", Long.class, company);
        doThrow(new WecomClient.WecomApiException("upstream interrupted")).when(client).users(session, List.of(1L));
        ReflectionTestUtils.invokeMethod(service, "run", failed, null);
        assertThat(jdbc.queryForObject("SELECT status FROM t_wecom_sync_job WHERE id = ?", String.class, failed))
            .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM t_user WHERE id = ?", String.class, localUser))
            .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
            "SELECT directory_present FROM t_wecom_user_mapping WHERE user_id = ?", Boolean.class, localUser))
            .isTrue();
        verify(client, atLeast(4)).users(session, List.of(1L));
    }

    private WecomSyncLeaseFixture() { }
}
