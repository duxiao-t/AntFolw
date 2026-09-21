package com.antflow.mobile.workflow;

import com.antflow.org.Department;
import com.antflow.org.DepartmentMapper;
import com.antflow.org.Role;
import com.antflow.org.RoleMapper;
import com.antflow.org.PickerRoleDto;
import com.antflow.org.User;
import com.antflow.org.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MobileOrgService {
    private static final int SEARCH_LIMIT = 20;

    private final UserMapper userMapper;
    private final DepartmentMapper departmentMapper;
    private final RoleMapper roleMapper;

    public List<MobilePickerUserDto> searchUsers(String keyword) {
        QueryWrapper<User> query = new QueryWrapper<>();
        query.select("id", "username", "display_name", "employee_no", "dept_id");
        String trimmedKeyword = normalizeKeyword(keyword);
        if (!trimmedKeyword.isEmpty()) {
            List<Long> matchingDepartmentIds = departmentMapper.selectList(
                new QueryWrapper<Department>().select("id").like("name", trimmedKeyword))
                .stream().map(Department::getId).toList();
            query.and(wrapper -> {
                wrapper.like("username", trimmedKeyword)
                    .or()
                    .like("display_name", trimmedKeyword)
                    .or()
                    .like("employee_no", trimmedKeyword);
                if (!matchingDepartmentIds.isEmpty()) {
                    wrapper.or().in("dept_id", matchingDepartmentIds);
                }
            });
        }
        query.orderByAsc("display_name").last("LIMIT " + SEARCH_LIMIT);
        return pickerUsers(userMapper.selectList(query));
    }

    public List<MobilePickerUserDto> selectedUsers(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return pickerUsers(userMapper.selectList(new QueryWrapper<User>()
            .select("id", "username", "display_name", "employee_no", "dept_id").in("id", ids)));
    }

    private List<MobilePickerUserDto> pickerUsers(List<User> users) {
        Set<Long> departmentIds = users.stream().map(User::getDeptId)
            .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        Map<Long, String> departments = departmentIds.isEmpty() ? Map.of()
            : departmentMapper.selectBatchIds(departmentIds).stream()
                .collect(java.util.stream.Collectors.toMap(Department::getId, Department::getName));
        return users.stream()
            .map(user -> toPickerUser(user, departments))
            .toList();
    }

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
