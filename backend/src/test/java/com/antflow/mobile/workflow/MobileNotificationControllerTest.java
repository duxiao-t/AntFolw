package com.antflow.mobile.workflow;

import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MobileNotificationControllerTest {
    @Mock private MobileWorkflowMapper mapper;
    @Mock private AuthorizationService authorization;
    private MobileNotificationController controller;

    @BeforeEach
    void setUp() {
        controller = new MobileNotificationController(mapper, authorization);
        PrincipalHolder.set(new PrincipalHolder.Principal(7L, "reader", List.of("user")));
    }

    @AfterEach
    void tearDown() {
        PrincipalHolder.clear();
    }

    @Test
    void listsOnlyTheCurrentUsersNotificationsAndMarksThemRead() {
        var row = new MobileWorkflowMapper.NotificationRow(12L, "APPROVAL_INVALIDATED",
            "您的审批已作废", 31L, 41L, OffsetDateTime.now(), null);
        when(mapper.selectNotifications(7L, true, 3, 0)).thenReturn(List.of(row));
        when(mapper.countUnreadNotifications(7L)).thenReturn(1L);
        when(mapper.markNotificationRead(12L, 7L)).thenReturn(1);

        var page = controller.notifications(1, 2, true);
        controller.markRead(12L);

        assertThat(page.items()).containsExactly(row);
        assertThat(page.unreadCount()).isEqualTo(1);
        assertThat(page.hasMore()).isFalse();
        verify(authorization, times(2)).requirePermission(PermissionCodes.WORKFLOW_TASK_READ);
        verify(mapper).markNotificationRead(12L, 7L);
    }
}
