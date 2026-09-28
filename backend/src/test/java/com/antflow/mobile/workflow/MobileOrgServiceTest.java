package com.antflow.mobile.workflow;

import com.antflow.org.Department;
import com.antflow.org.DepartmentMapper;
import com.antflow.org.RoleMapper;
import com.antflow.org.User;
import com.antflow.org.UserMapper;
import com.antflow.org.UserService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

class MobileOrgServiceTest {
    @Test
    void returnsNameDepartmentAndEmployeeNumberForPickerRows() {
        UserMapper users = Mockito.mock(UserMapper.class);
        DepartmentMapper departments = Mockito.mock(DepartmentMapper.class);
        RoleMapper roles = Mockito.mock(RoleMapper.class);
        User user = user(7L, 20L, "张三", "zhangsan", "000007");
        Department department = new Department();
        department.setId(20L);
        department.setName("研发部");
        Mockito.when(users.selectList(any())).thenReturn(List.of(user));
        Mockito.when(departments.selectBatchIds(any())).thenReturn(List.of(department));

        MobileOrgService.RuntimePickerUserDto row = new MobileOrgService(users, departments, roles)
            .searchUsers(keywordQuery("张")).get(0);

        // 列表端点刻意不返回登录账号（可被关键字枚举的入口）：窄 DTO 上根本没有 username 字段，
        // 所以"没泄漏账号"这件事是**编译期**保证的，不需要断言。
        assertThat(row).isEqualTo(new MobileOrgService.RuntimePickerUserDto(
            7L, "张三", "研发部", "000007"));
    }

    @Test
    void readsSelectedUserIdentityById() {
        UserMapper users = Mockito.mock(UserMapper.class);
        DepartmentMapper departments = Mockito.mock(DepartmentMapper.class);
        RoleMapper roles = Mockito.mock(RoleMapper.class);
        User user = user(7L, 20L, "张三", "zhangsan", "000007");
        Department department = new Department();
        department.setId(20L);
        department.setName("研发部");
        Mockito.when(users.selectById(7L)).thenReturn(user);
        Mockito.when(departments.selectById(20L)).thenReturn(department);

        MobilePickerUserDto row = new MobileOrgService(users, departments, roles).user(7L);

        assertThat(row.department()).isEqualTo("研发部");
        assertThat(row.employeeNo()).isEqualTo("000007");
    }

    @Test
    void readsSelectedDepartmentById() {
        UserMapper users = Mockito.mock(UserMapper.class);
        DepartmentMapper departments = Mockito.mock(DepartmentMapper.class);
        RoleMapper roles = Mockito.mock(RoleMapper.class);
        Department department = new Department();
        department.setId(20L);
        department.setName("研发部");
        Mockito.when(departments.selectById(20L)).thenReturn(department);

        MobilePickerDepartmentDto row = new MobileOrgService(users, departments, roles).department(20L);

        assertThat(row).isEqualTo(new MobilePickerDepartmentDto(20L, "研发部"));
    }

    @Test
    void hidesMissingSelectedDepartment() {
        UserMapper users = Mockito.mock(UserMapper.class);
        DepartmentMapper departments = Mockito.mock(DepartmentMapper.class);
        RoleMapper roles = Mockito.mock(RoleMapper.class);

        assertThatThrownBy(() -> new MobileOrgService(users, departments, roles).department(404L))
            .isInstanceOf(com.antflow.authz.HiddenResourceException.class);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void searchesUsersByMatchingDepartmentName() {
        UserMapper users = Mockito.mock(UserMapper.class);
        DepartmentMapper departments = Mockito.mock(DepartmentMapper.class);
        RoleMapper roles = Mockito.mock(RoleMapper.class);
        User user = user(7L, 20L, "张三", "zhangsan", "000007");
        Department department = new Department();
        department.setId(20L);
        department.setName("研发部");
        Mockito.when(departments.selectList(any(QueryWrapper.class))).thenReturn(List.of(department));
        Mockito.when(users.selectList(any(QueryWrapper.class))).thenReturn(List.of(user));
        Mockito.when(departments.selectBatchIds(any())).thenReturn(List.of(department));

        new MobileOrgService(users, departments, roles).searchUsers(keywordQuery("研发"));

        ArgumentCaptor<QueryWrapper> query = ArgumentCaptor.forClass(QueryWrapper.class);
        Mockito.verify(users).selectList(query.capture());
        assertThat(query.getValue().getSqlSegment()).contains("dept_id");
    }

    /** 设计器配的范围要落到移动端选择器的查询上——否则配了范围手机上照样看到全公司。 */
    @Test
    void appliesDesignerScopeToMobilePicker() {
        UserMapper users = Mockito.mock(UserMapper.class);
        DepartmentMapper departments = Mockito.mock(DepartmentMapper.class);
        RoleMapper roles = Mockito.mock(RoleMapper.class);
        Mockito.when(users.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        Mockito.when(departments.subtreeIds(20L)).thenReturn(List.of(20L, 21L));

        new MobileOrgService(users, departments, roles).searchUsers(
            UserService.UserQuery.of(null, 20L, true, false, "部长", null));

        ArgumentCaptor<QueryWrapper> query = ArgumentCaptor.forClass(QueryWrapper.class);
        Mockito.verify(users).selectList(query.capture());
        String segment = query.getValue().getSqlSegment();
        assertThat(segment).contains("dept_id").contains("position");
        assertThat(query.getValue().getParamNameValuePairs().values().stream()
            .anyMatch(value -> String.valueOf(value).contains("部长"))).isTrue();
    }

    private static UserService.UserQuery keywordQuery(String keyword) {
        return UserService.UserQuery.of(keyword, null, null, null, null, null);
    }

    private static User user(long id, long deptId, String displayName, String username,
                             String employeeNo) {
        User user = new User();
        user.setId(id);
        user.setDeptId(deptId);
        user.setDisplayName(displayName);
        user.setUsername(username);
        user.setEmployeeNo(employeeNo);
        return user;
    }
}
