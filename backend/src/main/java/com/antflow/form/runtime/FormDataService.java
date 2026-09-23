package com.antflow.form.runtime;

import com.antflow.common.FormalNumberService;
import com.antflow.common.BusinessNumberService;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.engine.BizException;
import com.antflow.process.DefinitionVersionRepository;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionMapper;
import com.antflow.form.FormDefinitionService;
import com.antflow.org.UserMapper;
import com.antflow.mobile.workflow.MobileFileLinkService;
import com.antflow.mobile.workflow.MobileFileRef;
import com.antflow.mobile.workflow.MobileDraftService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FormDataService {
    private final FormDataMapper mapper;
    private final FormDefinitionService formDefinitionService;
    private final ObjectMapper json;
    private final FormalNumberService formalNumberService;
    @Autowired(required = false)
    private BusinessNumberService businessNumbers;
    private final AuthorizationService authorizationService;
    private final UserMapper userMapper;
    private final FormDefinitionMapper formDefinitionMapper;
    private final MobileFileLinkService fileLinkService;
    private final MobileDraftService draftService;
    /** 可选注入：老的单测直接 new 本类，不给它传这个依赖。 */
    @Autowired(required = false)
    private DefinitionVersionRepository versions;

    /**
     * MVP demo — independent submission (DRAFT or SUBMITTED) outside the workflow engine.
     * Production: process instances must flow through {@code engine.start(...)}.
     */
    @Transactional
    public Long submit(String formCode, String status, Object data, Long userId) {
        return submit(formCode, status, data, userId, List.of()).dataId();
    }

    @Transactional
    public SubmitResult submit(String formCode, String status, Object data, Long userId,
                       List<MobileFileRef> files) {
        return submit(formCode, status, data, userId, files, null);
    }

    @Transactional
    public SubmitResult submit(String formCode, String status, Object data, Long userId,
                       List<MobileFileRef> files, Long draftId) {
        FormDefinition fd = formDefinitionService.getByCode(formCode);
        if (fd == null || !"PUBLISHED".equals(fd.getStatus())) {
            throw new BizException("FORM_NOT_PUBLISHED", "Form not published: " + formCode);
        }
        if (userId == null || authorizationService.currentUserId() != userId) {
            throw new AccessDeniedException("submission user does not match current principal");
        }
        authorizationService.requireFormUse(fd.getId());
        String normalizedStatus = status == null ? "SUBMITTED" : status;
        // 挂了已发布流程的表单只能走引擎发起。否则直接提交会造出 status=SUBMITTED、却没有
        // t_process_instance/审批任务的记录——桌面 /api/forms/data 与移动 /api/mobile/submissions
        // 都调这里，客户端只是"按 settings.workflowEnabled 自己选路"，服务端不兜底就能被绕过。
        if (!"DRAFT".equals(normalizedStatus) && versions != null
                && versions.hasPublishedProcess(fd.getId())) {
            throw new BizException("FORM_HAS_PROCESS",
                "该表单已启用审批流程，请从发起入口提交");
        }
        formDefinitionService.validateSubmission(fd.getSchema(), data);
        Object storedData = "DRAFT".equals(normalizedStatus)
            ? data
            : formDefinitionService.filterVisibleSubmission(fd.getSchema(), data);
        var fd2 = new FormData();
        fd2.setFormDefId(fd.getId());
        fd2.setFormDefVersion(fd.getVersion());
        if (!"DRAFT".equals(normalizedStatus)) {
            fd2.setBusinessNo(businessNumbers == null
                ? formalNumberService.businessNo() : businessNumbers.next(fd, storedData));
        }
        fd2.setData(writeJson(storedData));
        fd2.setStatus(normalizedStatus);
        fd2.setCreatedBy(userId);
        mapper.insert(fd2);
        fileLinkService.append(fd2.getId(), files, userId);
        if (draftId != null) draftService.deleteAfterSubmit(draftId, fd.getId(), userId);
        return new SubmitResult(fd2.getId(), fd2.getBusinessNo());
    }

    public record SubmitResult(Long dataId, String businessNo) { }

    public List<FormData> mySubmissions(Long userId, String formCode) {
        Long formDefId = null;
        if (formCode != null) {
            var fd = formDefinitionService.getByCode(formCode);
            if (fd != null) formDefId = fd.getId();
        }
        return mapper.selectMySubmissions(userId, formDefId);
    }

    public Page<FormData> adminPage(long page, long size, Long formDefId,
                                    String status, Long createdBy) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), 100);
        var q = new QueryWrapper<FormData>();
        if (formDefId != null) q.eq("form_def_id", formDefId);
        if (status != null && !status.isBlank()) q.eq("status", status);
        if (createdBy != null) q.eq("created_by", createdBy);
        q.orderByDesc("created_at").orderByDesc("id");
        return enrichAdminPage(mapper.selectPage(Page.of(safePage, safeSize), q));
    }

    public Page<FormData> authorizedPage(long page, long size, Long formDefId,
                                         String status, Long createdBy,
                                         long userId, boolean admin) {
        // 非 admin 的能力数据范围由 DataPermissionPolicyHandler 在 SQL 层注入，
        // 这里统一走 SQL 分页，避免把整表读进内存再过滤。
        return adminPage(page, size, formDefId, status, createdBy);
    }

    public FormData getById(Long id) {
        FormData data = mapper.selectById(id);
        if (data == null) {
            throw new BizException("FORM_DATA_NOT_FOUND", "Form data not found: " + id);
        }
        return data;
    }

    private String writeJson(Object o) {
        try { return json.writeValueAsString(o); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("BAD_JSON", e.getMessage());
        }
    }

    private Page<FormData> enrichAdminPage(Page<FormData> page) {
        var records = page.getRecords();
        if (records.isEmpty()) return page;
        var users = userMapper.selectBatchIds(records.stream().map(FormData::getCreatedBy)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet())).stream()
            .collect(Collectors.toMap(com.antflow.org.User::getId, Function.identity()));
        Map<Long, FormDefinition> definitions = formDefinitionMapper.selectBatchIds(records.stream()
                .map(FormData::getFormDefId).collect(Collectors.toSet())).stream()
            .collect(Collectors.toMap(FormDefinition::getId, Function.identity()));
        records.forEach(record -> {
            var user = users.get(record.getCreatedBy());
            record.setCreatedByUsername(user == null ? null : user.getUsername());
            record.setFieldValues(fieldValues(record.getData(), definitions.get(record.getFormDefId())));
        });
        return page;
    }

    private List<FormData.FieldValue> fieldValues(String data, FormDefinition definition) {
        try {
            var labels = new java.util.HashMap<String, String>();
            if (definition != null) collectLabels(json.readTree(definition.getSchema()), labels);
            var values = json.readTree(data);
            if (values == null || !values.isObject()) return List.of();
            var fields = new java.util.ArrayList<FormData.FieldValue>();
            values.fields().forEachRemaining(entry -> fields.add(new FormData.FieldValue(
                entry.getKey(), labels.getOrDefault(entry.getKey(), entry.getKey()),
                json.convertValue(entry.getValue(), Object.class))));
            return fields;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            return List.of();
        }
    }

    private void collectLabels(com.fasterxml.jackson.databind.JsonNode nodes, Map<String, String> labels) {
        if (nodes == null || !nodes.isArray()) return;
        nodes.forEach(node -> {
            String id = node.path("id").asText();
            if (!id.isBlank()) labels.put(id, node.path("label").asText(id));
            collectLabels(node.path("children"), labels);
        });
    }
}
