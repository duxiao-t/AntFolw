package com.antflow.mobile.workflow;

import com.antflow.form.runtime.MobileDraftService;

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
        long pendingCount = taskMapper.countPendingInbox(userId);
        return new MobileBootstrapDto(
            new MobileUserDto(user.getId(), user.getUsername(), user.getDisplayName(),
                departmentName(user), user.getDeptId(), user.getEmployeeNo(),
                List.copyOf(roles)),
            Math.toIntExact(pendingCount + workflowMapper.countUnreadCc(userId)),
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
