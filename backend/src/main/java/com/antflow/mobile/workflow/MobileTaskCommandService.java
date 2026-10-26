package com.antflow.mobile.workflow;

import com.antflow.authz.AuthorizationService;
import com.antflow.engine.BizException;
import com.antflow.engine.ProcessEngine;
import com.antflow.engine.dto.CompleteCmd;
import com.antflow.engine.dto.StartCmd;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.runtime.MobileDraftDto;
import com.antflow.form.runtime.MobileDraftService;
import com.antflow.attachment.MobileFileLinkService;
import com.antflow.process.DefinitionVersionRepository;
import com.antflow.process.ProcessDefinition;
import com.antflow.process.ProcessDefinitionService;
import com.antflow.task.ProcessInstance;
import com.antflow.task.ProcessInstanceMapper;
import com.antflow.task.TaskEntity;
import com.antflow.task.TaskMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;

/**
 * 移动端的写操作：发起流程、审批、驳回、标记已读（含抄送）、撤回。
 *
 * <p>**事务边界不在这里**：{@code @Transactional} 留在 {@link MobileWorkflowService} 的公开入口上，
 * 因为 Agent 时代的调用顺序是"控制器 → 门面（开事务）→ 这里"，本类若再声明一次事务，
 * 在单测里（直接 new、没有代理）不生效、在生产里又多一层加入点，只会让"到底谁开的事务"更难查。
 *
 * <p>三处必须注意的行为：
 * <ul>
 *   <li>草稿发起时的公司归属取**草稿行快照**（{@code companyAttribution.forDraft}），
 *       不是当前身份；调岗后重提不能换归属。这是 P4a 的核心口径；</li>
 *   <li>抄送任务的 id 是 `{@code 8000000000000000 + cc.id}` 拼出来的虚拟 id，
 *       已读接口必须先做区间判断再减基址，否则会把虚拟 id 当成真任务去查；</li>
 *   <li>发起附件的落库要经过 {@code starterEditableFiles} 过滤：
 *       非 EDITABLE 字段即便前端传了也不落，这与详情页的 HIDDEN 过滤是同一套元数据的两面。</li>
 * </ul>
 */
@RequiredArgsConstructor
final class MobileTaskCommandService {
    private static final long CC_TASK_ID_BASE = 8_000_000_000_000_000L;
    private static final String PUBLISHED_STATUS = "PUBLISHED";

    private final ProcessEngine engine;
    private final MobileDraftService draftService;
    private final MobileWorkflowMapper workflowMapper;
    private final FormDefinitionService formDefinitionService;
    private final ProcessDefinitionService processDefinitionService;
    private final ProcessInstanceMapper instanceMapper;
    private final TaskMapper taskMapper;
    private final MobileFileLinkService fileLinkService;
    private final ObjectMapper objectMapper;
    private final AuthorizationService authorizationService;
    private final MobileAttachmentProjector attachments;
    private final MobileEntityLookups lookups;
    /**
     * 表单修订仓库是可选依赖（门面上是 {@code @Autowired(required = false)} 字段注入），
     * 构造期还是 null，因此用 Supplier 延迟读取；直接持引用会让"未启用修订"的分支永远走不到。
     */
    private final Supplier<DefinitionVersionRepository> definitionVersions;

    MobileStartResult start(StartMobileInstanceRequest request, long userId) {
        JsonNode data = request.data() == null ? objectMapper.createObjectNode() : request.data();
        FormDefinition currentForm = formDefinitionService.getByCode(request.formCode());
        if (currentForm == null || !PUBLISHED_STATUS.equals(currentForm.getStatus())) {
            throw new BizException("FORM_NOT_PUBLISHED", "Form not published: " + request.formCode());
        }
        // 单公司：不再解析公司归属，只做草稿与表单的一致性校验。
        if (request.draftId() != null) {
            MobileDraftDto draft = draftService.get(request.draftId(), userId);
            if (!Objects.equals(draft.formCode(), request.formCode())) {
                throw new BizException("BAD_DRAFT", "draft does not belong to requested form");
            }
            if (draft.readOnly()) {
                throw new BizException("BAD_DRAFT", "draft is read only");
            }
            if (draft.formVersion() != currentForm.getVersion()) {
                throw new BizException("DRAFT_VERSION_MISMATCH",
                    "表单已改版，请基于新版本重新填写");
            }
        }
        authorizationService.requireFormUse(currentForm.getId());

        Map<String, List<Long>> selfSelected = request.selfSelected() == null
            ? Map.of() : request.selfSelected();
        Map<String, Object> result = engine.start(
            new StartCmd(request.formCode(), data, selfSelected, null), userId);
        Long formDataId = asLong(result.get("formDataId"));
        Long instanceId = asLong(result.get("instanceId"));
        String businessNo = Objects.toString(result.get("businessNo"), null);
        List<Long> firstTaskIds = asLongList(result.get("firstTaskIds"));

        ProcessDefinition currentProcess =
            processDefinitionService.latestPublishedForForm(currentForm.getId());
        Map<String, String> starterModes = formDefinitionService.starterFieldModes(
            currentProcess == null ? null : currentProcess.getProcess());
        fileLinkService.append(formDataId,
            MobileAttachmentProjector.starterEditableFiles(
                MobileAttachmentProjector.filesOf(request), starterModes), userId);
        DefinitionVersionRepository versions = definitionVersions.get();
        if (versions != null) {
            ProcessInstance started = instanceMapper.selectById(instanceId);
            if (started != null && started.getCurrentFormRevisionId() != null) {
                versions.syncRevisionFiles(started.getCurrentFormRevisionId(), formDataId);
            }
        }
        if (request.draftId() != null) {
            draftService.deleteAfterSubmit(request.draftId(), userId);
        }
        return new MobileStartResult(instanceId, formDataId, businessNo, firstTaskIds);
    }

    void markTaskRead(Long taskId, long userId) {
        if (taskId != null && taskId >= CC_TASK_ID_BASE) {
            int updated = workflowMapper.markCcRead(taskId - CC_TASK_ID_BASE, userId);
            if (updated == 0) throw new AccessDeniedException("not your task");
            return;
        }
        TaskEntity task = lookups.requireExistingTask(taskId);
        authorizationService.requireConfirmedInstance(task.getProcInstId());
        if (!Objects.equals(task.getAssigneeId(), userId)) {
            throw new AccessDeniedException("not your task");
        }
        if (!"CC".equals(task.getStatus()) || task.getReadAt() != null) return;
        task.setReadAt(OffsetDateTime.now());
        taskMapper.updateById(task);
    }

    void approve(Long taskId, MobileTaskActionRequest request, long userId) {
        Object data = request == null || request.data() == null
            ? null : objectMapper.convertValue(request.data(), Map.class);
        engine.approve(new CompleteCmd(taskId, "APPROVE",
            request == null ? null : request.comment(), null, data), userId);
    }

    void reject(Long taskId, MobileTaskActionRequest request, long userId) {
        engine.reject(new CompleteCmd(taskId, "REJECT",
            request == null ? null : request.comment(),
            request == null ? null : request.rejectToNodeId()), userId);
    }

    void withdraw(Long instanceId, long userId) {
        engine.withdraw(instanceId, userId);
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new BizException("BAD_ENGINE_RESULT", "engine result is missing numeric id");
    }

    private static List<Long> asLongList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .filter(Number.class::isInstance)
            .map(Number.class::cast)
            .map(Number::longValue)
            .toList();
    }
}
