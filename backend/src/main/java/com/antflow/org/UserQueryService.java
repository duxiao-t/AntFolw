package com.antflow.org;

import com.antflow.authz.AuthorizationService;
import com.antflow.engine.BizException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 用户查询与分页：收窄条件（关键字/部门/职务/名单/公司）→ 逐行数据范围过滤 → 补显示用的派生字段。
 *
 * <p>从 {@code UserService} 原样搬出（方法体逐字未改）。这里最容易踩的两个坑：
 * <ul>
 *   <li>**"给了名单但名单为空" = 明确要求零候选**，不能当成"没给过滤"——否则设计器把"指定人员"
 *       清空后会退化成全员可选；</li>
 *   <li>分页路径对 {@code ALL} 数据范围**不能**按 {@code manageableDepartments} 收窄：
 *       那个集合只含"存在"的部门 id，会把 {@code dept_id IS NULL} 的成员挡在外面，而单行判权
 *       对 ALL 返回 true——两条路径口径不一致，搜人时一眼就能看出来。</li>
 * </ul>
 */
@RequiredArgsConstructor
class UserQueryService {
    private final UserMapper userMapper;
    private final DepartmentMapper departmentMapper;
    private final JdbcTemplate jdbcTemplate;
    private final AuthorizationService authorizationService;
    private final OrgAccess orgAccess;

    /**
     * 查询条件。**为什么不直接吃 {@code UserService.UserQuery}**：那是个挂在门面上的公开嵌套类型
     * （外部调用方按 {@code UserService.UserQuery.of(...)} 拼参数，签名不能动），新类再引用它就成了
     * 反向依赖。这里收的是同一批原始条件，部门展开仍发生在原来的位置（见 {@link #resolveDepartments}）。
     */
    record Criteria(String keyword, Long departmentId, boolean includeDescendants,
                    List<Long> userIds, String positionFilter) {
    }

    List<User> listAuthorized(Criteria request) {
        authorizationService.requirePermission(com.antflow.authz.PermissionCodes.ORG_USER_READ);
        QueryWrapper<User> query = new QueryWrapper<>();
        if (request.keyword() != null && !request.keyword().isBlank()) {
            String keyword = request.keyword().trim();
            query.and(wrapper -> {
                wrapper.like("username", keyword)
                    .or().like("display_name", keyword)
                    .or().like("employee_no", keyword);
                DepartmentMapper.applyDeptNameMatch(wrapper, keyword);
            });
        }
        // 显式名单：候选只限这些人。仍会被下面的数据范围再过滤一次。
        // 注意「给了名单但是空的」= 明确要求"零候选"，不能当成"没给过滤"——否则设计器把
        // "指定人员"清空后，本该没人可选的字段会退化成全员可选。
        if (request.userIds() != null) {
            if (request.userIds().isEmpty()) {
                return List.of();
            }
            query.in("id", request.userIds());
        }
        Collection<Long> departments = resolveDepartments(request);
        if (departments != null) {
            if (departments.isEmpty()) {
                return List.of();
            }
            query.in("dept_id", departments);
        }
        // 「部门领导」只是职务过滤的一个预设值，两者共用一条路径。
        String positionFilter = request.positionFilter();
        if (positionFilter != null) {
            // LIKE 自动排除职务为空的用户——没有职务的人本来就不是领导。
            query.like("position", positionFilter);
        }
        List<User> users = userMapper.selectList(query).stream()
            .filter(user -> authorizationService.inCurrentDataScope(
                com.antflow.authz.PermissionCodes.ORG_USER_READ,
                user.getId(), user.getDeptId()))
            .toList();
        fillManagerNames(users);
        return users;
    }

    Page<User> listAuthorizedPage(String keyword, Long departmentId,
                                  boolean includeDescendants, long page, long size) {
        String permission = com.antflow.authz.PermissionCodes.ORG_USER_READ;
        authorizationService.requirePermission(permission);
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), 100);
        QueryWrapper<User> query = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String normalized = keyword.trim();
            // 关键字也匹配部门名（含下级）：通讯录里搜"研发部"要出人，不能只过滤左树。
            query.and(wrapper -> {
                wrapper.like("username", normalized)
                    .or().like("display_name", normalized)
                    .or().like("employee_no", normalized);
                DepartmentMapper.applyDeptNameMatch(wrapper, normalized);
            });
        }
        if (departmentId != null) {
            List<Long> requested = includeDescendants
                ? departmentMapper.subtreeIds(departmentId) : List.of(departmentId);
            if (requested.isEmpty()) return Page.of(safePage, safeSize, 0);
            query.in("dept_id", requested);
        }
        AuthorizationService.AuthzSnapshot snapshot = authorizationService.currentSnapshot();
        // ALL 范围的人不该被"按部门列举"收窄：manageableDepartments 只返回**存在**的部门 id，
        // 拿它做 dept_id IN (...) 会把 dept_id IS NULL 的成员（种子里的 admin/bob 就是）挡在外面，
        // 而单条的 inCurrentDataScope 对 ALL 返回 true——两条路径口径不一致，搜人时会直接看出来。
        var effectiveScope = authorizationService.currentDataScope(permission);
        boolean unrestricted = effectiveScope.map(AuthorizationService.DataScopeFilter::unrestricted)
            .orElse(false);
        if (!snapshot.admin() && !unrestricted) {
            Set<Long> departments = authorizationService.manageableDepartments(snapshot, permission);
            boolean self = snapshot.permissionRoles().getOrDefault(permission, List.of()).stream()
                .anyMatch(grant -> grant.dataScope() == com.antflow.authz.DataScope.SELF);
            if (departments.isEmpty() && !self) return Page.of(safePage, safeSize, 0);
            query.and(scope -> {
                if (!departments.isEmpty()) scope.in("dept_id", departments);
                if (self) {
                    if (!departments.isEmpty()) scope.or();
                    scope.eq("id", snapshot.userId());
                }
            });
        }
        query.orderByDesc("EXISTS (SELECT 1 FROM t_department_leader department_leader"
                + " WHERE department_leader.department_id = t_user.dept_id"
                + " AND department_leader.user_id = t_user.id)")
            .orderByAsc("display_name").orderByAsc("id");
        Page<User> result = userMapper.selectPage(Page.of(safePage, safeSize), query);
        fillManagerNames(result.getRecords());
        fillDirectoryMetadata(result.getRecords());
        return result;
    }

    /**
     * 部门条件展开成 id 集合；{@code null} 表示不按部门收窄。
     *
     * <p>**必须在这里、而不是调用方**做展开：原来是"先判空名单直接返回，再展开部门"，
     * 提前展开会让"指定人员=空"的请求白查一次子树（子树查询还可能因为部门被删而抛错）。
     */
    private Collection<Long> resolveDepartments(Criteria request) {
        if (request.departmentId() == null) {
            return null;
        }
        return request.includeDescendants()
            ? departmentMapper.subtreeIds(request.departmentId())
            : List.of(request.departmentId());
    }

    List<User> managerCandidates(Long departmentId, Long excludedUserId, String keyword) {
        orgAccess.requireDepartment(departmentId);
        authorizationService.requireManageableDepartment(
            com.antflow.authz.PermissionCodes.ORG_USER_MANAGE, departmentId);
        Department department = departmentId == null ? null : departmentMapper.selectById(departmentId);
        if (department == null) {
            throw new BizException("DEPARTMENT_NOT_FOUND", "所属部门不存在");
        }
        // 单公司：候选人不再按公司圈定，但仍要求其归属某个部门（无部门无法参与汇报关系）
        QueryWrapper<User> query = new QueryWrapper<User>()
            .eq("status", "ACTIVE")
            .isNotNull("dept_id")
            .orderByAsc("display_name")
            .orderByAsc("id")
            .last("LIMIT 100");
        if (excludedUserId != null) query.ne("id", excludedUserId);
        if (keyword != null && !keyword.isBlank()) {
            String normalized = keyword.trim();
            query.and(item -> item.like("display_name", normalized)
                .or().like("username", normalized)
                .or().like("employee_no", normalized));
        }
        return userMapper.selectList(query);
    }

    private void fillManagerNames(List<User> users) {
        List<Long> managerIds = users.stream()
            .map(User::getManagerId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (managerIds.isEmpty()) return;
        Map<Long, User> managers = userMapper.selectBatchIds(managerIds).stream()
            .collect(Collectors.toMap(User::getId, Function.identity()));
        for (User user : users) {
            User manager = managers.get(user.getManagerId());
            if (manager != null) user.setManagerDisplayName(manager.getDisplayName());
        }
    }

    /**
     * 一次批量查询补齐企微映射/目录状态/部门负责人标记。
     *
     * <p>占位符按记录数拼（{@code IN (?,?,...)}），**参数直接传数组**让 JdbcTemplate 走 varargs；
     * 逐条查会变成 N+1（一页 20 人就是 20 次往返）。
     */
    void fillDirectoryMetadata(List<User> users) {
        if (users.isEmpty()) return;
        Map<Long, User> byId = users.stream()
            .collect(Collectors.toMap(User::getId, Function.identity()));
        String placeholders = String.join(",", Collections.nCopies(users.size(), "?"));
        jdbcTemplate.query("""
            SELECT user_row.id, EXISTS (
                       SELECT 1 FROM t_wecom_user_mapping synced WHERE synced.user_id = user_row.id
                   ) AS wecom_mapped,
                   mapping.wecom_status, mapping.directory_present,
                   EXISTS (
                       SELECT 1 FROM t_department_leader department_leader
                       WHERE department_leader.department_id = user_row.dept_id
                         AND department_leader.user_id = user_row.id
                   ) AS department_leader
            FROM t_user user_row
            LEFT JOIN t_department department ON department.id = user_row.dept_id
            LEFT JOIN t_wecom_user_mapping mapping
              ON mapping.user_id = user_row.id
             AND mapping.company_id = department.company_id
            WHERE user_row.id IN (""" + placeholders + ")", resultSet -> {
                User user = byId.get(resultSet.getLong("id"));
                if (user == null) return;
                boolean mapped = resultSet.getBoolean("wecom_mapped");
                user.setWecomMapped(mapped);
                user.setWecomStatus(mapped ? (Integer) resultSet.getObject("wecom_status") : null);
                user.setWecomDirectoryPresent(mapped
                    && resultSet.getBoolean("directory_present"));
                user.setDepartmentLeader(resultSet.getBoolean("department_leader"));
            }, users.stream().map(User::getId).toArray());
    }
}
