package com.antflow.mobile.workflow;

import com.antflow.auth.PrincipalHolder;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionMapper;
import com.antflow.org.User;
import com.antflow.org.UserMapper;
import com.antflow.task.TaskMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MobileBootstrapControllerTest {
    @Mock
    private UserMapper userMapper;
    @Mock
    private TaskMapper taskMapper;
    @Mock
    private FormDefinitionMapper formDefinitionMapper;

    private MobileBootstrapController controller;

    @BeforeEach
    void setUp() {
        controller = new MobileBootstrapController(userMapper, taskMapper, formDefinitionMapper);
        PrincipalHolder.set(new PrincipalHolder.Principal(1L, "admin", List.of("user", "admin")));
    }

    @AfterEach
    void tearDown() {
        PrincipalHolder.clear();
    }

    @Test
    void returnsCurrentUserPendingCountAndPublishedApps() {
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        user.setDisplayName("AntFlow Admin");
        FormDefinition form = new FormDefinition();
        form.setId(11L);
        form.setCode("leave");
        form.setName("请假申请");

        when(userMapper.selectById(1L)).thenReturn(user);
        when(taskMapper.selectCount(any())).thenReturn(2L);
        when(formDefinitionMapper.selectList(any())).thenReturn(List.of(form));

        MobileBootstrapDto result = controller.bootstrap();

        assertEquals("admin", result.user().username());
        assertEquals(2, result.pendingCount());
        assertEquals(1, result.favoriteApps().size());
        assertEquals("leave", result.favoriteApps().get(0).code());
        assertEquals("builtin-1", result.brandingVersion());
    }
}
