package com.antflow.mobile.workflow;

import com.antflow.auth.PrincipalHolder;
import com.antflow.org.Department;
import com.antflow.org.DepartmentMapper;
import com.antflow.org.User;
import com.antflow.org.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SuppressWarnings({"unchecked", "rawtypes"})
class MobileOrgControllerTest {
    private UserMapper userMapper;
    private DepartmentMapper departmentMapper;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userMapper = Mockito.mock(UserMapper.class);
        departmentMapper = Mockito.mock(DepartmentMapper.class);
        MobileOrgService service = new MobileOrgService(userMapper, departmentMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(new MobileOrgController(service)).build();
        PrincipalHolder.set(new PrincipalHolder.Principal(7L, "mobile-user", List.of("user")));
    }

    @AfterEach
    void tearDown() {
        PrincipalHolder.clear();
    }

    @Test
    void mobileUserPickerReturnsSafeUserDtosWithoutAdminRole() throws Exception {
        when(userMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(user()));

        mockMvc.perform(get("/api/mobile/users").param("keyword", "zhang"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(1001))
            .andExpect(jsonPath("$[0].username").value("zhangsan"))
            .andExpect(jsonPath("$[0].displayName").value("张三"))
            .andExpect(jsonPath("$[0].email").doesNotExist())
            .andExpect(jsonPath("$[0].passwordHash").doesNotExist());

        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        Mockito.verify(userMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertThat(sql).contains("username");
        assertThat(sql).contains("display_name");
    }

    @Test
    void mobileDepartmentPickerReturnsSafeDepartmentDtosWithoutAdminRole() throws Exception {
        when(departmentMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(department()));

        mockMvc.perform(get("/api/mobile/departments").param("keyword", "研"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(2001))
            .andExpect(jsonPath("$[0].name").value("研发部"))
            .andExpect(jsonPath("$[0].leaderId").doesNotExist())
            .andExpect(jsonPath("$[0].path").doesNotExist());

        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        Mockito.verify(departmentMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("name");
    }

    private static User user() {
        User user = new User();
        user.setId(1001L);
        user.setUsername("zhangsan");
        user.setDisplayName("张三");
        user.setEmail("private@example.com");
        user.setPasswordHash("secret");
        return user;
    }

    private static Department department() {
        Department department = new Department();
        department.setId(2001L);
        department.setName("研发部");
        department.setLeaderId(7L);
        department.setPath("acme.root.dev");
        return department;
    }
}
