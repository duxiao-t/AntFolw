package com.antflow.mobile.workflow;

import com.antflow.org.User;
import com.antflow.org.UserMapper;
import com.antflow.org.DepartmentMapper;
import com.antflow.task.TaskEntity;
import com.antflow.task.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.util.List;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
@RequiredArgsConstructor
public class MobileBootstrapService {
    private static final int MAX_RECENT_PROCESSES = 4;
    private static final String BUILTIN_BRANDING_VERSION = "builtin-1";

    private final UserMapper userMapper;
    private final DepartmentMapper departmentMapper;
    private final TaskMapper taskMapper;
    private final MobileAppService mobileAppService;
    private final MobileWorkflowMapper workflowMapper;
    @Autowired(required = false)
    private MobileDraftService draftService;

    public MobileBootstrapDto bootstrap(long userId, Collection<String> roles) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new AccessDeniedException("authenticated user no longer exists");
        Long pendingCount = taskMapper.selectCount(new QueryWrapper<TaskEntity>()
            .eq("assignee_id", userId)
            .and(query -> query.eq("status", "PENDING")
                .or(cc -> cc.eq("status", "CC").isNull("read_at"))));
        return new MobileBootstrapDto(
            new MobileUserDto(user.getId(), user.getUsername(), user.getDisplayName(),
                departmentName(user), user.getEmployeeNo(),
                List.copyOf(roles)),
            pendingCount.intValue() + Math.toIntExact(workflowMapper.countUnreadCc(userId)),
            draftService == null ? 0 : Math.toIntExact(draftService.count(userId)),
            Math.toIntExact(workflowMapper.countUnreadNotifications(userId)),
            mobileAppService.favorites(userId),
            workflowMapper.selectRecentProcesses(userId, MAX_RECENT_PROCESSES),
            BUILTIN_BRANDING_VERSION);
    }

    private String departmentName(User user) {
        if (user.getDeptId() == null) return null;
        com.antflow.org.Department department = departmentMapper.selectById(user.getDeptId());
        return department == null ? null : department.getName();
    }
}
