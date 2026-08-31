package com.antflow.mobile.workflow;

import com.antflow.auth.PrincipalHolder;
import com.antflow.org.User;
import com.antflow.org.UserMapper;
import com.antflow.task.TaskMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
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
    private MobileAppService mobileAppService;
    @Mock
    private MobileWorkflowMapper workflowMapper;

    private MobileBootstrapController controller;
    private MobileBootstrapService service;

    @BeforeEach
    void setUp() {
        service = new MobileBootstrapService(userMapper, taskMapper, mobileAppService,
            workflowMapper);
        controller = new MobileBootstrapController(service);
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
        when(userMapper.selectById(1L)).thenReturn(user);
        when(taskMapper.selectCount(any())).thenReturn(2L);
        when(workflowMapper.countUnreadNotifications(1L)).thenReturn(4L);
        when(mobileAppService.favorites(1L)).thenReturn(List.of(
            new MobileAppDto(11L, "leave", "请假申请", null, "other", "其他", null)));
        when(workflowMapper.selectRecentProcesses(1L, 4)).thenReturn(List.of(
            new RecentProcessDto(21L, "leave", "请假申请", "RUNNING",
                OffsetDateTime.parse("2026-07-30T10:00:00+08:00"))));

        MobileBootstrapDto result = controller.bootstrap();

        assertEquals("admin", result.user().username());
        assertEquals(2, result.pendingCount());
        assertEquals(4, result.unreadNotificationCount());
        assertEquals(1, result.favoriteApps().size());
        assertEquals("leave", result.favoriteApps().get(0).code());
        assertEquals(1, result.recentProcesses().size());
        assertEquals(21L, result.recentProcesses().get(0).instanceId());
        assertEquals("builtin-1", result.brandingVersion());
    }
}
