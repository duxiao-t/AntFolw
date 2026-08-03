package com.antflow.mobile.workflow;

import com.antflow.auth.PrincipalHolder;
import com.antflow.org.User;
import com.antflow.org.UserMapper;
import com.antflow.task.TaskEntity;
import com.antflow.task.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/mobile")
@RequiredArgsConstructor
public class MobileBootstrapController {
    private static final int MAX_RECENT_PROCESSES = 4;
    private static final String PENDING_STATUS = "PENDING";
    private static final String BUILTIN_BRANDING_VERSION = "builtin-1";

    private final UserMapper userMapper;
    private final TaskMapper taskMapper;
    private final MobileAppService mobileAppService;
    private final MobileWorkflowMapper workflowMapper;

    @GetMapping("/bootstrap")
    public MobileBootstrapDto bootstrap() {
        PrincipalHolder.Principal principal = principal();
        User user = userMapper.selectById(principal.userId());
        if (user == null) {
            throw new AccessDeniedException("authenticated user no longer exists");
        }

        Long pendingCount = taskMapper.selectCount(new QueryWrapper<TaskEntity>()
            .eq("assignee_id", principal.userId())
            .eq("status", PENDING_STATUS));
        List<MobileAppDto> favoriteApps = mobileAppService.favorites(principal.userId());
        List<RecentProcessDto> recentProcesses = workflowMapper.selectRecentProcesses(
            principal.userId(), MAX_RECENT_PROCESSES);

        MobileUserDto mobileUser = new MobileUserDto(
            user.getId(), user.getUsername(), user.getDisplayName(), principal.roles());
        return new MobileBootstrapDto(
            mobileUser,
            pendingCount.intValue(),
            favoriteApps,
            recentProcesses,
            BUILTIN_BRANDING_VERSION);
    }

    private static PrincipalHolder.Principal principal() {
        return PrincipalHolder.current()
            .orElseThrow(() -> new AccessDeniedException("authentication required"));
    }
}

record MobileBootstrapDto(MobileUserDto user, int pendingCount,
                          List<MobileAppDto> favoriteApps,
                          List<RecentProcessDto> recentProcesses,
                          String brandingVersion) {
}

record MobileUserDto(Long id, String username, String displayName, List<String> roles) {
}

record MobileAppDto(Long formId, String code, String name, String iconUrl,
                    String category, String categoryLabel, String description) {
}

record RecentProcessDto(Long instanceId, String formCode, String formTitle,
                        String status, java.time.OffsetDateTime updatedAt) {
}
