package com.antflow.org;

import com.antflow.audit.AuditService;
import com.antflow.auth.AuthSessionService;
import com.antflow.authz.AuthorizationService;
import com.antflow.common.FormalNumberService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户管理的对外门面：**只做转发**，实现分别落在
 * {@link UserAccountService}（增删改）、{@link UserQueryService}（查询/分页）、
 * {@link UserStatusService}（状态/密码/企微登录）、{@link UserRoles}（角色与管理员不变量）。
 *
 * <p>为什么留着这一层：
 * <ol>
 *   <li>{@code @Transactional} 只在**代理入口**上生效，事务边界必须留在这里（协作者上不标注解）；</li>
 *   <li>{@code UserQuery} / {@code LEADER_TITLE} 是公开契约（Controller、移动端、集成测试都按
 *       {@code UserService.UserQuery.of(...)} 用），必须留在原名下；</li>
 *   <li>唯一约束冲突（{@code t_user_username_key} / {@code uk_user_employee_no}）靠异常原样冒到
 *       {@code GlobalExceptionHandler}，门面**不 catch 任何 DataAccessException**。</li>
 * </ol>
 *
 * <p>协作者都是包内可见的普通对象，由本构造器组装（依赖方向单向：门面 → 协作者）。
 */
@Service
public class UserService {
    private final DepartmentMapper departmentMapper;
    private final UserRoles roles;
    private final UserAccountService accounts;
    private final UserQueryService queries;
    private final UserStatusService statuses;
    private final UserAuthzVersion authzVersion;

    /** 构造器参数与原来 Lombok 生成的完全一致（顺序、类型都不动），测试与 Spring 都不用改。 */
    public UserService(UserMapper userMapper, UserRoleMapper userRoleMapper, RoleMapper roleMapper,
                       PasswordEncoder encoder, DepartmentMapper departmentMapper,
                       DepartmentLeaderMapper leaderMapper, JdbcTemplate jdbcTemplate,
                       FormalNumberService formalNumberService,
                       AuthorizationService authorizationService,
                       AuthSessionService authSessionService, AuditService auditService,
                       OrgAccess orgAccess) {
        this.departmentMapper = departmentMapper;
        this.roles = new UserRoles(userRoleMapper, roleMapper, jdbcTemplate);
        this.queries = new UserQueryService(userMapper, departmentMapper, jdbcTemplate,
            authorizationService, orgAccess);
        this.authzVersion = new UserAuthzVersion(jdbcTemplate, authorizationService);
        UserGuards guards = new UserGuards(userMapper, departmentMapper, jdbcTemplate,
            formalNumberService);
        this.statuses = new UserStatusService(userMapper, encoder, jdbcTemplate, authSessionService,
            authorizationService, roles, guards, authzVersion, queries);
        this.accounts = new UserAccountService(userMapper, userRoleMapper, departmentMapper,
            leaderMapper, jdbcTemplate, encoder, formalNumberService, authorizationService,
            authSessionService, auditService, orgAccess, roles, guards, statuses,
            authzVersion);
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(User u, List<Long> roleIds, String rawPassword) {
        return accounts.create(u, roleIds, rawPassword);
    }

    @Transactional(rollbackFor = Exception.class)
    public void setRoles(Long userId, List<Long> roleIds) {
        accounts.setRoles(userId, roleIds);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId) {
        accounts.delete(userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public User update(Long userId, Map<String, Object> body) {
        return accounts.update(userId, body);
    }

    @Transactional(rollbackFor = Exception.class)
    public User setWecomLoginAccess(Long userId, boolean enabled) {
        return statuses.setWecomLoginAccess(userId, enabled);
    }

    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(Long userId, String rawPassword) {
        statuses.resetPassword(userId, rawPassword);
    }

    @Transactional
    public void changeOwnPassword(long userId, UUID currentSessionId,
                                  String currentPassword, String nextPassword) {
        statuses.changeOwnPassword(userId, currentSessionId, currentPassword, nextPassword);
    }

    public void authorizationChanged(Long userId) {
        authzVersion.authorizationChanged(userId);
    }

    public List<String> rolesOf(Long userId) {
        return roles.rolesOf(userId);
    }

    public List<User> listAuthorized(UserQuery request) {
        return queries.listAuthorized(new UserQueryService.Criteria(request.keyword(),
            request.departmentId(), request.includeDescendants(), request.userIds(),
            request.positionFilter(LEADER_TITLE)));
    }

    public Page<User> listAuthorizedPage(String keyword, Long departmentId,
                                         boolean includeDescendants, long page, long size) {
        return queries.listAuthorizedPage(keyword, departmentId, includeDescendants, page, size);
    }

    public List<User> managerCandidates(Long departmentId, Long excludedUserId, String keyword) {
        return queries.managerCandidates(departmentId, excludedUserId, keyword);
    }

    /**
     * 「部门领导」的范围定义：职务里带这个称谓。称谓改了只需改这一处。
     * ponytail: 按职务字符串判定，不是 t_department.leader_id 关系；若 HR 改用别的称谓，改这里一个常量。
     */
    public static final String LEADER_TITLE = "部长";

    /**
     * 用户候选的收窄条件。各条件相互独立、都可为空；选择器一次只用一个维度，
     * 但它们能自由组合（例如「研发部的部长」= departmentId + position）。
     * 用 record 而不是一排位置参数：{@code includeDescendants} 与 {@code leaderOnly}
     * 都是 boolean，排在一起极易传错。
     *
     * <p>下面两个方法是「怎么用这些条件」的唯一实现——桌面 /api/users 与移动端选择器
     * 都调它们，免得两处各写一份慢慢漂移。
     */
    public record UserQuery(String keyword, Long departmentId, boolean includeDescendants,
                            boolean leaderOnly, String position, List<Long> userIds) {
        public static UserQuery of(String keyword, Long departmentId, Boolean includeDescendants,
                                   Boolean leaderOnly, String position, List<Long> userIds) {
            return new UserQuery(keyword, departmentId, Boolean.TRUE.equals(includeDescendants),
                Boolean.TRUE.equals(leaderOnly), position, userIds);
        }

        /** 职务过滤的最终值：自由文本优先，否则取「部门领导」的预设称谓；都没有则不过滤。 */
        public String positionFilter(String leaderTitle) {
            if (position != null && !position.isBlank()) {
                return position.trim();
            }
            return leaderOnly ? leaderTitle : null;
        }

        /** 部门收窄最终展开成的 id 集合；返回 null 表示不按部门收窄。 */
        public Collection<Long> resolveDepartments(DepartmentMapper mapper) {
            if (departmentId == null) {
                return null;
            }
            return includeDescendants ? mapper.subtreeIds(departmentId) : List.of(departmentId);
        }
    }
}
