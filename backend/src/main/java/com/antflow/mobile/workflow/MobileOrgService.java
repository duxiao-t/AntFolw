package com.antflow.mobile.workflow;

import com.antflow.org.Department;
import com.antflow.org.DepartmentMapper;
import com.antflow.org.Role;
import com.antflow.org.RoleMapper;
import com.antflow.org.PickerRoleDto;
import com.antflow.org.User;
import com.antflow.org.UserMapper;
import com.antflow.org.UserService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MobileOrgService {
    private static final int SEARCH_LIMIT = 20;
    /** 「已选」批量取人时的上限，防止拿这个接口当目录翻页工具。 */
    private static final int SELECTED_LIMIT = 200;

    private final UserMapper userMapper;
    private final DepartmentMapper departmentMapper;
    private final RoleMapper roleMapper;

    /**
     * 选择器候选。除关键字外还接受设计器配的范围（部门含下级 / 职务 / 显式名单）。
     *
     * <p>注意这里**不叠加查看者自己的数据范围**：填表人往往是员工，数据范围是「本人」，
     * 一叠加候选就只剩自己，待办里挑审批人会直接坏掉。收窄只按设计器的配置来。
     *
     * <p>返回的是**窄 DTO**（不含登录账号）：这两个列表端点是唯一能被"敲个关键字翻页"枚举的入口，
     * 而它们只要求登录 / 能进管理端。需要账号与工号的场景（人员详情、已选回显）走按 id 的端点。
     */
    public List<RuntimePickerUserDto> searchUsers(UserService.UserQuery request) {
        QueryWrapper<User> query = new QueryWrapper<>();
        query.select("id", "username", "display_name", "employee_no", "dept_id");
        String trimmedKeyword = normalizeKeyword(request.keyword());
        if (!trimmedKeyword.isEmpty()) {
            query.and(wrapper -> {
                wrapper.like("username", trimmedKeyword)
                    .or()
                    .like("display_name", trimmedKeyword)
                    .or()
                    .like("employee_no", trimmedKeyword);
                DepartmentMapper.applyDeptNameMatch(wrapper, trimmedKeyword);
            });
        }
        // 空名单 = 明确要求零候选，不能当成"没给过滤"（见 UserService.UserQuery 的说明）。
        if (request.userIds() != null) {
            if (request.userIds().isEmpty()) {
                return List.of();
            }
            query.in("id", request.userIds());
        }
        Collection<Long> departments = request.resolveDepartments(departmentMapper);
        if (departments != null) {
            if (departments.isEmpty()) {
                return List.of();
            }
            query.in("dept_id", departments);
        }
        String positionFilter = request.positionFilter(UserService.LEADER_TITLE);
        if (positionFilter != null) {
            query.like("position", positionFilter);
        }
        query.orderByAsc("display_name").last("LIMIT " + SEARCH_LIMIT);
        List<User> rows = userMapper.selectList(query);
        Map<Long, String> departmentNames = departmentsOf(rows);
        return rows.stream()
            .map(user -> toRuntimePickerUser(user, departmentNames))
            .toList();
    }

    public List<MobilePickerUserDto> selectedUsers(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        // 这个接口按调用方给的 id 列表取人，没有上限就等同于"传 N 个 id 拿 N 条"，可以被拿来翻目录。
        // 选择器实际用不到几百个选中项，超出的截断即可。
        return pickerUsers(userMapper.selectList(new QueryWrapper<User>()
            .select("id", "username", "display_name", "employee_no", "dept_id")
            .in("id", ids.stream().limit(SELECTED_LIMIT).toList())));
    }

    private List<MobilePickerUserDto> pickerUsers(List<User> users) {
        Map<Long, String> departments = departmentsOf(users);
        return users.stream()
            .map(user -> toPickerUser(user, departments))
            .toList();
    }

    private Map<Long, String> departmentsOf(List<User> users) {
        Set<Long> departmentIds = users.stream().map(User::getDeptId)
            .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        return departmentIds.isEmpty() ? Map.of()
            : departmentMapper.selectBatchIds(departmentIds).stream()
                .collect(java.util.stream.Collectors.toMap(Department::getId, Department::getName));
    }

    /** 运行时选择器候选的窄投影：只有够用的身份信息，不带登录账号。 */
    public static RuntimePickerUserDto toRuntimePickerUser(User user, Map<Long, String> departments) {
        return new RuntimePickerUserDto(user.getId(), user.getDisplayName(),
            user.getDeptId() == null ? null : departments.get(user.getDeptId()),
            user.getEmployeeNo());
    }

    /** 列表端点用的窄 DTO：id / 姓名 / 部门 / 工号。**刻意不含 username**。 */
    public record RuntimePickerUserDto(Long id, String displayName, String department,
                                       String employeeNo) { }

    public MobilePickerUserDto user(long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new com.antflow.authz.HiddenResourceException("user not found");
        Department department = user.getDeptId() == null ? null : departmentMapper.selectById(user.getDeptId());
        return new MobilePickerUserDto(user.getId(), user.getUsername(), user.getDisplayName(),
            department == null ? null : department.getName(), user.getEmployeeNo());
    }

    public List<MobilePickerDepartmentDto> searchDepartments(String keyword) {
        QueryWrapper<Department> query = new QueryWrapper<>();
        query.select("id", "name");
        String trimmedKeyword = normalizeKeyword(keyword);
        if (!trimmedKeyword.isEmpty()) {
            query.like("name", trimmedKeyword);
        }
        query.orderByAsc("name").last("LIMIT " + SEARCH_LIMIT);
        return departmentMapper.selectList(query).stream()
            .map(department -> new MobilePickerDepartmentDto(department.getId(), department.getName()))
            .toList();
    }

    /**
     * 选择器用的部门清单：全部部门、不打 20 条上限。{@link #searchDepartments} 有 LIMIT，
     * 拿它做下拉会静默漏掉排在后面的部门。供 /api/pickers/departments 使用。
     */
    public List<MobilePickerDepartmentDto> allDepartments() {
        return departmentMapper.selectList(new QueryWrapper<Department>()
                .select("id", "name").orderByAsc("name")).stream()
            .map(department -> new MobilePickerDepartmentDto(department.getId(), department.getName()))
            .toList();
    }

    /** 角色选择器：只暴露 id/code/name，供流程配置里的"审批角色"选择使用。 */
    public List<PickerRoleDto> searchRoles(String keyword) {
        QueryWrapper<Role> query = new QueryWrapper<>();
        query.select("id", "code", "name").eq("enabled", true);
        String trimmedKeyword = normalizeKeyword(keyword);
        if (!trimmedKeyword.isEmpty()) {
            query.and(wrapper -> wrapper.like("code", trimmedKeyword)
                .or().like("name", trimmedKeyword));
        }
        query.orderByAsc("id").last("LIMIT " + SEARCH_LIMIT);
        return roleMapper.selectList(query).stream()
            .map(role -> new PickerRoleDto(role.getId(), role.getCode(), role.getName()))
            .toList();
    }

    public List<PickerRoleDto> selectedRoles(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return roleMapper.selectList(new QueryWrapper<Role>()
            .select("id", "code", "name").in("id", ids)).stream()
            .map(role -> new PickerRoleDto(role.getId(), role.getCode(), role.getName()))
            .toList();
    }

    public MobilePickerDepartmentDto department(long departmentId) {
        Department department = departmentMapper.selectById(departmentId);
        if (department == null) {
            throw new com.antflow.authz.HiddenResourceException("department not found");
        }
        return new MobilePickerDepartmentDto(department.getId(), department.getName());
    }

    private static String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return "";
        }
        return keyword.trim();
    }

    private static MobilePickerUserDto toPickerUser(User user, Map<Long, String> departments) {
        return new MobilePickerUserDto(user.getId(), user.getUsername(), user.getDisplayName(),
            user.getDeptId() == null ? null : departments.get(user.getDeptId()), user.getEmployeeNo());
    }
}
