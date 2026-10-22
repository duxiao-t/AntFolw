package com.antflow.integration.wecom;

import com.antflow.audit.AuditService;
import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.engine.BizException;
import com.antflow.integration.wecom.WecomClient.WecomDepartment;
import com.antflow.integration.wecom.WecomClient.WecomUser;
import com.antflow.integration.wecom.WecomClient.WecomUserRef;
// 映射规则的类型契约已搬到 WecomMappingRules；这里 import 进来，调用点保持原名。
import com.antflow.integration.wecom.WecomMappingRules.LocalUserState;
import com.antflow.integration.wecom.WecomMappingRules.SyncUserException;
// 租约丢失的异常类型随租约一起搬到 WecomSyncLease；这里 import 进来，catch 处保持原名。
import com.antflow.integration.wecom.WecomSyncLease.LeaseLostException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Slf4j
public class WecomService {
    private static final int MAX_ERROR_SUMMARIES = 10;
    private static final int USER_BATCH_SIZE = 200;
    // 直属上级同步的并发度与限流间隔已随 WecomManagerSync 搬走（MANAGER_POOL_SIZE / MANAGER_BATCH_INTERVAL_MS）。
    private static final LocalTime DEFAULT_SCHEDULE_TIME = LocalTime.of(3, 0);
    private static final DateTimeFormatter SCHEDULE_TIME_FORMAT =
        DateTimeFormatter.ofPattern("HH:mm:ss");
    private final JdbcTemplate jdbc;
    private final WecomSecretCipher cipher;
    private final WecomClient client;
    private final AuthorizationService authorization;
    private final AuditService audit;
    private final ObjectMapper json;
    private final PasswordEncoder passwords;
    private final TransactionTemplate transactions;
    private final Executor executor;
    private final WecomProperties properties;
    /** 租约状态与校验都在 WecomSyncLease；本类只保留调度入口（renewSyncLeases）。 */
    private final WecomSyncLease lease;
    /** 直属上级同步：并发拉详情、查环、批量写 manager_id。 */
    private final WecomManagerSync managerSync;

    public WecomService(JdbcTemplate jdbc, WecomSecretCipher cipher, WecomClient client,
                        AuthorizationService authorization, AuditService audit, ObjectMapper json,
                        PasswordEncoder passwords,
                        TransactionTemplate transactions,
                        @Qualifier("wecomSyncExecutor") Executor executor,
                        WecomProperties properties,
                        WecomSyncLease lease,
                        WecomManagerSync managerSync) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.client = client;
        this.authorization = authorization;
        this.audit = audit;
        this.json = json;
        this.passwords = passwords;
        this.transactions = transactions;
        this.executor = executor;
        this.properties = properties;
        this.lease = lease;
        this.managerSync = managerSync;
    }

    public SettingsDto settings(long companyId) {
        authorization.requirePermission(PermissionCodes.INTEGRATION_WECOM_MANAGE);
        requireCompanyAccess(companyId);
        Config config = config(companyId);
        return new SettingsDto(companyId, config == null ? "" : config.corpId(),
            config != null && !config.encryptedSecret().isBlank(), latestJob(companyId),
            config == null ? null : config.agentId(),
            config != null && config.encryptedAgentSecret() != null,
            config != null && config.oauthEnabled(), config != null && config.jsSdkEnabled(),
            config != null && config.messageEnabled(),
            config != null && config.scheduleEnabled(),
            formatScheduleTime(config == null ? DEFAULT_SCHEDULE_TIME : config.scheduleTime()),
            config == null ? "INCREMENTAL" : config.scheduleMode());
    }

    @Transactional(rollbackFor = Exception.class)
    public SettingsDto saveSettings(long companyId, String corpId, String secret) {
        Config current = config(companyId);
        return saveSettings(companyId, corpId, secret,
            current == null ? null : current.agentId(), null,
            current != null && current.oauthEnabled(), current != null && current.jsSdkEnabled(),
            current != null && current.messageEnabled(), null, null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public SettingsDto saveSettings(long companyId, String corpId, String secret, Integer agentId,
                                    String agentSecret, boolean oauthEnabled,
                                    boolean jsSdkEnabled, boolean messageEnabled) {
        return saveSettings(companyId, corpId, secret, agentId, agentSecret, oauthEnabled,
            jsSdkEnabled, messageEnabled, null, null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public SettingsDto saveSettings(long companyId, String corpId, String secret, Integer agentId,
                                    String agentSecret, Boolean oauthEnabled,
                                    Boolean jsSdkEnabled, Boolean messageEnabled,
                                    Boolean scheduleEnabled, String scheduleTime,
                                    String scheduleMode) {
        authorization.requirePermission(PermissionCodes.INTEGRATION_WECOM_MANAGE);
        requireCompanyAccess(companyId);
        String normalizedCorpId = corpId == null ? "" : corpId.trim();
        if (normalizedCorpId.isBlank() || normalizedCorpId.length() > 128) {
            throw new BizException("WECOM_CORP_ID_REQUIRED", "请输入有效的 CorpID");
        }
        Config current = config(companyId);
        String encrypted = secret == null || secret.isBlank()
            ? current == null ? null : current.encryptedSecret()
            : cipher.encrypt(secret.trim(), companyId);
        if (encrypted == null) {
            throw new BizException("WECOM_SECRET_REQUIRED", "首次配置时请输入通讯录同步 Secret");
        }
        String encryptedAgentSecret = agentSecret == null || agentSecret.isBlank()
            ? current == null ? null : current.encryptedAgentSecret()
            : cipher.encrypt(agentSecret.trim(), "wecom-agent:" + companyId);
        Integer savedAgentId = agentId == null && current != null ? current.agentId() : agentId;
        boolean savedOauthEnabled = oauthEnabled == null
            ? current != null && current.oauthEnabled() : oauthEnabled;
        boolean savedJsSdkEnabled = jsSdkEnabled == null
            ? current != null && current.jsSdkEnabled() : jsSdkEnabled;
        boolean savedMessageEnabled = messageEnabled == null
            ? current != null && current.messageEnabled() : messageEnabled;
        boolean appEnabled = savedOauthEnabled || savedJsSdkEnabled || savedMessageEnabled;
        if (appEnabled && (savedAgentId == null || savedAgentId <= 0 || encryptedAgentSecret == null)) {
            throw new BizException("WECOM_APP_REQUIRED", "启用免登、JS-SDK 或应用消息前请配置 AgentId 和应用 Secret");
        }
        if (savedOauthEnabled) {
            Integer otherOauth = jdbc.queryForObject("""
                SELECT count(*) FROM t_wecom_config WHERE oauth_enabled AND company_id <> ?
                """, Integer.class, companyId);
            if (otherOauth != null && otherOauth > 0) {
                throw new BizException("WECOM_OAUTH_CONFLICT", "当前只允许启用一个企业微信 Corp 免登入口");
            }
        }
        boolean savedScheduleEnabled = scheduleEnabled == null
            ? current != null && current.scheduleEnabled() : scheduleEnabled;
        LocalTime savedScheduleTime = scheduleTime == null
            ? current == null ? DEFAULT_SCHEDULE_TIME : current.scheduleTime()
            : parseScheduleTime(scheduleTime);
        String savedScheduleMode = scheduleMode == null
            ? current == null ? "INCREMENTAL" : current.scheduleMode()
            : normalizeScheduleMode(scheduleMode);
        long actorId = authorization.currentUserId();
        jdbc.update("""
            INSERT INTO t_wecom_config(company_id, corp_id, secret_encrypted, agent_id,
                agent_secret_encrypted, oauth_enabled, js_sdk_enabled, message_enabled,
                schedule_enabled, schedule_time, schedule_mode, created_by, updated_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (company_id) DO UPDATE SET
                corp_id = EXCLUDED.corp_id,
                secret_encrypted = EXCLUDED.secret_encrypted,
                agent_id = EXCLUDED.agent_id,
                agent_secret_encrypted = EXCLUDED.agent_secret_encrypted,
                oauth_enabled = EXCLUDED.oauth_enabled,
                js_sdk_enabled = EXCLUDED.js_sdk_enabled,
                message_enabled = EXCLUDED.message_enabled,
                schedule_enabled = EXCLUDED.schedule_enabled,
                schedule_time = EXCLUDED.schedule_time,
                schedule_mode = EXCLUDED.schedule_mode,
                updated_by = EXCLUDED.updated_by,
                updated_at = now()
            """, companyId, normalizedCorpId, encrypted, savedAgentId, encryptedAgentSecret,
            savedOauthEnabled, savedJsSdkEnabled, savedMessageEnabled, savedScheduleEnabled,
            savedScheduleTime, savedScheduleMode, actorId, actorId);
        audit.success("integration.wecom.settings.update", "COMPANY", companyId,
            AuditService.RiskLevel.HIGH,
            Map.of("changedFields", List.of("corpId", "secretConfigured", "scheduleEnabled",
                "scheduleTime", "scheduleMode")), Map.of());
        return new SettingsDto(companyId, normalizedCorpId, true, latestJob(companyId), savedAgentId,
            encryptedAgentSecret != null, savedOauthEnabled, savedJsSdkEnabled, savedMessageEnabled,
            savedScheduleEnabled, formatScheduleTime(savedScheduleTime), savedScheduleMode);
    }

    @Transactional(rollbackFor = Exception.class)
    public JobDto start(long companyId, String syncMode) {
        authorization.requirePermission(PermissionCodes.INTEGRATION_WECOM_MANAGE);
        authorization.requireAllDataScope(PermissionCodes.ORG_DEPARTMENT_MANAGE);
        authorization.requireAllDataScope(PermissionCodes.ORG_USER_MANAGE);
        requireCompanyAccess(companyId);
        if (config(companyId) == null) {
            throw new BizException("WECOM_NOT_CONFIGURED", "请先保存企业微信连接配置");
        }
        String mode = "FULL".equalsIgnoreCase(syncMode) ? "FULL" : "INCREMENTAL";
        PrincipalHolder.Principal actor = PrincipalHolder.current().orElseThrow();
        Long id = jdbc.query("""
            INSERT INTO t_wecom_sync_job(company_id, initiated_by, sync_mode, message)
            VALUES (?, ?, ?, '任务已排队')
            ON CONFLICT (company_id) WHERE status IN ('PENDING', 'RUNNING') DO NOTHING
            RETURNING id
            """, statement -> {
                statement.setLong(1, companyId);
                statement.setLong(2, actor.userId());
                statement.setString(3, mode);
            }, result -> result.next() ? result.getLong(1) : null);
        if (id == null) return activeJob(companyId);

        audit.success("integration.wecom.sync.start", "WECOM_SYNC_JOB", id,
            AuditService.RiskLevel.HIGH, Map.of(), Map.of("companyId", companyId));
        long jobId = id;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit(jobId, actor);
            }
        });
        return job(jobId, false);
    }

    public JobDto job(long id) {
        authorization.requirePermission(PermissionCodes.INTEGRATION_WECOM_MANAGE);
        JobDto result = job(id, true);
        requireCompanyAccess(result.companyId());
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
            JobDto initial = job(jobId, false);
            Config config = config(initial.companyId());
            if (config == null) throw new WecomClient.WecomApiException("企业微信配置不存在");
            boolean full = "FULL".equals(initial.syncMode());
            stage(jobId, "CONNECTING", 5, "正在连接企业微信");
            WecomClient.Session session = client.connect(config.corpId(),
                cipher.decrypt(config.encryptedSecret(), initial.companyId()));

            stage(jobId, "DEPARTMENTS", 12, "正在同步部门树");
            List<WecomDepartment> departments = WecomMappingRules.orderDepartments(client.departments(session));
            Map<Long, Long> departmentMappings = syncDepartments(initial.companyId(), departments);
            stage(jobId, "DEPARTMENTS", 25, "部门同步完成");

            stage(jobId, "FETCHING_USERS", 30, "正在获取成员列表");
            List<WecomUser> externalUsers = client.users(session,
                departments.stream().map(WecomDepartment::id).toList());
            transactions.executeWithoutResult(status -> {
                lease.require();
                jdbc.update("UPDATE t_wecom_sync_job SET total_users = ?, updated_at = now() WHERE id = ?",
                    externalUsers.size(), jobId);
            });

            List<String> errors = new CopyOnWriteArrayList<>();
            SyncContext context = loadContext(initial.companyId(), externalUsers, full);
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
                        return syncUsers(initial.companyId(), batch, departmentMappings, batchContext);
                    });
                    created += counts[0];
                    updated += counts[1];
                } catch (RuntimeException exception) {
                    if (exception instanceof LeaseLostException) throw exception;
                    log.warn("WeCom member batch write failed: job={}, batch={}", jobId,
                        offset / USER_BATCH_SIZE + 1, exception);
                    SyncContext retryContext = loadContext(initial.companyId(), externalUsers, full);
                    for (WecomUser user : batch) {
                        try {
                            SyncContext memberContext = retryContext;
                            int[] counts = transactions.execute(status -> {
                                lease.require();
                                return syncUsers(initial.companyId(), List.of(user),
                                    departmentMappings, memberContext);
                            });
                            created += counts[0];
                            updated += counts[1];
                        } catch (RuntimeException memberException) {
                            if (memberException instanceof LeaseLostException) throw memberException;
                            failed++;
                            log.warn("WeCom member write failed: job={}, member={}", jobId,
                                WecomMappingRules.mask(user.userId()), memberException);
                            addError(errors, memberError(user, memberException));
                            retryContext = loadContext(initial.companyId(), externalUsers, full);
                        }
                    }
                    context = retryContext;
                }
                int processed = Math.min(offset + USER_BATCH_SIZE, pending.size());
                int percent = pending.isEmpty() ? 75
                    : 35 + (int) Math.floor(processed * 40.0 / pending.size());
                progress(jobId, percent, externalUsers.size(), processed, created, updated, failed,
                    "正在同步成员 " + processed + "/" + pending.size(), errors);
            }

            stage(jobId, "RELATIONS", 88, "正在同步部门负责人和直属上级");
            syncDepartmentLeaders(initial.companyId(), departments, errors);
            // 直属上级同步已搬到 WecomManagerSync；它只返回"发生了什么"，错误上限由本类统一执行。
            for (String problem : managerSync.sync(initial.companyId(), session, externalUsers,
                context.mappedIds, context.byId.values())) {
                addError(errors, problem);
            }
            int disabled = deactivateMissing(initial.companyId(), externalUsers);
            String summary = "通讯录同步完成"
                + (disabled > 0 ? "，已停用 " + disabled + " 名成员" : "");
            boolean clean = failed == 0 && errors.isEmpty();
            finish(jobId, clean ? "SUCCESS" : "PARTIAL",
                clean ? summary : summary + "，部分数据未能同步", errors);
            terminal = true;
        } catch (LeaseLostException exception) {
            log.info("WeCom sync {} stopped because its lease was lost", jobId);
        } catch (RuntimeException exception) {
            terminal = fail(jobId, fatalError(exception), List.of(fatalError(exception)));
        } finally {
            lease.exit(jobId, token);
            if (terminal) auditResult(jobId, actor);
        }
    }

    private SyncContext loadContext(long companyId, List<WecomUser> externalUsers, boolean full) {
        SyncContext context = new SyncContext();
        context.roleId = jdbc.queryForObject(
            "SELECT id FROM t_role WHERE code = 'employee' AND enabled = true", Long.class);
        if (context.roleId == 0) throw new SyncUserException("内置 employee 角色不存在");
        List<LocalUserState> localUsers = jdbc.query("""
            SELECT u.id, u.username, u.employee_no, u.phone, u.email, u.display_name,
                   u.position, u.gender, u.status, m.wecom_user_id,
                   m.wecom_status, m.directory_present, m.login_enabled_override,
                   COALESCE(department_mapping.wecom_department_id, 0), u.manager_id
            FROM t_user u
            JOIN t_department d ON d.id = u.dept_id
            LEFT JOIN t_wecom_user_mapping m ON m.user_id = u.id AND m.company_id = ?
            LEFT JOIN t_wecom_department_mapping department_mapping
              ON department_mapping.department_id = u.dept_id
             AND department_mapping.company_id = ?
            WHERE d.company_id = ?
            """, (rs, rowNum) -> new LocalUserState(rs.getLong(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                (Integer) rs.getObject(11), (Boolean) rs.getObject(12),
                (Boolean) rs.getObject(13), rs.getLong(14), (Long) rs.getObject(15)),
            companyId, companyId, companyId);
        for (LocalUserState state : localUsers) {
            context.usernameOwners.put(state.username(), state.id());
            if (state.employeeNo() != null) context.employeeNoOwners.put(state.employeeNo(), state.id());
            if (state.wecomUserId() != null) context.mappedIds.put(state.wecomUserId(), state.id());
            if (state.phone() != null) context.byPhone
                .computeIfAbsent(state.phone(), ignored -> new ArrayList<>()).add(state.id());
            if (state.email() != null) context.byEmail
                .computeIfAbsent(state.email().toLowerCase(), ignored -> new ArrayList<>()).add(state.id());
            context.byId.put(state.id(), state);
        }
        for (WecomUser user : externalUsers) {
            if (full) {
                context.pending.add(user);
                continue;
            }
            LocalUserState local = context.byId.get(context.mappedIds.get(user.userId()));
            if (local == null || WecomMappingRules.changed(user, local)) context.pending.add(user);
        }
        return context;
    }

    private int[] syncUsers(long companyId, List<WecomUser> batch,
                            Map<Long, Long> departmentMappings, SyncContext context) {
        List<Object[]> inserts = new ArrayList<>();
        List<String> insertedUsernames = new ArrayList<>();
        List<Integer> insertedIndexes = new ArrayList<>();
        List<Object[]> updates = new ArrayList<>();
        int created = 0;
        int updated = 0;
        for (int index = 0; index < batch.size(); index++) {
            WecomUser user = batch.get(index);
            Long localId = context.mappedIds.get(user.userId());
            if (localId == null) localId = matchLocal(context, user.phone(), user.email());
            if (localId == null) {
                Long departmentId = departmentMappings.get(WecomMappingRules.primaryDepartment(user));
                if (departmentId == null) throw new SyncUserException("主部门未绑定");
                String employeeNo = wecomEmployeeNo(context, user.userId(), null);
                String username = wecomUsername(context, employeeNo, null);
                String password = passwords.encode(UUID.randomUUID().toString());
                inserts.add(new Object[]{departmentId, employeeNo, username, password,
                    displayName(user), email(user), phone(user), position(user), gender(user.gender()),
                    WecomMappingRules.effectiveUserStatus(user.status(), null)});
                insertedUsernames.add(username);
                insertedIndexes.add(index);
                created++;
            } else {
                Long departmentId = departmentMappings.get(WecomMappingRules.primaryDepartment(user));
                if (departmentId == null) throw new SyncUserException("主部门未绑定");
                String employeeNo = wecomEmployeeNo(context, user.userId(), localId);
                String username = wecomUsername(context, employeeNo, localId);
                updates.add(new Object[]{username, employeeNo, departmentId, displayName(user),
                    phone(user), email(user),
                    position(user), gender(user.gender()), localId});
                updated++;
            }
        }
        if (!inserts.isEmpty()) {
            jdbc.batchUpdate("""
                INSERT INTO t_user(dept_id, employee_no, username, password_hash, display_name,
                                   email, phone, position, gender, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, inserts);
            String placeholders = String.join(",",
                Collections.nCopies(insertedUsernames.size(), "?"));
            Map<String, Long> idByUsername = new HashMap<>();
            jdbc.query("SELECT id, username FROM t_user WHERE username IN (" + placeholders + ")",
                rs -> {
                    idByUsername.put(rs.getString("username"), rs.getLong("id"));
                },
                insertedUsernames.toArray());
            List<Object[]> roleInserts = new ArrayList<>();
            for (int i = 0; i < insertedUsernames.size(); i++) {
                Long insertedId = idByUsername.get(insertedUsernames.get(i));
                if (insertedId == null) throw new SyncUserException("新增成员落库失败");
                context.mappedIds.put(batch.get(insertedIndexes.get(i)).userId(), insertedId);
                roleInserts.add(new Object[]{insertedId, context.roleId});
            }
            jdbc.batchUpdate("INSERT INTO t_user_role(user_id, role_id) VALUES (?, ?)",
                roleInserts);
        }
        List<Object[]> mappings = new ArrayList<>();
        for (WecomUser user : batch) {
            Long id = context.mappedIds.get(user.userId());
            if (id == null) throw new SyncUserException("成员映射缺失");
            mappings.add(new Object[]{companyId, user.userId(), id, user.status()});
        }
        jdbc.batchUpdate("""
            INSERT INTO t_wecom_user_mapping(
                company_id, wecom_user_id, user_id, wecom_status, directory_present)
            VALUES (?, ?, ?, ?, true)
            ON CONFLICT (company_id, wecom_user_id) DO UPDATE
            SET user_id = EXCLUDED.user_id,
                wecom_status = EXCLUDED.wecom_status,
                directory_present = true,
                login_enabled_override = CASE
                    WHEN EXCLUDED.wecom_status IN (1, 4)
                    THEN t_wecom_user_mapping.login_enabled_override
                    ELSE NULL
                END
            """, mappings);
        if (!updates.isEmpty()) {
            jdbc.batchUpdate("""
                UPDATE t_user SET username = ?, employee_no = ?, dept_id = ?, display_name = ?,
                    phone = COALESCE(NULLIF(?, ''), phone),
                    email = COALESCE(NULLIF(?, ''), email),
                    position = ?, gender = ?
                WHERE id = ?
                """, updates);
        }
        applyLoginStatus(companyId, batch.stream()
            .map(user -> context.mappedIds.get(user.userId()))
            .filter(Objects::nonNull).distinct().toList());
        return new int[]{created, updated};
    }

    private void applyLoginStatus(long companyId, List<Long> userIds) {
        if (userIds.isEmpty()) return;
        String placeholders = String.join(",", Collections.nCopies(userIds.size(), "?"));
        List<Object> arguments = new ArrayList<>();
        arguments.add(companyId);
        arguments.addAll(userIds);
        jdbc.update("""
            WITH account_status AS (
                UPDATE t_user user_row
                SET status = CASE
                    WHEN mapping.directory_present AND mapping.wecom_status = 1
                         AND mapping.login_enabled_override IS DISTINCT FROM false THEN 'ACTIVE'
                    WHEN mapping.directory_present AND mapping.wecom_status = 4
                         AND mapping.login_enabled_override IS true THEN 'ACTIVE'
                    ELSE 'DISABLED'
                END
                FROM t_wecom_user_mapping mapping
                WHERE mapping.company_id = ?
                  AND mapping.user_id = user_row.id
                  AND user_row.id IN (%s)
                RETURNING user_row.id, user_row.status
            )
            UPDATE t_auth_session session
            SET revoked_at = now()
            WHERE session.revoked_at IS NULL
              AND session.user_id IN (
                  SELECT id FROM account_status WHERE status = 'DISABLED'
              )
            """.formatted(placeholders), arguments.toArray());
    }

    private static Long matchLocal(SyncContext context, String phone, String email) {
        List<Long> phoneMatches = blankToNull(phone) == null ? List.of()
            : context.byPhone.getOrDefault(trim(phone), List.of());
        List<Long> emailMatches = blankToNull(email) == null ? List.of()
            : context.byEmail.getOrDefault(trim(email).toLowerCase(), List.of());
        return WecomMappingRules.resolveMatch(phoneMatches, emailMatches);
    }

    private static String wecomEmployeeNo(SyncContext context, String userId, Long localId) {
        String value = trim(userId);
        if (value.isBlank() || value.length() > 64 || value.matches(".*[\\s\\p{Cntrl}].*")) {
            throw new SyncUserException("企业微信账号不能作为工号");
        }
        Long owner = context.employeeNoOwners.get(value);
        if (owner != null && !Objects.equals(owner, localId)) {
            throw new SyncUserException("企业微信账号已被其他本地用户占用");
        }
        if (localId != null) context.employeeNoOwners.values().removeIf(id -> Objects.equals(id, localId));
        context.employeeNoOwners.put(value, localId == null ? Long.MIN_VALUE : localId);
        return value;
    }

    private static String wecomUsername(SyncContext context, String employeeNo, Long localId) {
        Long owner = context.usernameOwners.get(employeeNo);
        if (owner != null && !Objects.equals(owner, localId)) {
            throw new SyncUserException("企业微信账号已被其他本地用户占用");
        }
        if (localId != null) {
            context.usernameOwners.values().removeIf(id -> Objects.equals(id, localId));
        }
        context.usernameOwners.put(employeeNo, localId == null ? Long.MIN_VALUE : localId);
        return employeeNo;
    }

    private void syncDepartmentLeaders(long companyId, List<WecomDepartment> departments,
                                       List<String> errors) {
        for (WecomDepartment department : departments) {
            try {
                List<String> leaders = new ArrayList<>(new LinkedHashSet<>(department.leaderUserIds()));
                Long leaderId = leaders.isEmpty() ? null : mappedActiveUser(companyId, leaders.get(0));
                transactions.executeWithoutResult(status -> {
                    lease.require();
                    Long departmentId = mappedDepartment(companyId, department.id());
                    if (departmentId == null) return;
                    jdbc.update("DELETE FROM t_department_leader WHERE department_id = ?",
                        departmentId);
                    if (leaderId != null) jdbc.update("""
                        INSERT INTO t_department_leader(department_id, user_id)
                        VALUES (?, ?) ON CONFLICT DO NOTHING
                        """, departmentId, leaderId);
                    jdbc.update("UPDATE t_department SET leader_id = ? WHERE id = ?",
                        leaderId, departmentId);
                });
                if (leaders.size() > 1) {
                    addError(errors, "部门 " + department.id() + " 配置了多位负责人，仅同步第一位");
                }
                if (!leaders.isEmpty() && leaderId == null) {
                    addError(errors, "部门 " + department.id() + " 的负责人未同步或已停用，已清空");
                }
            } catch (RuntimeException exception) {
                if (exception instanceof LeaseLostException) throw exception;
                log.warn("WeCom department leader sync failed: company={}, department={}", companyId,
                    department.id(), exception);
                addError(errors, "部门 " + department.id() + " 的负责人同步失败");
            }
        }
    }

    private int deactivateMissing(long companyId, List<WecomUser> externalUsers) {
        Set<String> externalIds = externalUsers.stream()
            .map(WecomUser::userId).collect(Collectors.toSet());
        Integer disabled = transactions.execute(status -> {
            lease.require();
            List<Object[]> targets = jdbc.query("""
                SELECT wecom_user_id, user_id
                FROM t_wecom_user_mapping
                WHERE company_id = ? AND directory_present
                FOR UPDATE
                """, (rs, rowNum) -> new Object[]{rs.getString(1), rs.getLong(2)}, companyId)
                .stream()
                .filter(row -> !externalIds.contains((String) row[0]))
                .toList();
            if (targets.isEmpty()) return 0;
            List<Long> userIds = targets.stream().map(row -> (Long) row[1]).distinct().toList();
            String placeholders = String.join(",", Collections.nCopies(userIds.size(), "?"));
            List<Object> arguments = new ArrayList<>();
            arguments.add(companyId);
            arguments.addAll(userIds);
            jdbc.update("""
                WITH missing AS (
                    UPDATE t_wecom_user_mapping
                    SET directory_present = false, login_enabled_override = NULL
                    WHERE company_id = ? AND user_id IN (%s)
                    RETURNING user_id
                ), disabled_users AS (
                    UPDATE t_user user_row
                    SET status = 'DISABLED'
                    WHERE user_row.id IN (SELECT user_id FROM missing)
                    RETURNING user_row.id
                )
                UPDATE t_auth_session session
                SET revoked_at = now()
                WHERE session.revoked_at IS NULL
                  AND session.user_id IN (SELECT id FROM disabled_users)
                """.formatted(placeholders), arguments.toArray());
            return userIds.size();
        });
        return disabled == null ? 0 : disabled;
    }

    private Map<Long, Long> syncDepartments(long companyId, List<WecomDepartment> departments) {
        Map<Long, Long> mappings = new HashMap<>();
        for (WecomDepartment department : departments) {
            Long localId = transactions.execute(status -> {
                lease.require();
                Long parentId = department.parentId() == 0 ? null : mappings.get(department.parentId());
                if (department.parentId() != 0 && parentId == null) {
                    throw new WecomClient.WecomApiException("企业微信部门树缺少父部门");
                }
                Long existing = mappedDepartment(companyId, department.id());
                String parentPath = parentId == null ? null : jdbc.queryForObject(
                    "SELECT path::text FROM t_department WHERE id = ?", String.class, parentId);
                String path = (parentPath == null ? "" : parentPath + ".")
                    + "wc" + companyId + "d" + department.id();
                String name = department.name() == null ? "" : department.name().trim();
                if (name.isBlank()) throw new WecomClient.WecomApiException("企业微信部门名称为空");
                if (existing == null) {
                    Long created = jdbc.queryForObject("""
                        INSERT INTO t_department(company_id, parent_id, path, name, sort_order)
                        VALUES (?, ?, CAST(? AS ltree), ?, ?) RETURNING id
                        """, Long.class, companyId, parentId, path, name, department.order());
                    jdbc.update("""
                        INSERT INTO t_wecom_department_mapping(
                            company_id, wecom_department_id, department_id)
                        VALUES (?, ?, ?)
                        """, companyId, department.id(), created);
                    return created;
                }
                DepartmentState current = jdbc.query("""
                    SELECT company_id, path::text FROM t_department WHERE id = ?
                    """, rs -> rs.next() ? new DepartmentState(rs.getLong(1), rs.getString(2)) : null,
                    existing);
                if (current == null || current.companyId() != companyId) {
                    throw new WecomClient.WecomApiException("企业微信部门绑定无效");
                }
                if (!Objects.equals(current.path(), path)) {
                    jdbc.update("""
                        UPDATE t_department
                        SET path = CAST(? AS ltree) || subpath(path, nlevel(CAST(? AS ltree)))
                        WHERE path <@ CAST(? AS ltree) AND path <> CAST(? AS ltree)
                        """, path, current.path(), current.path(), current.path());
                }
                jdbc.update("""
                    UPDATE t_department
                    SET parent_id = ?, path = CAST(? AS ltree), name = ?, sort_order = ?
                    WHERE id = ?
                    """, parentId, path, name, department.order(), existing);
                return existing;
            });
            mappings.put(department.id(), localId);
        }
        return mappings;
    }

    private void stage(long jobId, String phase, int percent, String message) {
        transactions.executeWithoutResult(status -> {
            lease.require();
            jdbc.update("""
            UPDATE t_wecom_sync_job SET status = 'RUNNING', phase = ?, percent = ?, message = ?,
                started_at = COALESCE(started_at, now()), updated_at = now()
            WHERE id = ? AND status IN ('PENDING', 'RUNNING')
            """, phase, percent, message, jobId);
        });
    }

    private void progress(long jobId, int percent, int total, int processed, int created,
                          int updated, int failed, String message, List<String> errors) {
        transactions.executeWithoutResult(status -> {
            lease.require();
            jdbc.update("""
            UPDATE t_wecom_sync_job SET phase = 'USERS', percent = ?, total_users = ?,
                processed_users = ?, created_users = ?, updated_users = ?, failed_users = ?,
                message = ?, error_summary = CAST(? AS jsonb), updated_at = now()
            WHERE id = ?
            """, percent, total, processed, created, updated, failed, message, errorJson(errors), jobId);
        });
    }

    private void finish(long jobId, String status, String message, List<String> errors) {
        transactions.executeWithoutResult(transaction -> {
            lease.require();
            jdbc.update("""
            UPDATE t_wecom_sync_job SET status = ?, phase = 'COMPLETED', percent = 100,
                message = ?, error_summary = CAST(? AS jsonb), finished_at = now(), updated_at = now(),
                claim_token = NULL, lease_expires_at = NULL
            WHERE id = ?
            """, status, message, errorJson(errors), jobId);
        });
    }

    private boolean fail(long jobId, String message, List<String> errors) {
        UUID token = lease.currentToken();
        if (token == null) return false;
        return jdbc.update("""
            UPDATE t_wecom_sync_job SET status = 'FAILED', phase = 'COMPLETED',
                message = ?, error_summary = CAST(? AS jsonb), finished_at = now(), updated_at = now(),
                claim_token = NULL, lease_expires_at = NULL
            WHERE id = ? AND claim_token = ? AND status = 'RUNNING' AND lease_expires_at > now()
            """, message, errorJson(errors), jobId, token) == 1;
    }

    private void auditResult(long jobId, PrincipalHolder.Principal actor) {
        try {
            JobDto result = job(jobId, false);
            audit.successAs(actor, "integration.wecom.sync.finish", "WECOM_SYNC_JOB", jobId,
                AuditService.RiskLevel.HIGH, Map.of("status", result.status()),
                Map.of("companyId", result.companyId(), "totalUsers", result.totalUsers(),
                    "createdUsers", result.createdUsers(), "updatedUsers", result.updatedUsers(),
                    "failedUsers", result.failedUsers()));
        } catch (RuntimeException ignored) {
            // The persisted job result remains authoritative if audit storage is unavailable.
        }
    }

    private Config config(long companyId) {
        return jdbc.query("""
            SELECT corp_id, secret_encrypted, agent_id, agent_secret_encrypted,
                   oauth_enabled, js_sdk_enabled, message_enabled,
                   schedule_enabled, schedule_time, schedule_mode
            FROM t_wecom_config WHERE company_id = ?
            """, rs -> rs.next() ? new Config(rs.getString(1), rs.getString(2),
                (Integer) rs.getObject(3), rs.getString(4), rs.getBoolean(5),
                rs.getBoolean(6), rs.getBoolean(7), rs.getBoolean(8),
                rs.getObject(9, LocalTime.class), rs.getString(10)) : null, companyId);
    }

    private Long mappedDepartment(long companyId, long externalId) {
        return jdbc.query("""
            SELECT department_id FROM t_wecom_department_mapping
            WHERE company_id = ? AND wecom_department_id = ?
            """, rs -> rs.next() ? rs.getLong(1) : null, companyId, externalId);
    }

    private Long mappedActiveUser(long companyId, String externalId) {
        return jdbc.query("""
            SELECT user_row.id
            FROM t_wecom_user_mapping mapping
            JOIN t_user user_row ON user_row.id = mapping.user_id
            JOIN t_department department ON department.id = user_row.dept_id
            WHERE mapping.company_id = ? AND mapping.wecom_user_id = ?
              AND department.company_id = ? AND user_row.status = 'ACTIVE'
            """, rs -> rs.next() ? rs.getLong(1) : null, companyId, externalId, companyId);
    }

    private JobDto latestJob(long companyId) {
        return jdbc.query("""
            SELECT * FROM t_wecom_sync_job WHERE company_id = ?
            ORDER BY created_at DESC, id DESC LIMIT 1
            """, rs -> rs.next() ? mapJob(rs) : null, companyId);
    }

    private JobDto activeJob(long companyId) {
        JobDto result = jdbc.query("""
            SELECT * FROM t_wecom_sync_job
            WHERE company_id = ? AND status IN ('PENDING', 'RUNNING')
            ORDER BY created_at DESC, id DESC LIMIT 1
            """, rs -> rs.next() ? mapJob(rs) : null, companyId);
        if (result == null) throw new BizException("WECOM_JOB_CONFLICT", "同步任务状态已变化，请重试");
        return result;
    }

    private JobDto job(long id, boolean failIfMissing) {
        JobDto result = jdbc.query("SELECT * FROM t_wecom_sync_job WHERE id = ?",
            rs -> rs.next() ? mapJob(rs) : null, id);
        if (result == null && failIfMissing) {
            throw new com.antflow.authz.HiddenResourceException("sync job not found");
        }
        return result;
    }

    private JobDto mapJob(ResultSet rs) throws SQLException {
        String syncMode = "FULL";
        try {
            syncMode = rs.getString("sync_mode");
        } catch (SQLException ignored) {
            // Older database rows without the column default to FULL.
        }
        return new JobDto(rs.getLong("id"), rs.getLong("company_id"), rs.getString("status"),
            rs.getString("phase"), rs.getInt("percent"), rs.getInt("total_users"),
            rs.getInt("processed_users"), rs.getInt("created_users"),
            rs.getInt("updated_users"), rs.getInt("failed_users"), rs.getString("message"),
            errorList(rs.getString("error_summary")), rs.getObject("started_at", OffsetDateTime.class),
            rs.getObject("finished_at", OffsetDateTime.class), syncMode);
    }

    private void requireCompany(long companyId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM t_company WHERE id = ?",
            Long.class, companyId);
        if (count == null || count == 0) throw new BizException("COMPANY_NOT_FOUND", "企业不存在");
    }

    private void requireCompanyAccess(long companyId) {
        requireCompany(companyId);
        PrincipalHolder.Principal principal = PrincipalHolder.current().orElseThrow();
        if (principal.isAdmin()) return;
        Long count = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM t_user user_row
            JOIN t_department department ON department.id = user_row.dept_id
            WHERE user_row.id = ? AND department.company_id = ?
            """, Long.class, principal.userId(), companyId);
        if (count == null || count == 0) {
            throw new com.antflow.authz.HiddenResourceException("company not found");
        }
    }

    private String errorJson(List<String> errors) {
        try {
            return json.writeValueAsString(errors.stream().limit(MAX_ERROR_SUMMARIES).toList());
        } catch (Exception exception) {
            return "[]";
        }
    }

    private List<String> errorList(String value) {
        try {
            return json.readValue(value == null ? "[]" : value, new TypeReference<>() { });
        } catch (Exception exception) {
            return List.of();
        }
    }

    private static void addError(List<String> errors, String value) {
        if (errors.size() < MAX_ERROR_SUMMARIES) errors.add(value.length() > 160
            ? value.substring(0, 160) : value);
    }

    private static String fatalError(RuntimeException exception) {
        if (exception instanceof WecomClient.WecomApiException
            || exception instanceof SyncUserException) return exception.getMessage();
        return "同步任务执行失败，请检查配置后重试";
    }

    private static String memberError(WecomUser user, RuntimeException exception) {
        return "成员 " + displayName(user) + "（工号 " + user.userId() + "）同步失败："
            + userError(exception);
    }

    static String userError(RuntimeException exception) {
        return exception instanceof SyncUserException ? exception.getMessage()
            : exception instanceof WecomClient.WecomApiException ? exception.getMessage()
            : exception instanceof DataIntegrityViolationException ? integrityError(exception)
            : exception instanceof DataAccessException ? "本地成员数据写入失败，请检查字段长度或数据库约束"
            : "本地成员数据写入失败";
    }

    private static String integrityError(RuntimeException exception) {
        String detail = String.valueOf(NestedExceptionUtils.getMostSpecificCause(exception).getMessage());
        if (detail.contains("t_user_username_key")) return "企微工号与本地账号冲突";
        if (detail.contains("uk_user_employee_no")) return "企微工号与本地工号冲突";
        if (detail.contains("t_user_dept_id_fkey")) return "所属部门映射无效";
        if (detail.contains("value too long")) return "成员字段长度超过本地限制";
        return "本地成员数据违反唯一性或关联约束";
    }

    // 字段清洗的实现已搬到 WecomMappingRules（那里是这些口径的唯一真源）。
    // 这里保留一行委托，是为了不让原类近 60 处调用点同时改名——它们全是 private，不构成对外契约。
    // 下一刀收敛调用点时直接删掉这几个方法即可，不要在这里再加逻辑。
    private static String displayName(WecomUser user) { return WecomMappingRules.displayName(user); }
    private static String phone(WecomUser user) { return WecomMappingRules.phone(user); }
    private static String email(WecomUser user) { return WecomMappingRules.email(user); }
    private static String position(WecomUser user) { return WecomMappingRules.position(user); }
    private static String bounded(String value, int limit, String label) {
        return WecomMappingRules.bounded(value, limit, label);
    }
    private static String gender(String value) { return WecomMappingRules.gender(value); }
    private static String blankToNull(String value) { return WecomMappingRules.blankToNull(value); }
    private static String trim(String value) { return WecomMappingRules.trim(value); }

    static LocalTime parseScheduleTime(String value) {
        if (value == null || !value.matches("(?:[01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d")) {
            throw new BizException("WECOM_SCHEDULE_TIME_INVALID", "自动同步时间格式必须为 HH:mm:ss");
        }
        return LocalTime.parse(value, DateTimeFormatter.ISO_LOCAL_TIME);
    }

    static String normalizeScheduleMode(String value) {
        if (!"INCREMENTAL".equals(value) && !"FULL".equals(value)) {
            throw new BizException("WECOM_SCHEDULE_MODE_INVALID", "自动同步模式无效");
        }
        return value;
    }

    private static String formatScheduleTime(LocalTime value) {
        return value.format(SCHEDULE_TIME_FORMAT);
    }

    private record Config(String corpId, String encryptedSecret, Integer agentId,
                          String encryptedAgentSecret, boolean oauthEnabled,
                          boolean jsSdkEnabled, boolean messageEnabled,
                          boolean scheduleEnabled, LocalTime scheduleTime,
                          String scheduleMode) { }
    private record DepartmentState(long companyId, String path) { }
    private static final class SyncContext {
        final Map<String, Long> mappedIds = new HashMap<>();
        final Map<String, List<Long>> byPhone = new HashMap<>();
        final Map<String, List<Long>> byEmail = new HashMap<>();
        final Map<String, Long> usernameOwners = new HashMap<>();
        final Map<String, Long> employeeNoOwners = new HashMap<>();
        final Map<Long, LocalUserState> byId = new HashMap<>();
        long roleId;
        final List<WecomUser> pending = new ArrayList<>();
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
