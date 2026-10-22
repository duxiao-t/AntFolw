package com.antflow.engine;

import com.antflow.attachment.MediaSubmissionValidator;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.runtime.FormData;
import com.antflow.form.runtime.FormDataMapper;
import com.antflow.task.ProcessInstance;
import com.antflow.task.ProcessInstanceMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 审批节点的"字段可编辑"回写：把 {@code formPerms} 里标成 EDITABLE 的字段合并进运行时表单。
 *
 * <p>单独成类的原因是它与审批推进的变化原因不同：这里改的是**表单数据与修订版本**，
 * 审批推进改的是**任务与节点**。合在一起时，任何一次表单权限调整都要在几百行的推进逻辑里翻找。
 *
 * <p>四个易错点：
 * <ol>
 *   <li>**不能加 {@code @Transactional}**：它跑在调用方 approve 的事务里。这里同时写
 *       t_form_data 与修订表，分两段提交会出现"修订有了、表单没改"。</li>
 *   <li>{@code data} 为空对象视为"没改"，直接返回；但**非空对象**在没有 EDITABLE 字段时必须报错，
 *       不能静默丢弃——用户以为改了，实际没落库。</li>
 *   <li>校验用的是**节点 schema**（V2 实例取 {@code runtimeV2.formSchema(pi)}），不是表单发布版本的
 *       schema：否则流程改版后，老实例会被新 schema 的必填项挡住。</li>
 *   <li>{@code mediaValidator} 的第二参是**字段模式表**（EDITABLE / READONLY），
 *       不是字段 id 列表；传错会让所有附件都被当作只读校验。</li>
 * </ol>
 */
final class FieldEditApplier {

    private final EngineSupport support;
    private final FormDataMapper formDataMapper;
    private final FormDefinitionService formDefinitionService;
    private final ProcessInstanceMapper processInstanceMapper;
    private final MediaSubmissionValidator mediaValidator;
    private final ObjectMapper json;
    private final Supplier<WorkflowRuntimeV2> runtimeV2;

    FieldEditApplier(EngineSupport support, FormDataMapper formDataMapper,
                     FormDefinitionService formDefinitionService,
                     ProcessInstanceMapper processInstanceMapper,
                     MediaSubmissionValidator mediaValidator, ObjectMapper json,
                     Supplier<WorkflowRuntimeV2> runtimeV2) {
        this.support = support;
        this.formDataMapper = formDataMapper;
        this.formDefinitionService = formDefinitionService;
        this.processInstanceMapper = processInstanceMapper;
        this.mediaValidator = mediaValidator;
        this.json = json;
        this.runtimeV2 = runtimeV2;
    }

    /**
     * 审批节点可编辑字段回写：只允许修改当前节点 formPerms 中标记为 EDITABLE 的字段，
     * 逐字段按 schema 校验后合并进 t_form_data.data（未提交的字段保持不变）。
     */
    void applyNodeFieldEdits(ProcessInstance pi, JsonNode cur, Object data,
                             long operatorId) {
        if (data instanceof Map<?, ?> edits && edits.isEmpty()) {
            return;
        }
        Set<String> editable = editableFieldIds(cur);
        if (editable.isEmpty()) {
            throw new BizException("FORM_DATA_INVALID", "该审批节点不允许编辑表单字段");
        }
        if (!(data instanceof Map<?, ?> edits)) {
            throw new BizException("FORM_DATA_INVALID", "表单字段更新必须是对象");
        }
        for (Object key : edits.keySet()) {
            if (!(key instanceof String fieldId) || !editable.contains(fieldId)) {
                throw new BizException("FORM_DATA_INVALID", "字段不可编辑: " + key);
            }
        }
        FormData formData = formDataMapper.selectRuntimeById(pi.getFormDataId());
        if (formData == null) {
            throw new BizException("NOT_FOUND", "form data not found");
        }
        FormDefinition fd = formDefinitionService.getById(formData.getFormDefId());
        WorkflowRuntimeV2 v2 = runtimeV2.get();
        String schema = v2 == null ? null : v2.formSchema(pi);
        if (fd == null && schema == null) {
            throw new BizException("NOT_FOUND", "form definition not found");
        }
        var merged = (com.fasterxml.jackson.databind.node.ObjectNode)
            support.readTreeOrEmpty(formData.getData()).deepCopy();
        edits.forEach((key, value) -> merged.set((String) key, json.valueToTree(value)));
        formDefinitionService.validateSubmission(schema == null ? fd.getSchema() : schema,
            merged, editable);
        formData.setData(support.writeJson(merged));
        formDataMapper.updateById(formData);
        Map<String, String> modes = new LinkedHashMap<>();
        support.readTree(schema == null ? fd.getSchema() : schema).forEach(node ->
            modes.put(node.path("id").asText(),
                editable.contains(node.path("id").asText()) ? "EDITABLE" : "READONLY"));
        mediaValidator.validate(formData.getId(), modes);
        if (v2 != null && v2.active(pi)) {
            long revisionId = v2.createRevision(formData, "SUBMITTED", "NODE_EDIT",
                operatorId);
            pi.setCurrentFormRevisionId(revisionId);
            processInstanceMapper.updateById(pi);
        }
    }

    private Set<String> editableFieldIds(JsonNode node) {
        var perms = node.path("props").path("formPerms");
        var ids = new LinkedHashSet<String>();
        if (perms.isArray()) {
            for (var entry : perms) {
                if ("EDITABLE".equals(entry.path("mode").asText())) {
                    ids.add(entry.path("fieldId").asText());
                }
            }
        }
        return ids;
    }
}
