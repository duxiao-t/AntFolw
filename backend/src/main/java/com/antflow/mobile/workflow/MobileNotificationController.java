package com.antflow.mobile.workflow;

import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.engine.BizException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mobile/notifications")
@RequiredArgsConstructor
public class MobileNotificationController {
    private final MobileWorkflowMapper mapper;
    private final AuthorizationService authorization;

    @GetMapping
    public NotificationPage notifications(@RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int pageSize,
                                          @RequestParam(defaultValue = "false") boolean unreadOnly) {
        long userId = userId();
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, pageSize));
        List<MobileWorkflowMapper.NotificationRow> rows = mapper.selectNotifications(
            userId, unreadOnly, safeSize + 1, (safePage - 1) * safeSize);
        return new NotificationPage(rows.stream().limit(safeSize).toList(),
            rows.size() > safeSize, Math.toIntExact(mapper.countUnreadNotifications(userId)));
    }

    @PostMapping("/{id}/read")
    public void markRead(@PathVariable long id) {
        long userId = userId();
        if (mapper.markNotificationRead(id, userId) == 0) {
            throw new BizException("NOT_FOUND", "notification not found");
        }
    }

    private long userId() {
        authorization.requirePermission(PermissionCodes.WORKFLOW_TASK_READ);
        return PrincipalHolder.current().orElseThrow().userId();
    }

    record NotificationPage(List<MobileWorkflowMapper.NotificationRow> items,
                            boolean hasMore, int unreadCount) {
    }
}
