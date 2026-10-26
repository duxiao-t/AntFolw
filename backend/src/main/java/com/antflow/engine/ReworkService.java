package com.antflow.engine;

import com.antflow.form.FormDefinitionService;
import com.antflow.form.runtime.FormData;
import com.antflow.form.runtime.FormDataMapper;
import com.antflow.process.RevisionReasons;
import com.antflow.task.ProcessInstance;
import com.antflow.task.ProcessInstanceMapper;
import com.antflow.task.TaskEntity;
import com.antflow.task.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 返工单（REWORK 任务）的重新提交：把单据改回 SUBMITTED，并按上一轮选过的人重新落地任务。
 *
 * <p>**锁留在 ProcessEngine**：{@code lockPendingTask} 决定的是"谁能动这个任务"，
 * 属于入口编排；这里只接收已经锁好的 task/instance，避免把加锁顺序搬散。
 *
 * <p>方法体与拆分前逐字一致，三个易错处：
 * <ol>
 *   <li>**不能加 {@code @Transactional}**：调用方 {@code ProcessEngine.resubmitRework} 已经开事务，
 *       这里再开一层会让"任务 RESUBMITTED + 表单 SUBMITTED + 新任务"分段提交。</li>
 *   <li>{@code previousSelections} 用的是**全部历史 APPROVAL 任务**（不按轮次过滤），
 *       这是自选审批人的兜底：换成"上一轮"会让人数在返工后缩水。</li>
 *   <li>{@code beginRound} 必须在改任务状态**之前**，因为运行时会按当前轮次写审计；
 *       顺序动了，集成测试里轮次号会对不上。</li>
 * </ol>
 */
final class ReworkService {

    private final EngineSupport support;
    private final TaskMapper taskMapper;
    private final ProcessInstanceMapper processInstanceMapper;
    private final FormDataMapper formDataMapper;
    private final FormDefinitionService formDefinitionService;
    private final com.antflow.attachment.MediaSubmissionValidator mediaValidator;
    private final Supplier<WorkflowRuntimeV2> runtimeV2;
    private final FlowAdvancer flow;

    ReworkService(EngineSupport support, TaskMapper taskMapper,
                  ProcessInstanceMapper processInstanceMapper, FormDataMapper formDataMapper,
                  FormDefinitionService formDefinitionService,
                  com.antflow.attachment.MediaSubmissionValidator mediaValidator,
                  Supplier<WorkflowRuntimeV2> runtimeV2, FlowAdvancer flow) {
        this.support = support;
        this.taskMapper = taskMapper;
        this.processInstanceMapper = processInstanceMapper;
        this.formDataMapper = formDataMapper;
        this.formDefinitionService = formDefinitionService;
        this.mediaValidator = mediaValidator;
        this.runtimeV2 = runtimeV2;
        this.flow = flow;
    }

    /** @param task 已由调用方锁定、且已校验过归属的 REWORK 任务 */
    List<Long> resubmit(TaskEntity task, ProcessInstance instance, long operatorId) {
        WorkflowRuntimeV2 v2 = runtimeV2.get();
        FormData formDataRow = formDataMapper.selectRuntimeById(instance.getFormDataId());
        mediaValidator.validate(instance.getFormDataId(),
            formDefinitionService.starterFieldModes(instance.getProcessSnapshot()));
        if (formDataRow == null) {
            throw new BizException("NOT_FOUND", "form data not found");
        }

        task.setStatus("RESUBMITTED");
        task.setApprovedBy(operatorId);
        task.setApprovedAt(OffsetDateTime.now());
        task.setComment("修改后重新提交");
        if (v2 != null && v2.active(instance)) {
            v2.beginRound(instance, "RESUBMIT");
        }
        support.updateTaskOrConflict(task);
        formDataRow.setStatus("SUBMITTED");
        formDataMapper.updateById(formDataRow);
        if (v2 != null && v2.active(instance)) {
            long revisionId = v2.createRevision(formDataRow, "SUBMITTED",
                RevisionReasons.RESUBMIT, operatorId);
            instance.setCurrentFormRevisionId(revisionId);
            task.setActionFormRevisionId(revisionId);
            taskMapper.updateById(task);
        }

        JsonNode root = support.readProcessTree(instance);
        JsonNode formData = support.readTreeOrEmpty(formDataRow.getData());
        Map<String, List<Long>> previousSelections = taskMapper.selectList(
                new QueryWrapper<TaskEntity>()
                    .eq("proc_inst_id", instance.getId())
                    .eq("task_type", "APPROVAL")
                    .orderByAsc("id"))
            .stream()
            .collect(Collectors.groupingBy(TaskEntity::getNodeId, LinkedHashMap::new,
                Collectors.mapping(TaskEntity::getAssigneeId,
                    Collectors.collectingAndThen(Collectors.toList(), values -> values.stream()
                        .distinct().toList()))));

        instance.setStatus("RUNNING");
        instance.setFinishedAt(null);
        processInstanceMapper.updateById(instance);
        List<Long> taskIds = flow.resolveAndLand(root, instance, formData, instance.getStartedBy(),
            previousSelections, root);
        support.insertHistory(task, "__rework__", root.path("id").asText(null), "RESUBMIT",
            operatorId, task.getComment());
        return taskIds;
    }
}
