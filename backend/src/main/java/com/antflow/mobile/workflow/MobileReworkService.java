package com.antflow.mobile.workflow;

import com.antflow.attachment.MobileFileLinkService;
import com.antflow.authz.AuthorizationService;
import com.antflow.engine.BizException;
import com.antflow.engine.ProcessEngine;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.runtime.FormData;
import com.antflow.form.runtime.FormDataMapper;
import com.antflow.process.DefinitionVersionRepository;
import com.antflow.task.ProcessInstance;
import com.antflow.task.ProcessInstanceMapper;
import com.antflow.task.TaskEntity;
import com.antflow.task.TaskMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;

/**
 * 退回修改（返工）的读改写：打开原单、暂存、重提。
 *
 * <p>这一簇的风险全在**状态与锁**上，不在字段映射上：
 * <ul>
 *   <li>读路径必须用**当前修订**的表单 Schema（{@code current_form_revision_id}）而不是表单定义上的最新版；
 *       否则用户改的是 A 版字段、提交时按 B 版校验，重提会莫名其妙报缺字段；</li>
 *   <li>写路径必须对实例与任务都 {@code selectForUpdate}：两者状态在写入期间可能被审批/撤回改掉，
 *       只锁其中一个仍会写出"实例 RUNNING、任务已 APPROVED"这种半截状态；</li>
 *   <li>暂存（validate=false）也把表单数据状态置为 {@code NEEDS_REVISION}，这是前端判断"草稿态"的依据；</li>
 *   <li>{@code saveRework} 保存后**重新读一次**（{@code getReworkTask}）再返回，
 *       这样返回的 schema/data 一定是刚写进去的那份，前端不必二次拉取。</li>
 * </ul>
 *
 * <p>事务边界不在这里，见 {@link MobileTaskCommandService} 的说明。
 */
@RequiredArgsConstructor
final class MobileReworkService {
    private final ProcessEngine engine;
    private final FormDefinitionService formDefinitionService;
    private final FormDataMapper formDataMapper;
    private final ProcessInstanceMapper instanceMapper;
    private final TaskMapper taskMapper;
    private final MobileFileLinkService fileLinkService;
    private final ObjectMapper objectMapper;
    private final AuthorizationService authorizationService;
    private final MobileAttachmentProjector attachments;
    private final MobileEntityLookups lookups;
    private final MobileProjectionSupport projection;
    /** 可选依赖（修订仓库），见门面上的字段注入说明：必须延迟读取。 */
    private final Supplier<DefinitionVersionRepository> definitionVersions;

    ReworkTaskDto getReworkTask(Long taskId, long userId) {
        TaskEntity task = requireOwnedReworkTask(taskId, userId);
        ProcessInstance instance = lookups.requireExistingInstance(task.getProcInstId());
        FormData formData = lookups.requireFormData(instance.getFormDataId());
        FormDefinition form = formDefinitionService.getById(formData.getFormDefId());
        if (form == null) {
            throw new BizException("NOT_FOUND", "form definition not found");
        }
        DefinitionVersionRepository versions = definitionVersions.get();
        String schema = instance.getCurrentFormRevisionId() != null && versions != null
            ? versions.revisionSchema(instance.getCurrentFormRevisionId()) : null;
        String effectiveSchema = schema == null ? form.getSchema() : schema;
        JsonNode schemaTree = projection.readJsonArray(effectiveSchema, "BAD_SCHEMA_JSON");
        JsonNode snapshot = projection.readJsonObject(instance.getProcessSnapshot(), "BAD_FLOW_JSON");
        return new ReworkTaskDto(task.getId(), instance.getId(), form.getCode(), form.getName(),
            formData.getBusinessNo(),
            formDefinitionService.projectStarterSchema(schemaTree, snapshot),
            formDefinitionService.projectStarterData(formData.getData(), schemaTree, snapshot),
            snapshot,
            attachments.starterFiles(formData.getId(), snapshot));
    }

    ReworkTaskDto saveRework(Long taskId, ReworkTaskRequest request, long userId) {
        TaskEntity task = requireOwnedReworkTask(taskId, userId);
        updateRework(task, request, userId, false);
        return getReworkTask(taskId, userId);
    }

    ReworkResult resubmitRework(Long taskId, ReworkTaskRequest request, long userId) {
        TaskEntity task = requireOwnedReworkTask(taskId, userId);
        FormData formData = updateRework(task, request, userId, true);
        List<Long> firstTaskIds = engine.resubmitRework(taskId, userId);
        return new ReworkResult(task.getProcInstId(), formData.getId(), formData.getBusinessNo(),
            firstTaskIds);
    }

    private TaskEntity requireOwnedReworkTask(Long taskId, long userId) {
        TaskEntity task = lookups.requireExistingTask(taskId);
        authorizationService.requireConfirmedInstance(task.getProcInstId());
        if (!"PENDING".equals(task.getStatus()) || !"REWORK".equals(task.getTaskType())) {
            throw new BizException("TASK_NOT_PENDING", "待修改任务已处理");
        }
        if (!Objects.equals(task.getAssigneeId(), userId)) {
            throw new AccessDeniedException("not your rework task");
        }
        return task;
    }

    private FormData updateRework(TaskEntity task, ReworkTaskRequest request, long userId,
                                  boolean validate) {
        if (request == null) {
            throw new BizException("BAD_REQUEST", "表单内容不能为空");
        }
        ProcessInstance instance = instanceMapper.selectForUpdate(task.getProcInstId());
        TaskEntity current = taskMapper.selectForUpdate(task.getId());
        if (instance == null || !"RUNNING".equals(instance.getStatus())
            || current == null || !Objects.equals(current.getProcInstId(), instance.getId())
            || !"PENDING".equals(current.getStatus()) || !"REWORK".equals(current.getTaskType())
            || !Objects.equals(current.getAssigneeId(), userId)) {
            throw new BizException("BAD_REWORK_STATE", "待修改任务已失效，请刷新");
        }
        FormData formData = lookups.requireFormData(instance.getFormDataId());
        JsonNode data = request.data() == null ? objectMapper.createObjectNode() : request.data();
        FormDefinition form = formDefinitionService.getById(formData.getFormDefId());
        if (form == null) {
            throw new BizException("NOT_FOUND", "form definition not found");
        }
        DefinitionVersionRepository versions = definitionVersions.get();
        String schema = instance.getCurrentFormRevisionId() != null
            && versions != null
            ? versions.revisionSchema(instance.getCurrentFormRevisionId()) : null;
        String effectiveSchema = schema == null ? form.getSchema() : schema;
        Map<String, Object> canonicalData =
            formDefinitionService.canonicalizeStarterRevision(effectiveSchema, data,
                formData.getData(), instance.getProcessSnapshot());
        if (validate) {
            formDefinitionService.validateStarterRevision(effectiveSchema, canonicalData,
                instance.getProcessSnapshot());
        }
        formData.setData(projection.writeJson(objectMapper.valueToTree(canonicalData)));
        formData.setStatus("NEEDS_REVISION");
        formDataMapper.updateById(formData);
        if (request.files() != null) {
            Map<String, String> starterModes = formDefinitionService.starterFieldModes(
                instance.getProcessSnapshot());
            fileLinkService.reconcileEditable(formData.getId(), request.files(), userId,
                starterModes);
        }
        return formData;
    }
}
