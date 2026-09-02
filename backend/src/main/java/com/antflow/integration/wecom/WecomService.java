package com.antflow.integration.wecom;

import com.antflow.audit.AuditService;
import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.engine.BizException;
import com.antflow.integration.wecom.WecomClient.WecomDepartment;
import com.antflow.integration.wecom.WecomClient.WecomUser;
import com.antflow.integration.wecom.WecomClient.WecomUserRef;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WecomService {
    private static final int MAX_ERROR_SUMMARIES = 10;
    private static final int USER_BATCH_SIZE = 200;
    private static final int MANAGER_POOL_SIZE = 12;
    private static final long MANAGER_BATCH_INTERVAL_MS = 350;
    private final JdbcTemplate jdbc;
    private final WecomSecretCipher cipher;
    private final WecomClient client;
    private final AuthorizationService authorization;
    private final AuditService audit;
    private final ObjectMapper json;
    private final PasswordEncoder passwords;
    private final TransactionTemplate transactions;
    private final Executor executor;

    public WecomService(JdbcTemplate jdbc, WecomSecretCipher cipher, WecomClient client,
                        AuthorizationService authorization, AuditService audit, ObjectMapper json,
                        PasswordEncoder passwords,
                        TransactionTemplate transactions,
                        @Qualifier("wecomSyncExecutor") Executor executor) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.client = client;
        this.authorization = authorization;
        this.audit = audit;
        this.json = json;
        this.passwords = passwords;
        this.transactions = transactions;
        this.executor = executor;
    }

    public SettingsDto settings(long companyId) {
        authorization.requirePermission(PermissionCodes.ORG_COMPANY_MANAGE);
        requireCompany(companyId);
        Config config = config(companyId);
        return new SettingsDto(companyId, config == null ? "" : config.corpId(),
            config != null && !config.encryptedSecret().isBlank(), latestJob(companyId),
            config == null ? null : config.agentId(),
            config != null && config.encryptedAgentSecret() != null,
            config != null && config.oauthEnabled(), config != null && config.jsSdkEnabled(),
            config != null && config.messageEnabled());
    }

    @Transactional(rollbackFor = Exception.class)
    public SettingsDto saveSettings(long companyId, String corpId, String secret) {
        Config current = config(companyId);
        return saveSettings(companyId, corpId, secret,
            current == null ? null : current.agentId(), null,
            current != null && current.oauthEnabled(), current != null && current.jsSdkEnabled(),
            current != null && current.messageEnabled());
    }

    @Transactional(rollbackFor = Exception.class)
    public SettingsDto saveSettings(long companyId, String corpId, String secret, Integer agentId,
                                    String agentSecret, boolean oauthEnabled,
                                    boolean jsSdkEnabled, boolean messageEnabled) {
        authorization.requirePermission(PermissionCodes.ORG_COMPANY_MANAGE);
        requireCompany(companyId);
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
        boolean appEnabled = oauthEnabled || jsSdkEnabled || messageEnabled;
        if (appEnabled && (agentId == null || agentId <= 0 || encryptedAgentSecret == null)) {
            throw new BizException("WECOM_APP_REQUIRED", "启用免登、JS-SDK 或应用消息前请配置 AgentId 和应用 Secret");
        }
        if (oauthEnabled) {
            Integer otherOauth = jdbc.queryForObject("""
                SELECT count(*) FROM t_wecom_config WHERE oauth_enabled AND company_id <> ?
                """, Integer.class, companyId);
            if (otherOauth != null && otherOauth > 0) {
                throw new BizException("WECOM_OAUTH_CONFLICT", "当前只允许启用一个企业微信 Corp 免登入口");
            }
        }
        long actorId = authorization.currentUserId();
        jdbc.update("""
            INSERT INTO t_wecom_config(company_id, corp_id, secret_encrypted, agent_id,
                agent_secret_encrypted, oauth_enabled, js_sdk_enabled, message_enabled,
                created_by, updated_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (company_id) DO UPDATE SET
                corp_id = EXCLUDED.corp_id,
                secret_encrypted = EXCLUDED.secret_encrypted,
                agent_id = EXCLUDED.agent_id,
                agent_secret_encrypted = EXCLUDED.agent_secret_encrypted,
                oauth_enabled = EXCLUDED.oauth_enabled,
                js_sdk_enabled = EXCLUDED.js_sdk_enabled,
                message_enabled = EXCLUDED.message_enabled,
                updated_by = EXCLUDED.updated_by,
                updated_at = now()
            """, companyId, normalizedCorpId, encrypted, agentId, encryptedAgentSecret,
            oauthEnabled, jsSdkEnabled, messageEnabled, actorId, actorId);
        audit.success("integration.wecom.settings.update", "COMPANY", companyId,
            AuditService.RiskLevel.HIGH,
            Map.of("changedFields", List.of("corpId", "secretConfigured")), Map.of());
        return new SettingsDto(companyId, normalizedCorpId, true, latestJob(companyId), agentId,
            encryptedAgentSecret != null, oauthEnabled, jsSdkEnabled, messageEnabled);
    }

    @Transactional(rollbackFor = Exception.class)
    public JobDto start(long companyId, String syncMode) {
        authorization.requirePermission(PermissionCodes.ORG_COMPANY_MANAGE);
        authorization.requireAllDataScope(PermissionCodes.ORG_DEPARTMENT_WRITE);
        authorization.requireAllDataScope(PermissionCodes.ORG_USER_WRITE);
        requireCompany(companyId);
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
        authorization.requirePermission(PermissionCodes.ORG_COMPANY_MANAGE);
        return job(id, true);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedJobs() {
        List<Long> ids = jdbc.queryForList("""
            UPDATE t_wecom_sync_job
            SET status = 'FAILED', phase = 'COMPLETED', message = '服务已重启，请重新同步',
                error_summary = '["服务重启中断了同步任务"]'::jsonb,
                finished_at = now(), updated_at = now()
            WHERE status IN ('PENDING', 'RUNNING')
            RETURNING id
            """, Long.class);
        ids.forEach(id -> {
            try {
                audit.success("integration.wecom.sync.recovered", "WECOM_SYNC_JOB", id,
                    AuditService.RiskLevel.HIGH, Map.of("status", "FAILED"),
                    Map.of("reason", "application_restart"));
            } catch (RuntimeException ignored) {
                // Recovery must release the active-job constraint even if audit storage is down.
            }
        });
    }

    private void submit(long jobId, PrincipalHolder.Principal actor) {
        try {
            executor.execute(() -> run(jobId, actor));
        } catch (RejectedExecutionException exception) {
            fail(jobId, "同步队列已满，请稍后重试", List.of("同步队列已满"));
            auditResult(jobId, actor);
        }
    }

    private void run(long jobId, PrincipalHolder.Principal actor) {
        try {
            JobDto initial = job(jobId, false);
            Config config = config(initial.companyId());
            if (config == null) throw new WecomClient.WecomApiException("企业微信配置不存在");
            boolean full = "FULL".equals(initial.syncMode());
            stage(jobId, "CONNECTING", 5, "正在连接企业微信");
            WecomClient.Session session = client.connect(config.corpId(),
                cipher.decrypt(config.encryptedSecret(), initial.companyId()));

            stage(jobId, "DEPARTMENTS", 12, "正在同步部门树");
            List<WecomDepartment> departments = orderDepartments(client.departments(session));
            Map<Long, Long> departmentMappings = syncDepartments(initial.companyId(), departments);
            stage(jobId, "DEPARTMENTS", 25, "部门同步完成");

            stage(jobId, "FETCHING_USERS", 30, "正在获取成员列表");
            List<WecomUser> externalUsers = client.users(session,
                departments.stream().map(WecomDepartment::id).toList());
            jdbc.update("UPDATE t_wecom_sync_job SET total_users = ?, updated_at = now() WHERE id = ?",
                externalUsers.size(), jobId);

            List<String> errors = new CopyOnWriteArrayList<>();
            SyncContext context = loadContext(initial.companyId(), externalUsers, full);
            int created = 0;
            int updated = 0;
            int failed = 0;
            for (int offset = 0; offset < context.pending.size(); offset += USER_BATCH_SIZE) {
                List<WecomUser> batch = context.pending.subList(offset,
                    Math.min(offset + USER_BATCH_SIZE, context.pending.size()));
                try {
                    int[] counts = transactions.execute(status ->
                        syncUsers(initial.companyId(), batch, departmentMappings, context));
                    created += counts[0];
                    updated += counts[1];
                } catch (RuntimeException exception) {
                    failed += batch.size();
                    addError(errors, "成员批次 " + (offset / USER_BATCH_SIZE + 1) + " 同步失败："
                        + userError(exception));
                }
                int processed = Math.min(offset + USER_BATCH_SIZE, context.pending.size());
                int percent = context.pending.isEmpty() ? 75
                    : 35 + (int) Math.floor(processed * 40.0 / context.pending.size());
                progress(jobId, percent, externalUsers.size(), processed, created, updated, failed,
                    "正在同步成员 " + processed + "/" + context.pending.size(), errors);
            }

            stage(jobId, "RELATIONS", 88, "正在同步部门负责人和直属上级");
            syncDepartmentLeaders(initial.companyId(), departments, errors);
            syncManagers(initial.companyId(), session,
                full ? externalUsers : context.createdUsers, context, errors);
            int disabled = deactivateMissing(initial.companyId(), externalUsers);
            String summary = "通讯录同步完成"
                + (disabled > 0 ? "，已停用 " + disabled + " 名成员" : "");
            boolean clean = failed == 0 && errors.isEmpty();
            finish(jobId, clean ? "SUCCESS" : "PARTIAL",
                clean ? summary : summary + "，部分数据未能同步", errors);
        } catch (RuntimeException exception) {
            fail(jobId, fatalError(exception), List.of(fatalError(exception)));
        } finally {
            auditResult(jobId, actor);
        }
    }

    private SyncContext loadContext(long companyId, List<WecomUser> externalUsers, boolean full) {
        SyncContext context = new SyncContext();
        context.roleId = jdbc.queryForObject(
            "SELECT id FROM t_role WHERE code = 'user' AND enabled = true", Long.class);
        if (context.roleId == 0) throw new SyncUserException("内置 user 角色不存在");
        List<LocalUserState> localUsers = jdbc.query("""
            SELECT u.id, u.username, u.employee_no, u.phone, u.email, u.display_name,
                   u.position, u.gender, u.status, m.wecom_user_id
            FROM t_user u
            JOIN t_department d ON d.id = u.dept_id
            LEFT JOIN t_wecom_user_mapping m ON m.user_id = u.id AND m.company_id = ?
            WHERE d.company_id = ?
            """, (rs, rowNum) -> new LocalUserState(rs.getLong(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10)),
            companyId, companyId);
        for (LocalUserState state : localUsers) {
            context.usernames.add(state.username());
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
            if (local == null || changed(user, local)) context.pending.add(user);
        }
        return context;
    }

    private static boolean changed(WecomUser external, LocalUserState local) {
        return !Objects.equals(trim(local.displayName()), displayName(external))
            || !Objects.equals(local.employeeNo(), external.userId())
            || !Objects.equals(local.status(), external.status() == 1 ? "ACTIVE" : "DISABLED")
            || !Objects.equals(blankToNull(local.position()), blankToNull(external.position()))
            || !Objects.equals(blankToNull(local.phone()), blankToNull(external.phone()))
            || !Objects.equals(blankToNull(local.email()), blankToNull(external.email()))
            || !Objects.equals(gender(local.gender()), gender(external.gender()));
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
                Long departmentId = departmentMappings.get(primaryDepartment(user));
                if (departmentId == null) throw new SyncUserException("主部门未绑定");
                String username = deterministicUsername(companyId, user.userId());
                if (!context.usernames.add(username)) {
                    throw new SyncUserException("自动生成的账号已存在");
                }
                String employeeNo = wecomEmployeeNo(context, user.userId(), null);
                String password = passwords.encode(UUID.randomUUID() + ":" + UUID.randomUUID());
                inserts.add(new Object[]{departmentId, employeeNo, username, password,
                    displayName(user), blankToNull(user.email()), blankToNull(user.phone()),
                    blankToNull(user.position()), gender(user.gender()),
                    user.status() == 1 ? "ACTIVE" : "DISABLED"});
                insertedUsernames.add(username);
                insertedIndexes.add(index);
                created++;
            } else {
                Long departmentId = departmentMappings.get(primaryDepartment(user));
                if (departmentId == null) throw new SyncUserException("主部门未绑定");
                String employeeNo = wecomEmployeeNo(context, user.userId(), localId);
                updates.add(new Object[]{employeeNo, departmentId, displayName(user), trim(user.phone()),
                    trim(user.email()), blankToNull(user.position()), gender(user.gender()),
                    user.status() == 1 ? "ACTIVE" : "DISABLED", localId});
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
                    return null;
                }, insertedUsernames.toArray());
            List<Object[]> roleInserts = new ArrayList<>();
            for (int i = 0; i < insertedUsernames.size(); i++) {
                Long insertedId = idByUsername.get(insertedUsernames.get(i));
                if (insertedId == null) throw new SyncUserException("新增成员落库失败");
                context.mappedIds.put(batch.get(insertedIndexes.get(i)).userId(), insertedId);
                context.createdUsers.add(batch.get(insertedIndexes.get(i)));
                roleInserts.add(new Object[]{insertedId, context.roleId});
            }
            jdbc.batchUpdate("INSERT INTO t_user_role(user_id, role_id) VALUES (?, ?)",
                roleInserts);
        }
        List<Object[]> mappings = new ArrayList<>();
        for (WecomUser user : batch) {
            Long id = context.mappedIds.get(user.userId());
            if (id == null) throw new SyncUserException("成员映射缺失");
            mappings.add(new Object[]{companyId, user.userId(), id});
        }
        jdbc.batchUpdate("""
            INSERT INTO t_wecom_user_mapping(company_id, wecom_user_id, user_id)
            VALUES (?, ?, ?)
            ON CONFLICT (company_id, wecom_user_id) DO UPDATE SET user_id = EXCLUDED.user_id
            """, mappings);
        if (!updates.isEmpty()) {
            jdbc.batchUpdate("""
                UPDATE t_user SET employee_no = ?, dept_id = ?, display_name = ?,
                    phone = COALESCE(NULLIF(?, ''), phone),
                    email = COALESCE(NULLIF(?, ''), email),
                    position = ?, gender = ?, status = ?
                WHERE id = ?
                """, updates);
        }
        return new int[]{created, updated};
    }

    private static Long matchLocal(SyncContext context, String phone, String email) {
        List<Long> phoneMatches = blankToNull(phone) == null ? List.of()
            : context.byPhone.getOrDefault(trim(phone), List.of());
        List<Long> emailMatches = blankToNull(email) == null ? List.of()
            : context.byEmail.getOrDefault(trim(email).toLowerCase(), List.of());
        return resolveMatch(phoneMatches, emailMatches);
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

    private void syncDepartmentLeaders(long companyId, List<WecomDepartment> departments,
                                       List<String> errors) {
        for (WecomDepartment department : departments) {
            try {
                transactions.executeWithoutResult(status -> {
                    Long departmentId = mappedDepartment(companyId, department.id());
                    if (departmentId == null) return;
                    jdbc.update("DELETE FROM t_department_leader WHERE department_id = ?",
                        departmentId);
                    for (String leader : new LinkedHashSet<>(department.leaderUserIds())) {
                        Long userId = mappedUser(companyId, leader);
                        if (userId != null) jdbc.update("""
                            INSERT INTO t_department_leader(department_id, user_id)
                            VALUES (?, ?) ON CONFLICT DO NOTHING
                            """, departmentId, userId);
                    }
                    Long first = department.leaderUserIds().stream()
                        .map(leader -> mappedUser(companyId, leader))
                        .filter(Objects::nonNull).findFirst().orElse(null);
                    jdbc.update("UPDATE t_department SET leader_id = ? WHERE id = ?",
                        first, departmentId);
                });
            } catch (RuntimeException exception) {
                addError(errors, "部门 " + department.id() + " 的负责人同步失败");
            }
        }
    }

    private void syncManagers(long companyId, WecomClient.Session session, List<WecomUser> targets,
                              SyncContext context, List<String> errors) {
        if (targets.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(MANAGER_POOL_SIZE);
        List<Object[]> updates = Collections.synchronizedList(new ArrayList<>());
        try {
            for (int offset = 0; offset < targets.size(); offset += MANAGER_POOL_SIZE) {
                List<WecomUser> chunk = targets.subList(offset,
                    Math.min(offset + MANAGER_POOL_SIZE, targets.size()));
                List<Future<?>> futures = new ArrayList<>();
                for (WecomUser user : chunk) {
                    futures.add(pool.submit(() -> {
                        try {
                            WecomUser full = client.user(session,
                                new WecomUserRef(user.userId(), user.departmentIds()));
                            Long managerId = full.directLeaders().isEmpty() ? null
                                : context.mappedIds.get(full.directLeaders().get(0));
                            Long localId = context.mappedIds.get(user.userId());
                            if (localId == null) return;
                            if (Objects.equals(localId, managerId)) managerId = null;
                            updates.add(new Object[]{managerId, localId});
                        } catch (RuntimeException exception) {
                            addError(errors, "成员 " + mask(user.userId()) + " 的直属上级同步失败");
                        }
                    }));
                }
                for (Future<?> future : futures) {
                    try {
                        future.get();
                    } catch (Exception ignored) {
                        // Per-user errors are captured in the errors list.
                    }
                }
                try {
                    Thread.sleep(MANAGER_BATCH_INTERVAL_MS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } finally {
            pool.shutdown();
        }
        if (!updates.isEmpty()) {
            jdbc.batchUpdate("UPDATE t_user SET manager_id = ? WHERE id = ?", updates);
        }
    }

    private int deactivateMissing(long companyId, List<WecomUser> externalUsers) {
        Set<String> externalIds = externalUsers.stream()
            .map(WecomUser::userId).collect(Collectors.toSet());
        List<Object[]> targets = jdbc.query("""
            SELECT wecom_user_id, user_id FROM t_wecom_user_mapping WHERE company_id = ?
            """, (rs, rowNum) -> new Object[]{rs.getString(1), rs.getLong(2)}, companyId)
            .stream()
            .filter(row -> !externalIds.contains((String) row[0]))
            .map(row -> new Object[]{row[1]})
            .toList();
        if (targets.isEmpty()) return 0;
        jdbc.batchUpdate(
            "UPDATE t_user SET status = 'DISABLED' WHERE id = ? AND status <> 'DISABLED'", targets);
        return targets.size();
    }

    private Map<Long, Long> syncDepartments(long companyId, List<WecomDepartment> departments) {
        Map<Long, Long> mappings = new HashMap<>();
        for (WecomDepartment department : departments) {
            Long localId = transactions.execute(status -> {
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

    static Long resolveMatch(List<Long> phoneMatches, List<Long> emailMatches) {
        if (phoneMatches.size() > 1 || emailMatches.size() > 1) {
            throw new SyncUserException("手机号或邮箱存在重复，无法自动绑定");
        }
        Set<Long> matches = new LinkedHashSet<>();
        matches.addAll(phoneMatches);
        matches.addAll(emailMatches);
        if (matches.size() > 1) {
            throw new SyncUserException("手机号和邮箱指向不同成员，无法自动绑定");
        }
        return matches.stream().findFirst().orElse(null);
    }

    static long primaryDepartment(WecomUser user) {
        if (user.mainDepartment() > 0 && user.departmentIds().contains(user.mainDepartment())) {
            return user.mainDepartment();
        }
        if (!user.departmentIds().isEmpty()) return user.departmentIds().get(0);
        throw new SyncUserException("成员没有所属部门");
    }

    static String deterministicUsername(long companyId, String userId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                (companyId + "\0" + userId).getBytes(StandardCharsets.UTF_8));
            return "wx_" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
                .substring(0, 32).toLowerCase();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static List<WecomDepartment> orderDepartments(List<WecomDepartment> input) {
        Map<Long, WecomDepartment> byId = new LinkedHashMap<>();
        input.forEach(department -> {
            if (department.id() <= 0 || byId.put(department.id(), department) != null) {
                throw new WecomClient.WecomApiException("企业微信部门树包含重复部门");
            }
        });
        List<WecomDepartment> result = new ArrayList<>();
        Set<Long> visiting = new HashSet<>();
        Set<Long> visited = new HashSet<>();
        for (WecomDepartment department : input) {
            visitDepartment(department, byId, visiting, visited, result);
        }
        return result;
    }

    private static void visitDepartment(WecomDepartment department,
                                        Map<Long, WecomDepartment> byId, Set<Long> visiting,
                                        Set<Long> visited, List<WecomDepartment> result) {
        if (visited.contains(department.id())) return;
        if (!visiting.add(department.id())) {
            throw new WecomClient.WecomApiException("企业微信部门树存在循环");
        }
        if (department.parentId() != 0) {
            WecomDepartment parent = byId.get(department.parentId());
            if (parent == null) throw new WecomClient.WecomApiException("企业微信部门树缺少父部门");
            visitDepartment(parent, byId, visiting, visited, result);
        }
        visiting.remove(department.id());
        visited.add(department.id());
        result.add(department);
    }

    private void stage(long jobId, String phase, int percent, String message) {
        jdbc.update("""
            UPDATE t_wecom_sync_job SET status = 'RUNNING', phase = ?, percent = ?, message = ?,
                started_at = COALESCE(started_at, now()), updated_at = now()
            WHERE id = ? AND status IN ('PENDING', 'RUNNING')
            """, phase, percent, message, jobId);
    }

    private void progress(long jobId, int percent, int total, int processed, int created,
                          int updated, int failed, String message, List<String> errors) {
        jdbc.update("""
            UPDATE t_wecom_sync_job SET phase = 'USERS', percent = ?, total_users = ?,
                processed_users = ?, created_users = ?, updated_users = ?, failed_users = ?,
                message = ?, error_summary = CAST(? AS jsonb), updated_at = now()
            WHERE id = ?
            """, percent, total, processed, created, updated, failed, message, errorJson(errors), jobId);
    }

    private void finish(long jobId, String status, String message, List<String> errors) {
        jdbc.update("""
            UPDATE t_wecom_sync_job SET status = ?, phase = 'COMPLETED', percent = 100,
                message = ?, error_summary = CAST(? AS jsonb), finished_at = now(), updated_at = now()
            WHERE id = ?
            """, status, message, errorJson(errors), jobId);
    }

    private void fail(long jobId, String message, List<String> errors) {
        jdbc.update("""
            UPDATE t_wecom_sync_job SET status = 'FAILED', phase = 'COMPLETED',
                message = ?, error_summary = CAST(? AS jsonb), finished_at = now(), updated_at = now()
            WHERE id = ?
            """, message, errorJson(errors), jobId);
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
                   oauth_enabled, js_sdk_enabled, message_enabled
            FROM t_wecom_config WHERE company_id = ?
            """, rs -> rs.next() ? new Config(rs.getString(1), rs.getString(2),
                (Integer) rs.getObject(3), rs.getString(4), rs.getBoolean(5),
                rs.getBoolean(6), rs.getBoolean(7)) : null, companyId);
    }

    private Long mappedDepartment(long companyId, long externalId) {
        return jdbc.query("""
            SELECT department_id FROM t_wecom_department_mapping
            WHERE company_id = ? AND wecom_department_id = ?
            """, rs -> rs.next() ? rs.getLong(1) : null, companyId, externalId);
    }

    private Long mappedUser(long companyId, String externalId) {
        return jdbc.query("""
            SELECT user_id FROM t_wecom_user_mapping
            WHERE company_id = ? AND wecom_user_id = ?
            """, rs -> rs.next() ? rs.getLong(1) : null, companyId, externalId);
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

    private static String userError(RuntimeException exception) {
        return exception instanceof SyncUserException ? exception.getMessage()
            : exception instanceof WecomClient.WecomApiException ? exception.getMessage()
            : "本地成员数据写入失败";
    }

    private static String displayName(WecomUser user) {
        String name = trim(user.name());
        return name.isBlank() ? user.userId() : name.substring(0, Math.min(128, name.length()));
    }

    private static String gender(String value) {
        return switch (trim(value)) {
            case "1" -> "男";
            case "2" -> "女";
            default -> null;
        };
    }

    private static String blankToNull(String value) {
        String normalized = trim(value);
        return normalized.isBlank() ? null : normalized;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String mask(String value) {
        if (value == null || value.isBlank()) return "***";
        if (value.length() < 3) return value.charAt(0) + "***";
        return value.charAt(0) + "***" + value.charAt(value.length() - 1);
    }

    private record Config(String corpId, String encryptedSecret, Integer agentId,
                          String encryptedAgentSecret, boolean oauthEnabled,
                          boolean jsSdkEnabled, boolean messageEnabled) { }
    private record DepartmentState(long companyId, String path) { }
    private record LocalUserState(long id, String username, String employeeNo, String phone,
                                  String email, String displayName, String position,
                                  String gender, String status, String wecomUserId) { }
    private static final class SyncContext {
        final Map<String, Long> mappedIds = new HashMap<>();
        final Map<String, List<Long>> byPhone = new HashMap<>();
        final Map<String, List<Long>> byEmail = new HashMap<>();
        final Set<String> usernames = new HashSet<>();
        final Map<String, Long> employeeNoOwners = new HashMap<>();
        final Map<Long, LocalUserState> byId = new HashMap<>();
        long roleId;
        final List<WecomUser> pending = new ArrayList<>();
        final List<WecomUser> createdUsers = new ArrayList<>();
    }

    static class SyncUserException extends RuntimeException {
        SyncUserException(String message) {
            super(message);
        }
    }

    public record SettingsDto(long companyId, String corpId, boolean secretConfigured,
                              JobDto latestJob, Integer agentId, boolean agentSecretConfigured,
                              boolean oauthEnabled, boolean jsSdkEnabled,
                              boolean messageEnabled) {
        public SettingsDto(long companyId, String corpId, boolean secretConfigured, JobDto latestJob) {
            this(companyId, corpId, secretConfigured, latestJob, null, false, false, false, false);
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
