package com.antflow.integration.wecom;

import com.antflow.audit.AuditService;
import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.engine.BizException;
import com.antflow.integration.wecom.WecomClient.WecomDepartment;
import com.antflow.integration.wecom.WecomClient.WecomUser;
import com.antflow.integration.wecom.WecomClient.WecomUserRef;
// 配置对象随 WecomConfigService 搬走；run() 里仍需要一个具名类型来持有它。
import com.antflow.integration.wecom.WecomConfigService.Config;
// 目录同步的工作区随 WecomDirectorySync 搬走；import 进来后 run() 里的类型声明不用改。
import com.antflow.integration.wecom.WecomDirectorySync.SyncContext;
// 映射规则的类型契约已搬到 WecomMappingRules；这里 import 进来，调用点保持原名。
import com.antflow.integration.wecom.WecomMappingRules.LocalUserState;
import com.antflow.integration.wecom.WecomMappingRules.SyncUserException;
// 租约丢失的异常类型随租约一起搬到 WecomSyncLease；这里 import 进来，catch 处保持原名。
import com.antflow.integration.wecom.WecomSyncLease.LeaseLostException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Slf4j
public class WecomService {
    private static final int USER_BATCH_SIZE = 200;
    // 直属上级同步的并发度与限流间隔已随 WecomManagerSync 搬走（MANAGER_POOL_SIZE / MANAGER_BATCH_INTERVAL_MS）。
    private final JdbcTemplate jdbc;
    private final WecomSecretCipher cipher;
    private final WecomClient client;
    private final AuthorizationService authorization;
    private final AuditService audit;
    private final TransactionTemplate transactions;
    private final Executor executor;
    private final WecomProperties properties;
    /** 租约状态与校验都在 WecomSyncLease；本类只保留调度入口（renewSyncLeases）。 */
    private final WecomSyncLease lease;
    /** 直属上级同步：并发拉详情、查环、批量写 manager_id。 */
    private final WecomManagerSync managerSync;
    /** 通讯录落库：成员加载、分批写入、离职停用。 */
    private final WecomDirectorySync directorySync;
    /** 部门树落库：ltree 路径维护与部门负责人。 */
    private final WecomDepartmentSync departmentSync;
    /** 连接配置与同步计划；settings/saveSettings 的实现在这里，本类只做门面。 */
    private final WecomConfigService configService;
    /** 同步任务持久化：创建、进度、收尾、查询。 */
    private final WecomJobStore jobStore;
    /** 公司存在性与归属校验，三个协作者共用。 */
    private final WecomAccessGuard accessGuard;

    public WecomService(JdbcTemplate jdbc, WecomSecretCipher cipher, WecomClient client,
                        AuthorizationService authorization, AuditService audit,
                        TransactionTemplate transactions,
                        @Qualifier("wecomSyncExecutor") Executor executor,
                        WecomProperties properties,
                        WecomSyncLease lease,
                        WecomManagerSync managerSync,
                        WecomDirectorySync directorySync,
                        WecomDepartmentSync departmentSync,
                        WecomConfigService configService,
                        WecomJobStore jobStore,
                        WecomAccessGuard accessGuard) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.client = client;
        this.authorization = authorization;
        this.audit = audit;
        this.transactions = transactions;
        this.executor = executor;
        this.properties = properties;
        this.lease = lease;
        this.managerSync = managerSync;
        this.directorySync = directorySync;
        this.departmentSync = departmentSync;
        this.configService = configService;
        this.jobStore = jobStore;
        this.accessGuard = accessGuard;
    }

    public SettingsDto settings(long companyId) {
        return configService.settings(companyId);
    }

    @Transactional(rollbackFor = Exception.class)
    public SettingsDto saveSettings(long companyId, String corpId, String secret) {
        return configService.saveSettings(companyId, corpId, secret);
    }

    @Transactional(rollbackFor = Exception.class)
    public SettingsDto saveSettings(long companyId, String corpId, String secret, Integer agentId,
                                    String agentSecret, boolean oauthEnabled,
                                    boolean jsSdkEnabled, boolean messageEnabled) {
        return configService.saveSettings(companyId, corpId, secret, agentId, agentSecret,
            oauthEnabled, jsSdkEnabled, messageEnabled);
    }

    @Transactional(rollbackFor = Exception.class)
    public SettingsDto saveSettings(long companyId, String corpId, String secret, Integer agentId,
                                    String agentSecret, Boolean oauthEnabled,
                                    Boolean jsSdkEnabled, Boolean messageEnabled,
                                    Boolean scheduleEnabled, String scheduleTime,
                                    String scheduleMode) {
        return configService.saveSettings(companyId, corpId, secret, agentId, agentSecret,
            oauthEnabled, jsSdkEnabled, messageEnabled, scheduleEnabled, scheduleTime, scheduleMode);
    }

    @Transactional(rollbackFor = Exception.class)
    public JobDto start(long companyId, String syncMode) {
        authorization.requirePermission(PermissionCodes.INTEGRATION_WECOM_MANAGE);
        authorization.requireAllDataScope(PermissionCodes.ORG_DEPARTMENT_MANAGE);
        authorization.requireAllDataScope(PermissionCodes.ORG_USER_MANAGE);
        accessGuard.requireCompanyAccess(companyId);
        if (configService.config(companyId) == null) {
            throw new BizException("WECOM_NOT_CONFIGURED", "请先保存企业微信连接配置");
        }
        String mode = "FULL".equalsIgnoreCase(syncMode) ? "FULL" : "INCREMENTAL";
        PrincipalHolder.Principal actor = PrincipalHolder.current().orElseThrow();
        Long id = jobStore.create(companyId, mode, actor.userId());
        if (id == null) return jobStore.activeJob(companyId);

        audit.success("integration.wecom.sync.start", "WECOM_SYNC_JOB", id,
            AuditService.RiskLevel.HIGH, Map.of(), Map.of("companyId", companyId));
        long jobId = id;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit(jobId, actor);
            }
        });
        return jobStore.job(jobId, false);
    }

    public JobDto job(long id) {
        authorization.requirePermission(PermissionCodes.INTEGRATION_WECOM_MANAGE);
        JobDto result = jobStore.job(id, true);
        accessGuard.requireCompanyAccess(result.companyId());
        return result;
    }

    @Scheduled(fixedDelayString = "${antflow.wecom.schedule-poll-interval-ms:60000}",
        initialDelayString = "${antflow.wecom.schedule-poll-interval-ms:60000}")
    void runScheduledSync() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(properties.getZoneId()));
        runScheduledSync(now.toLocalDate(), now.toLocalTime());
    }

    void runScheduledSync(LocalDate date, LocalTime time) {
        List<Long> companyIds = jdbc.queryForList("""
            SELECT company_id
            FROM t_wecom_config
            WHERE schedule_enabled AND schedule_time <= ?
              AND (schedule_last_run_date IS NULL OR schedule_last_run_date < ?)
            ORDER BY company_id
            """, Long.class, time, date);
        for (long companyId : companyIds) {
            Long jobId = claimScheduledJob(companyId, date, time);
            if (jobId == null) continue;
            log.info("Scheduled WeCom directory sync: company={}, job={}", companyId, jobId);
            submit(jobId, null);
        }
    }

    Long claimScheduledJob(long companyId, LocalDate date, LocalTime time) {
        return transactions.execute(status -> {
            String mode = jdbc.query("""
                UPDATE t_wecom_config
                SET schedule_last_run_date = ?, updated_at = now()
                WHERE company_id = ? AND schedule_enabled AND schedule_time <= ?
                  AND (schedule_last_run_date IS NULL OR schedule_last_run_date < ?)
                  AND NOT EXISTS (
                    SELECT 1 FROM t_wecom_sync_job
                    WHERE company_id = ? AND status IN ('PENDING', 'RUNNING')
                  )
                RETURNING schedule_mode
                """, result -> result.next() ? result.getString(1) : null,
                date, companyId, time, date, companyId);
            if (mode == null) return null;
            Long jobId = jdbc.query("""
                INSERT INTO t_wecom_sync_job(company_id, initiated_by, sync_mode, message)
                VALUES (?, NULL, ?, '自动同步任务已排队')
                ON CONFLICT (company_id) WHERE status IN ('PENDING', 'RUNNING') DO NOTHING
                RETURNING id
                """, result -> result.next() ? result.getLong(1) : null, companyId, mode);
            if (jobId == null) status.setRollbackOnly();
            return jobId;
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString = "${antflow.wecom.recovery-interval-ms:30000}")
    public void recoverInterruptedJobs() {
        List<Long> ids = jdbc.queryForList("""
            SELECT id FROM t_wecom_sync_job
            WHERE status = 'PENDING'
               OR (status = 'RUNNING' AND (lease_expires_at IS NULL OR lease_expires_at <= now()))
            ORDER BY created_at, id LIMIT 10
            """, Long.class);
        ids.forEach(id -> submit(id, null));
    }

    @Scheduled(fixedDelayString = "${antflow.wecom.lease-renew-interval-ms:30000}")
    public void renewSyncLeases() {
        lease.renewAll();
    }

    /**
     * 保留包内入口：{@code WecomSyncLeaseFixture} 直接调用，
     * {@code PostgresTransactionalIntegrityIntegrationTest} 用 {@code ReflectionTestUtils} 按方法名调用。
     * 实现已搬到 {@link WecomSyncLease#claim(long)}——不要在这里加逻辑。
     */
    UUID claimJob(long jobId) {
        return lease.claim(jobId);
    }

    private void submit(long jobId, PrincipalHolder.Principal actor) {
        try {
            executor.execute(() -> run(jobId, actor));
        } catch (RejectedExecutionException exception) {
            log.info("WeCom sync {} remains queued until worker capacity is available", jobId);
        }
    }

    private void run(long jobId, PrincipalHolder.Principal actor) {
        UUID token = claimJob(jobId);
        if (token == null) return;
        // 必须与 finally 里的 exit 成对：租约挂在 ThreadLocal 上，漏掉会让复用线程的下一个任务继承它。
        lease.enter(jobId, token);
        boolean terminal = false;
        try {
            JobDto initial = jobStore.job(jobId, false);
            Config config = configService.config(initial.companyId());
            if (config == null) throw new WecomClient.WecomApiException("企业微信配置不存在");
            boolean full = "FULL".equals(initial.syncMode());
            jobStore.stage(jobId, "CONNECTING", 5, "正在连接企业微信");
            WecomClient.Session session = client.connect(config.corpId(),
                cipher.decrypt(config.encryptedSecret(), initial.companyId()));

            jobStore.stage(jobId, "DEPARTMENTS", 12, "正在同步部门树");
            List<WecomDepartment> departments = WecomMappingRules.orderDepartments(client.departments(session));
            Map<Long, Long> departmentMappings = departmentSync.syncDepartments(initial.companyId(), departments);
            jobStore.stage(jobId, "DEPARTMENTS", 25, "部门同步完成");

            jobStore.stage(jobId, "FETCHING_USERS", 30, "正在获取成员列表");
            List<WecomUser> externalUsers = client.users(session,
                departments.stream().map(WecomDepartment::id).toList());
            transactions.executeWithoutResult(status -> {
                lease.require();
                jdbc.update("UPDATE t_wecom_sync_job SET total_users = ?, updated_at = now() WHERE id = ?",
                    externalUsers.size(), jobId);
            });

            List<String> errors = new CopyOnWriteArrayList<>();
            SyncContext context = directorySync.loadContext(initial.companyId(), externalUsers, full);
            List<WecomUser> pending = List.copyOf(context.pending);
            int created = 0;
            int updated = 0;
            int failed = 0;
            for (int offset = 0; offset < pending.size(); offset += USER_BATCH_SIZE) {
                List<WecomUser> batch = pending.subList(offset,
                    Math.min(offset + USER_BATCH_SIZE, pending.size()));
                try {
                    SyncContext batchContext = context;
                    int[] counts = transactions.execute(status -> {
                        lease.require();
                        return directorySync.syncUsers(initial.companyId(), batch, departmentMappings, batchContext);
                    });
                    created += counts[0];
                    updated += counts[1];
                } catch (RuntimeException exception) {
                    if (exception instanceof LeaseLostException) throw exception;
                    log.warn("WeCom member batch write failed: job={}, batch={}", jobId,
                        offset / USER_BATCH_SIZE + 1, exception);
                    SyncContext retryContext = directorySync.loadContext(initial.companyId(), externalUsers, full);
                    for (WecomUser user : batch) {
                        try {
                            SyncContext memberContext = retryContext;
                            int[] counts = transactions.execute(status -> {
                                lease.require();
                                return directorySync.syncUsers(initial.companyId(), List.of(user),
                                    departmentMappings, memberContext);
                            });
                            created += counts[0];
                            updated += counts[1];
                        } catch (RuntimeException memberException) {
                            if (memberException instanceof LeaseLostException) throw memberException;
                            failed++;
                            log.warn("WeCom member write failed: job={}, member={}", jobId,
                                WecomMappingRules.mask(user.userId()), memberException);
                            WecomSyncErrors.addError(errors, WecomSyncErrors.memberError(user, memberException));
                            retryContext = directorySync.loadContext(initial.companyId(), externalUsers, full);
                        }
                    }
                    context = retryContext;
                }
                int processed = Math.min(offset + USER_BATCH_SIZE, pending.size());
                int percent = pending.isEmpty() ? 75
                    : 35 + (int) Math.floor(processed * 40.0 / pending.size());
                jobStore.progress(jobId, percent, externalUsers.size(), processed, created, updated, failed,
                    "正在同步成员 " + processed + "/" + pending.size(), errors);
            }

            jobStore.stage(jobId, "RELATIONS", 88, "正在同步部门负责人和直属上级");
            // 部门负责人同步与直属上级同步都改为"返回问题列表"，错误上限与截断仍由本类统一执行。
            for (String problem : departmentSync.syncDepartmentLeaders(initial.companyId(), departments)) {
                WecomSyncErrors.addError(errors, problem);
            }
            // 直属上级同步已搬到 WecomManagerSync；它只返回"发生了什么"，错误上限由本类统一执行。
            for (String problem : managerSync.sync(initial.companyId(), session, externalUsers,
                context.mappedIds, context.byId.values())) {
                WecomSyncErrors.addError(errors, problem);
            }
            int disabled = directorySync.deactivateMissing(initial.companyId(), externalUsers);
            String summary = "通讯录同步完成"
                + (disabled > 0 ? "，已停用 " + disabled + " 名成员" : "");
            boolean clean = failed == 0 && errors.isEmpty();
            jobStore.finish(jobId, clean ? "SUCCESS" : "PARTIAL",
                clean ? summary : summary + "，部分数据未能同步", errors);
            terminal = true;
        } catch (LeaseLostException exception) {
            log.info("WeCom sync {} stopped because its lease was lost", jobId);
        } catch (RuntimeException exception) {
            terminal = jobStore.fail(jobId, WecomSyncErrors.fatalError(exception), List.of(WecomSyncErrors.fatalError(exception)));
        } finally {
            lease.exit(jobId, token);
            if (terminal) jobStore.auditResult(jobId, actor);
        }
    }



    public record SettingsDto(long companyId, String corpId, boolean secretConfigured,
                              JobDto latestJob, Integer agentId, boolean agentSecretConfigured,
                              boolean oauthEnabled, boolean jsSdkEnabled,
                              boolean messageEnabled, boolean scheduleEnabled,
                              String scheduleTime, String scheduleMode) {
        public SettingsDto(long companyId, String corpId, boolean secretConfigured, JobDto latestJob) {
            this(companyId, corpId, secretConfigured, latestJob, null, false, false, false, false,
                false, "03:00:00", "INCREMENTAL");
        }
    }
    public record JobDto(long id, long companyId, String status, String phase, int percent,
                         int totalUsers, int processedUsers, int createdUsers, int updatedUsers,
                         int failedUsers, String message, List<String> errorSummary,
                         OffsetDateTime startedAt, OffsetDateTime finishedAt,
                         String syncMode) {
        public JobDto(long id, long companyId, String status, String phase, int percent,
                      int totalUsers, int processedUsers, int createdUsers, int updatedUsers,
                      int failedUsers, String message, List<String> errorSummary,
                      OffsetDateTime startedAt, OffsetDateTime finishedAt) {
            this(id, companyId, status, phase, percent, totalUsers, processedUsers, createdUsers,
                updatedUsers, failedUsers, message, errorSummary, startedAt, finishedAt,
                "FULL");
        }
    }
}
