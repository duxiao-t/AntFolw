package com.antflow.engine;

import com.antflow.engine.dto.CompleteCmd;
import com.antflow.engine.tree.ProcessTreeNav;
import com.antflow.form.runtime.FormData;
import com.antflow.form.runtime.FormDataMapper;
import com.antflow.notify.NotificationEvent;
import com.antflow.notify.NotificationPublisher;
import com.antflow.task.ProcessInstance;
import com.antflow.task.ProcessInstanceMapper;
import com.antflow.task.TaskEntity;
import com.antflow.task.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * V1（{@code engine_version != 2}）的驳回结算：算会签票、把实例退回到上一级审批人或发起人修改。
 *
 * <p>V1 是只读兼容层——新实例一律 {@code engineVersion=2}（写入点唯一，见 ProcessEngine.start），
 * 所以这里只服务历史实例。方法体与拆分前逐字一致。
 *
 * <p>三个易错点：
 * <ol>
 *   <li>**不能加 {@code @Transactional}**：它跑在 ProcessEngine.reject/approve 的事务里，
 *       自己开事务会让"任务状态 + 实例状态 + 历史"分成两段提交。</li>
 *   <li>会签（ALL/RATIO）的"未通过"不是直接退，而是先找**第一个被驳回的任务**再走同一条退回路径，
 *       否则驳回人会被记成最后一个投票的人。</li>
 *   <li>通知事件的 source 现在是本类实例（原来传的是 ProcessEngine）。source 只被
 *       {@code ApplicationEvent} 持有，全仓没有读取方（见 NotificationEvent 的用法），
 *       类型、接收人、文案都没变。</li>
 * </ol>
 */
final class LegacyDecisionSettler {

    private final EngineSupport support;
    private final TaskMapper taskMapper;
    private final ProcessInstanceMapper processInstanceMapper;
    private final FormDataMapper formDataMapper;
    private final NotificationPublisher notifier;
    private final FlowAdvancer flow;

    LegacyDecisionSettler(EngineSupport support, TaskMapper taskMapper,
                          ProcessInstanceMapper processInstanceMapper,
                          FormDataMapper formDataMapper, NotificationPublisher notifier,
                          FlowAdvancer flow) {
        this.support = support;
        this.taskMapper = taskMapper;
        this.processInstanceMapper = processInstanceMapper;
        this.formDataMapper = formDataMapper;
        this.notifier = notifier;
        this.flow = flow;
    }

    /**
     * 同一节点同一批（按 created_at 识别）的会签票。
     * created_at 是这一批的"批次号"：节点被退回重审后会再建一批新任务，
     * 只按 node_id 查会把上一轮的票算进来。
     */
    List<TaskEntity> legacyNodeVotes(TaskEntity acted) {
        List<TaskEntity> votes = taskMapper.selectList(new QueryWrapper<TaskEntity>()
            .eq("proc_inst_id", acted.getProcInstId())
            .eq("node_id", acted.getNodeId())
            .eq("task_type", "APPROVAL")
            .eq("created_at", acted.getCreatedAt())
            .in("status", List.of("PENDING", "APPROVED", "REJECTED"))
            .orderByAsc("id"));
        return votes.isEmpty() ? List.of(acted) : votes;
    }

    static int requiredApprovals(int total, JsonNode node) {
        int ratio = node.path("props").path("ratio").asInt(
            node.path("props").path("passRatio").asInt(100));
        return Math.max(1,
            (int) Math.ceil(total * Math.min(100, Math.max(1, ratio)) / 100.0));
    }

    void settleLegacyRejection(CompleteCmd command, long operatorId, boolean force,
                               TaskEntity task, ProcessInstance instance, JsonNode root) {
        List<TaskEntity> siblings;
        if (task.getParallelId() != null) {
            siblings = taskMapper.selectList(new QueryWrapper<TaskEntity>()
                .eq("proc_inst_id", instance.getId()).eq("status", "PENDING")
                .eq("parallel_id", task.getParallelId()).ne("id", task.getId()));
        } else {
            siblings = taskMapper.selectList(new QueryWrapper<TaskEntity>()
                .eq("proc_inst_id", instance.getId()).eq("status", "PENDING")
                .eq("node_id", task.getNodeId()).ne("id", task.getId()));
        }
        for (TaskEntity sibling : siblings) {
            sibling.setStatus("SKIPPED");
            taskMapper.updateById(sibling);
            support.insertHistoryOnInstance(instance.getId(), task.getNodeId(), sibling.getNodeId(),
                "SKIP", operatorId, null);
        }

        if (force && command.rejectToNodeId() != null
            && !command.rejectToNodeId().isBlank()) {
            JsonNode target = ProcessTreeNav.findById(root, command.rejectToNodeId());
            if (target == null || !"APPROVAL".equals(target.path("type").asText())
                || ProcessTreeNav.isInsideParallelBranch(root, command.rejectToNodeId())) {
                throw new BizException("BAD_REJECT_TARGET", "reject target is not an approval node");
            }
            instance.setStatus("RUNNING");
            instance.setFinishedAt(null);
            instance.setCurrentNodeId(target.path("id").asText());
            processInstanceMapper.updateById(instance);
            support.insertHistory(task, task.getNodeId(), target.path("id").asText(),
                "FORCE_REJECT", operatorId, command.comment());
            flow.resolveAndLandFromNode(root, instance, support.readFormData(instance.getFormDataId()),
                instance.getStartedBy(), Map.of(), target);
            return;
        }

        TaskEntity previous = support.previousApproval(task);
        List<TaskEntity> returnedTasks = previous == null
            ? List.of(EngineSupport.newReturnedTask(instance.getId(), "__rework__", "REWORK", "OR_SIGN",
                instance.getStartedBy(), null, null))
            : support.previousNodeAssignees(previous).stream()
                .map(assigneeId -> EngineSupport.newReturnedTask(instance.getId(), previous.getNodeId(),
                    "APPROVAL", previous.getApprovalMode(), assigneeId, null, null))
                .toList();
        String returnedNodeId = previous == null ? "__rework__" : previous.getNodeId();
        if (returnedTasks.isEmpty()) {
            throw new BizException("BAD_FLOW", "previous approval has no assignee to return to");
        }
        for (TaskEntity returned : returnedTasks) taskMapper.insert(returned);

        instance.setStatus("RUNNING");
        instance.setFinishedAt(null);
        instance.setCurrentNodeId(returnedNodeId);
        processInstanceMapper.updateById(instance);
        support.insertHistory(task, task.getNodeId(), returnedNodeId,
            force ? "FORCE_REJECT" : "REJECT", operatorId, command.comment());

        if (previous == null) {
            FormData formData = formDataMapper.selectRuntimeById(instance.getFormDataId());
            if (formData == null) throw new BizException("NOT_FOUND", "form data not found");
            formData.setStatus("NEEDS_REVISION");
            formDataMapper.updateById(formData);
        }
        for (TaskEntity returned : returnedTasks) {
            notifier.publish(new NotificationEvent(this, "TASK_RETURNED",
                instance.getId(), returned.getId(), returned.getAssigneeId(),
                previous == null ? "申请已退回修改" : "审批已退回上一级"));
        }
    }
}
