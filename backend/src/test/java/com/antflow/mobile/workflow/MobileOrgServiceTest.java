package com.antflow.mobile.workflow;

import com.antflow.org.Department;
import com.antflow.org.DepartmentMapper;
import com.antflow.org.User;
import com.antflow.org.UserMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

class MobileOrgServiceTest {
    @Test
    void returnsNameDepartmentAndEmployeeNumberForPickerRows() {
        UserMapper users = Mockito.mock(UserMapper.class);
        DepartmentMapper departments = Mockito.mock(DepartmentMapper.class);
        User user = user(7L, 20L, "张三", "zhangsan", "000007");
        Department department = new Department();
        department.setId(20L);
        department.setName("研发部");
        Mockito.when(users.selectList(any())).thenReturn(List.of(user));
        Mockito.when(departments.selectBatchIds(any())).thenReturn(List.of(department));

        MobilePickerUserDto row = new MobileOrgService(users, departments).searchUsers("张").get(0);

        assertThat(row).isEqualTo(new MobilePickerUserDto(7L, "zhangsan", "张三", "研发部", "000007"));
    }

    @Test
    void readsSelectedUserIdentityById() {
        UserMapper users = Mockito.mock(UserMapper.class);
        DepartmentMapper departments = Mockito.mock(DepartmentMapper.class);
        User user = user(7L, 20L, "张三", "zhangsan", "000007");
        Department department = new Department();
        department.setId(20L);
        department.setName("研发部");
        Mockito.when(users.selectById(7L)).thenReturn(user);
        Mockito.when(departments.selectById(20L)).thenReturn(department);

        MobilePickerUserDto row = new MobileOrgService(users, departments).user(7L);

        assertThat(row.department()).isEqualTo("研发部");
        assertThat(row.employeeNo()).isEqualTo("000007");
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
