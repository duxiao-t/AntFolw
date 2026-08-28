package com.antflow.workplace;

import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionMapper;
import com.antflow.org.User;
import com.antflow.org.UserMapper;
import com.antflow.process.ProcessDefinition;
import com.antflow.process.ProcessDefinitionMapper;
import com.antflow.task.ProcessInstance;
import com.antflow.task.ProcessInstanceMapper;
import com.antflow.task.TaskEntity;
import com.antflow.task.TaskOperationService;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The small, permission-aware read model used by the desktop operations home. */
@RestController
@RequestMapping("/api/workplace")
@RequiredArgsConstructor
public class WorkplaceController {
    private static final int ITEM_LIMIT = 8;

    private final AuthorizationService authorizationService;
    private final TaskOperationService taskOperationService;
    private final ProcessInstanceMapper instanceMapper;
    private final ProcessDefinitionMapper processDefinitionMapper;
    private final FormDefinitionMapper formDefinitionMapper;
    private final UserMapper userMapper;

    @GetMapping("/overview")
    public Overview overview() {
        authorizationService.requirePermission(PermissionCodes.PAGE_WORKPLACE);
        authorizationService.requirePermission(PermissionCodes.WORKFLOW_TASK_READ);
        long userId = PrincipalHolder.current().orElseThrow().userId();
        boolean canReadInstances = authorizationService.hasPermission(
            PermissionCodes.WORKFLOW_INSTANCE_READ);
        boolean canSeeTasks = authorizationService.hasPermission(PermissionCodes.WORKFLOW_TASK_READ);

        List<TaskEntity> pendingTasks = canSeeTasks
            ? taskOperationService.listMyInbox(userId, "PENDING")
            : List.of();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        OffsetDateTime dayStart = today.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime dayEnd = today.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        boolean admin = authorizationService.isAdmin();
        List<ProcessInstance> recentInstances = instanceMapper.selectWorkplaceRecent(
            userId, admin, canReadInstances, ITEM_LIMIT);
        Set<Long> pendingInstanceIds = pendingTasks.stream().map(TaskEntity::getProcInstId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<ProcessInstance> pendingInstances = pendingInstanceIds.isEmpty() ? List.of()
            : instanceMapper.selectBatchIds(pendingInstanceIds);

        Map<Long, ProcessDefinition> processDefinitions = new LinkedHashMap<>();
        Map<Long, FormDefinition> forms = new LinkedHashMap<>();
        Map<Long, User> users = new LinkedHashMap<>();
        Map<Long, ProcessInstance> instancesById = new LinkedHashMap<>();
        pendingInstances.forEach(instance -> instancesById.put(instance.getId(), instance));
        recentInstances.forEach(instance -> instancesById.put(instance.getId(), instance));

        loadLabels(instancesById.values(), processDefinitions, forms, users);

        List<PendingTaskItem> pendingItems = new ArrayList<>();
        for (TaskEntity task : pendingTasks) {
            ProcessInstance instance = instancesById.get(task.getProcInstId());
            if (instance == null) {
                continue;
            }
            if (pendingItems.size() == ITEM_LIMIT) {
                break;
            }
            pendingItems.add(new PendingTaskItem(
                task.getId(), instance.getId(), instance.getId(), formName(instance, processDefinitions, forms),
                instance.getStartedBy(), userName(instance.getStartedBy(), users), task.getNodeId(),
                task.getStatus(), task.getCreatedAt()));
        }

        Map<String, Long> statusBreakdown = new LinkedHashMap<>();
        statusBreakdown.put("RUNNING", 0L);
        statusBreakdown.put("APPROVED", 0L);
        statusBreakdown.put("REJECTED", 0L);
        statusBreakdown.put("WITHDRAWN", 0L);
        long completedToday = 0;
        long rejectedToday = 0;
        for (Map<String, Object> row : instanceMapper.selectWorkplaceStatusCounts(
            userId, admin, canReadInstances, dayStart, dayEnd)) {
            String status = String.valueOf(row.get("status"));
            long total = ((Number) row.getOrDefault("total", 0L)).longValue();
            long finishedToday = ((Number) row.getOrDefault("finished_today", 0L)).longValue();
            statusBreakdown.put(status, total);
            if ("APPROVED".equals(status)) completedToday = finishedToday;
            if ("REJECTED".equals(status)) rejectedToday = finishedToday;
        }

        List<RecentInstanceItem> recentItems = recentInstances.stream()
            .map(instance -> new RecentInstanceItem(
                instance.getId(), formName(instance, processDefinitions, forms), instance.getStartedBy(),
                userName(instance.getStartedBy(), users), instance.getStatus(), instance.getCurrentNodeId(),
                instance.getStartedAt(), instance.getFinishedAt() == null
                    ? instance.getStartedAt() : instance.getFinishedAt()))
            .toList();

        return new Overview(
            pendingTasks.size(),
            statusBreakdown.getOrDefault("RUNNING", 0L),
            completedToday,
            rejectedToday,
            pendingItems,
            recentItems,
            statusBreakdown);
    }

    private String formName(ProcessInstance instance,
                             Map<Long, ProcessDefinition> processDefinitions,
                             Map<Long, FormDefinition> forms) {
        if (instance.getProcDefId() == null) {
            return "未命名流程";
        }
        ProcessDefinition process = processDefinitions.computeIfAbsent(
            instance.getProcDefId(), processDefinitionMapper::selectById);
        if (process == null || process.getFormDefId() == null) {
            return "流程 #" + instance.getId();
        }
        FormDefinition form = forms.computeIfAbsent(process.getFormDefId(), formDefinitionMapper::selectById);
        return form == null || form.getName() == null || form.getName().isBlank()
            ? "流程 #" + instance.getId() : form.getName();
    }

    private String userName(Long userId, Map<Long, User> users) {
        if (userId == null) {
            return "未知发起人";
        }
        User user = users.computeIfAbsent(userId, userMapper::selectById);
        return user == null || user.getDisplayName() == null || user.getDisplayName().isBlank()
            ? "用户 #" + userId : user.getDisplayName();
    }

    private void loadLabels(java.util.Collection<ProcessInstance> instances,
                            Map<Long, ProcessDefinition> processes,
                            Map<Long, FormDefinition> forms,
                            Map<Long, User> users) {
        Set<Long> processIds = instances.stream().map(ProcessInstance::getProcDefId)
            .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        if (!processIds.isEmpty()) {
            processDefinitionMapper.selectBatchIds(processIds)
                .forEach(process -> processes.put(process.getId(), process));
        }
        Set<Long> formIds = processes.values().stream().map(ProcessDefinition::getFormDefId)
            .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        if (!formIds.isEmpty()) {
            formDefinitionMapper.selectBatchIds(formIds)
                .forEach(form -> forms.put(form.getId(), form));
        }
        Set<Long> userIds = instances.stream().map(ProcessInstance::getStartedBy)
            .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        if (!userIds.isEmpty()) {
            userMapper.selectBatchIds(userIds).forEach(user -> users.put(user.getId(), user));
        }
    }

    public record Overview(
        long pendingTasks,
        long runningInstances,
        long completedToday,
        long rejectedToday,
        List<PendingTaskItem> pendingTaskItems,
        List<RecentInstanceItem> recentInstanceItems,
        Map<String, Long> statusBreakdown) { }

    public record PendingTaskItem(
        Long taskId,
        Long instanceId,
        Long procInstId,
        String formName,
        Long applicantId,
        String applicantName,
        String nodeId,
        String status,
        OffsetDateTime createdAt) { }

    public record RecentInstanceItem(
        Long instanceId,
        String formName,
        Long startedById,
        String startedByName,
        String status,
        String currentNodeId,
        OffsetDateTime startedAt,
        OffsetDateTime updatedAt) { }
}
