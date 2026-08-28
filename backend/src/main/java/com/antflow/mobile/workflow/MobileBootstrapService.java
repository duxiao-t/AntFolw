package com.antflow.mobile.workflow;

import com.antflow.org.User;
import com.antflow.org.UserMapper;
import com.antflow.task.TaskEntity;
import com.antflow.task.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.util.List;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MobileBootstrapService {
    private static final int MAX_RECENT_PROCESSES = 4;
    private static final String BUILTIN_BRANDING_VERSION = "builtin-1";

    private final UserMapper userMapper;
    private final TaskMapper taskMapper;
    private final MobileAppService mobileAppService;
    private final MobileWorkflowMapper workflowMapper;

    public MobileBootstrapDto bootstrap(long userId, Collection<String> roles) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new AccessDeniedException("authenticated user no longer exists");
        Long pendingCount = taskMapper.selectCount(new QueryWrapper<TaskEntity>()
            .eq("assignee_id", userId)
            .eq("status", "PENDING"));
        return new MobileBootstrapDto(
            new MobileUserDto(user.getId(), user.getUsername(), user.getDisplayName(),
                List.copyOf(roles)),
            pendingCount.intValue(),
            mobileAppService.favorites(userId),
            workflowMapper.selectRecentProcesses(userId, MAX_RECENT_PROCESSES),
            BUILTIN_BRANDING_VERSION);
    }
}
