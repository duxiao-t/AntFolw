package com.antflow.form.runtime;

import com.antflow.engine.BizException;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.runtime.FormData;
import com.antflow.form.runtime.FormDataMapper;
import com.antflow.process.ProcessDefinition;
import com.antflow.process.ProcessDefinitionService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MobileDraftService {
    private static final String DRAFT_STATUS = "DRAFT";
    private static final String PUBLISHED_STATUS = "PUBLISHED";

    private final FormDataMapper formDataMapper;
    private final FormDefinitionService formDefinitionService;
    private final ObjectMapper objectMapper;
    private final AuthorizationService authorizationService;
    @Autowired(required = false)
    private ProcessDefinitionService processDefinitionService;

    @Transactional(rollbackFor = Exception.class)
    public Long create(String formCode, JsonNode data, long userId) {
        FormDefinition formDefinition = requirePublishedForm(formCode);
        // 单公司：草稿不再记录公司归属
        authorizationService.requireFormUse(formDefinition.getId());
        FormData draft = new FormData();
        draft.setFormDefId(formDefinition.getId());
        draft.setFormDefVersion(formDefinition.getVersion());
        draft.setData(writeJson(canonicalData(formDefinition, data)));
        draft.setStatus(DRAFT_STATUS);
        draft.setCreatedBy(userId);
        draft.setUpdatedAt(OffsetDateTime.now());
        formDataMapper.insert(draft);
        return draft.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public FormData update(long draftId, JsonNode data, long userId) {
        FormData draft = requireOwnedDraft(draftId, userId);
        FormDefinition formDefinition = requirePublishedForm(draft.getFormDefId());
        authorizationService.requireFormUse(formDefinition.getId());
        draft.setData(writeJson(canonicalData(formDefinition, data)));
        draft.setUpdatedAt(OffsetDateTime.now());
        formDataMapper.updateById(draft);
        return draft;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(long draftId, long userId) {
        requireOwnedDraft(draftId, userId);
        formDataMapper.deleteById(draftId);
    }

    public List<MobileDraftDto> list(long userId) {
        List<FormData> drafts = formDataMapper.selectMyDrafts(userId);
        DraftContext context = contextFor(drafts, userId);
        return drafts.stream().map(draft -> toDto(draft, context)).toList();
    }

    public long count(long userId) {
        return formDataMapper.countMyDrafts(userId);
    }

    public MobileDraftDto get(long draftId, long userId) {
        FormData draft = requireOwnedDraft(draftId, userId);
        return toDto(draft, contextFor(List.of(draft), userId));
    }

    /**
     * 一次请求内按表单去重的共享上下文。原来 toDto 每张草稿都要查三次
     * （表单定义 / 使用授权 / 已发布流程），草稿列表有多长就乘多少条 SQL。
     */
    private record DraftContext(Map<Long, FormDefinition> forms,
                                java.util.function.Predicate<FormData> usable,
                                Map<Long, Object> processes) { }

    private DraftContext contextFor(List<FormData> drafts, long userId) {
        List<Long> formIds = drafts.stream().map(FormData::getFormDefId)
            .filter(Objects::nonNull).distinct().toList();
        // 定义与流程都按 id 批量取：逐张表单各查一次的话，草稿跨 N 张表单就是 2N 次往返。
        Map<Long, FormDefinition> forms = formDefinitionService.mapByIds(formIds);
        Map<Long, Object> processes = new HashMap<>();
        if (processDefinitionService != null && !formIds.isEmpty()) {
            processDefinitionService.latestPublishedForForms(formIds)
                .forEach((formId, definition) -> processes.put(formId, definition.getProcess()));
        }
        // canUseForm = 运行时能力 + 使用授权；能力这维一次问清，授权这维一次查全。
        java.util.Optional<java.util.Set<Long>> granted =
            authorizationService.hasPermission(userId, PermissionCodes.FORM_RUNTIME_READ)
                ? authorizationService.usableFormIds(userId)
                : java.util.Optional.of(java.util.Set.of());
        java.util.function.Predicate<FormData> usable = granted.isEmpty()
            ? draft -> true
            : draft -> granted.get().contains(draft.getFormDefId());
        return new DraftContext(forms, usable, processes);
    }


    /** 校验草稿可用于预览/填写：所有者、表单一致性与使用授权。 */
    public void requireUsableDraft(long draftId, long formDefId, long userId) {
        java.util.Objects.requireNonNull(requireOwnedDraft(draftId, userId));
        authorizationService.requireFormUse(formDefId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteAfterSubmit(long draftId, long userId) {
        delete(draftId, userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteAfterSubmit(long draftId, long formDefId, long userId) {
        FormData draft = requireOwnedDraft(draftId, userId);
        if (!Objects.equals(draft.getFormDefId(), formDefId)) {
            throw new BizException("BAD_DRAFT", "draft does not belong to requested form");
        }
        formDataMapper.deleteById(draftId);
    }

    private FormData requireOwnedDraft(long draftId, long userId) {
        FormData draft = formDataMapper.selectRuntimeById(draftId);
        if (draft == null) {
            throw new BizException("DRAFT_NOT_FOUND", "draft not found");
        }
        if (!DRAFT_STATUS.equals(draft.getStatus())) {
            throw new BizException("NOT_DRAFT", "draft is not editable");
        }
        if (!Objects.equals(draft.getCreatedBy(), userId)) {
            throw new AccessDeniedException("draft belongs to another user");
        }
        return draft;
    }

    private FormDefinition requirePublishedForm(String formCode) {
        FormDefinition formDefinition = formDefinitionService.getByCode(formCode);
        if (formDefinition == null || !PUBLISHED_STATUS.equals(formDefinition.getStatus())) {
            throw new BizException("FORM_NOT_PUBLISHED", "Form not published: " + formCode);
        }
        return formDefinition;
    }

    private FormDefinition requirePublishedForm(Long formDefId) {
        FormDefinition formDefinition = formDefinitionService.getById(formDefId);
        if (formDefinition == null || !PUBLISHED_STATUS.equals(formDefinition.getStatus())) {
            throw new BizException("FORM_NOT_PUBLISHED", "Form not published: " + formDefId);
        }
        return formDefinition;
    }

    private MobileDraftDto toDto(FormData draft, DraftContext context) {
        FormDefinition formDefinition = context.forms().get(draft.getFormDefId());
        // 单公司：不再有"归属待确认"这种状态
        boolean pending = false;
        boolean readOnly = pending || formDefinition == null
            || !PUBLISHED_STATUS.equals(formDefinition.getStatus())
            || !context.usable().test(draft);
        Object process = formDefinition == null ? null : context.processes().get(draft.getFormDefId());
        JsonNode schema = readJsonArray(formDefinition == null ? null : formDefinition.getSchema());
        return new MobileDraftDto(
            draft.getId(),
            draft.getFormDefId(),
            formDefinition == null ? "" : formDefinition.getCode(),
            formDefinition == null ? "已下线表单" : formDefinition.getName(),
            draft.getFormDefVersion(),
            pending ? objectMapper.createObjectNode()
                : formDefinitionService.projectStarterData(draft.getData(), schema, process),
            pending ? objectMapper.createArrayNode()
                : formDefinitionService.projectStarterSchema(schema, process),
            readOnly,
            draft.getCreatedAt(),
            draft.getUpdatedAt()
        );
    }


    private JsonNode canonicalData(FormDefinition formDefinition, JsonNode data) {
        return objectMapper.valueToTree(formDefinitionService.canonicalizeStarterData(
            formDefinition.getSchema(), data, processOf(formDefinition.getId())));
    }

    private Object processOf(Long formDefId) {
        if (processDefinitionService == null || formDefId == null) return null;
        ProcessDefinition process = processDefinitionService.latestPublishedForForm(formDefId);
        return process == null ? null : process.getProcess();
    }

    private String writeJson(JsonNode data) {
        try {
            return objectMapper.writeValueAsString(data == null
                ? objectMapper.createObjectNode() : data);
        } catch (JsonProcessingException exception) {
            throw new BizException("BAD_JSON", exception.getMessage());
        }
    }

    private JsonNode readJsonObject(String value) {
        if (value == null || value.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new BizException("BAD_JSON", exception.getMessage());
        }
    }

    private JsonNode readJsonArray(String value) {
        if (value == null || value.isBlank()) {
            return objectMapper.createArrayNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new BizException("BAD_SCHEMA_JSON", exception.getMessage());
        }
    }
}
