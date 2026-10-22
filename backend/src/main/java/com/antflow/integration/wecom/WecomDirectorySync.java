package com.antflow.integration.wecom;

import com.antflow.integration.wecom.WecomClient.WecomDepartment;
import com.antflow.integration.wecom.WecomClient.WecomUser;
import com.antflow.integration.wecom.WecomMappingRules.LocalUserState;
import com.antflow.integration.wecom.WecomMappingRules.SyncUserException;
import com.antflow.integration.wecom.WecomSyncLease.LeaseLostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 企微通讯录的**落库**：部门树、成员、部门负责人、离职停用。
 *
 * <p>为什么独立：这是同步里唯一"写组织数据"的部分，与配置读写、任务状态查询、消息投递
 * 没有共享逻辑。它同时是 {@link SyncContext} 的唯一拥有者——那份工作区（6 个索引 + pending）
 * 只在"加载 → 分批写 → 重试重建"这个闭环里有意义。
 *
 * <p>三条约束：
 * <ol>
 *   <li>**每次写库前必须 {@code lease.require()}**：所有 {@code transactions.execute*} 的第一行都是它。</li>
 *   <li>**{@link SyncContext} 不可跨批次复用而不重建**：单成员失败后调用方会重新
 *       {@link #loadContext}，因为失败可能已经改了一半索引（例如工号被占位后回滚）。</li>
 *   <li>**部门必须先父后子**：ltree 的 path 依赖父路径，{@link #syncDepartments} 假定入参已由
 *       {@link WecomMappingRules#orderDepartments} 排好序。</li>
 * </ol>
 *
 * <p>**错误聚合不在这里做**：{@link #syncDepartmentLeaders} 返回问题列表，上限与截断由调用方统一执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
class WecomDirectorySync {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final TransactionTemplate transactions;
    private final WecomSyncLease lease;

    SyncContext loadContext(long companyId, List<WecomUser> externalUsers, boolean full) {
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

    int[] syncUsers(long companyId, List<WecomUser> batch,
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
                    WecomMappingRules.displayName(user), WecomMappingRules.email(user),
                    WecomMappingRules.phone(user), WecomMappingRules.position(user),
                    WecomMappingRules.gender(user.gender()),
                    WecomMappingRules.effectiveUserStatus(user.status(), null)});
                insertedUsernames.add(username);
                insertedIndexes.add(index);
                created++;
            } else {
                Long departmentId = departmentMappings.get(WecomMappingRules.primaryDepartment(user));
                if (departmentId == null) throw new SyncUserException("主部门未绑定");
                String employeeNo = wecomEmployeeNo(context, user.userId(), localId);
                String username = wecomUsername(context, employeeNo, localId);
                updates.add(new Object[]{username, employeeNo, departmentId,
                    WecomMappingRules.displayName(user), WecomMappingRules.phone(user),
                    WecomMappingRules.email(user), WecomMappingRules.position(user),
                    WecomMappingRules.gender(user.gender()), localId});
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
        List<Long> phoneMatches = WecomMappingRules.blankToNull(phone) == null ? List.of()
            : context.byPhone.getOrDefault(WecomMappingRules.trim(phone), List.of());
        List<Long> emailMatches = WecomMappingRules.blankToNull(email) == null ? List.of()
            : context.byEmail.getOrDefault(WecomMappingRules.trim(email).toLowerCase(), List.of());
        return WecomMappingRules.resolveMatch(phoneMatches, emailMatches);
    }

    private static String wecomEmployeeNo(SyncContext context, String userId, Long localId) {
        String value = WecomMappingRules.trim(userId);
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

    int deactivateMissing(long companyId, List<WecomUser> externalUsers) {
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

    /**
     * 逐个部门同步负责人：一个部门一个事务。
     *
     * <p>企微允许多位负责人，本地只存一位——保留第一位并明确告知；负责人未同步或已停用时清空，
     * 而不是保留一个指向离职账号的悬空引用。
     *
     * @return 本次产生的问题描述；数量上限由调用方决定
     */


    /**
     * 同步工作区：本地账号的 6 个索引 + 本批待处理成员。
     *
     * <p>**包内可见**是刻意的：调用方（{@code WecomService.run}）要在批次重试时整体替换它，
     * 并在成员同步结束后把 {@code mappedIds} 交给直属上级同步使用。
     */
    static final class SyncContext {
        final Map<String, Long> mappedIds = new HashMap<>();
        final Map<String, List<Long>> byPhone = new HashMap<>();
        final Map<String, List<Long>> byEmail = new HashMap<>();
        final Map<String, Long> usernameOwners = new HashMap<>();
        final Map<String, Long> employeeNoOwners = new HashMap<>();
        final Map<Long, LocalUserState> byId = new HashMap<>();
        long roleId;
        final List<WecomUser> pending = new ArrayList<>();
    }
}
