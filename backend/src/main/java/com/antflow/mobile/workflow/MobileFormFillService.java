package com.antflow.mobile.workflow;

import com.antflow.authz.AuthorizationService;
import com.antflow.engine.BizException;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.runtime.MobileDraftService;
import com.antflow.process.ProcessDefinition;
import com.antflow.process.ProcessDefinitionService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import lombok.RequiredArgsConstructor;

/**
 * 填单页的投影：按表单 code 取"可填的那一版"schema / 流程树 / starter 字段模式。
 *
 * <p>为什么要独立：这是唯一一处**读表单定义**的入口，它的校验顺序直接决定填单页看到什么：
 * <ol>
 *   <li>表单必须存在且 PUBLISHED——草稿/返工都不能绕过这一条；</li>
 *   <li>{@code draftId} 与 {@code reworkTaskId} 互斥，同时传直接 BAD_REQUEST
 *       （前端在两个入口各有按钮，同时传说明调用方拼错了，不该猜）；</li>
 *   <li>带草稿走 {@code requireUsableDraft}（校验归属与可用性），带返工任务走返工读路径，
 *       两者都不带才按当前身份校验表单使用权——三条分支的权限来源不同，不能合并；</li>
 *   <li>流程树为空时 schema 仍要能返回：{@code projectStarterSchema} 对 null 树是容忍的，
 *       但 {@code readJsonObject} 会把 null 归一成空对象，所以这里先判空再解析。</li>
 * </ol>
 */
@RequiredArgsConstructor
final class MobileFormFillService {
    private static final String PUBLISHED_STATUS = "PUBLISHED";

    private final MobileDraftService draftService;
    private final FormDefinitionService formDefinitionService;
    private final ProcessDefinitionService processDefinitionService;
    private final AuthorizationService authorizationService;
    private final MobileReworkService reworks;
    private final MobileProjectionSupport projection;

    MobileFormDto getMobileForm(String code) {
        return getMobileForm(code, null, null);
    }

    MobileFormDto getMobileForm(String code, Long draftId, Long reworkTaskId) {
        FormDefinition form = formDefinitionService.getByCode(code);
        if (form == null || !PUBLISHED_STATUS.equals(form.getStatus())) {
            throw new BizException("FORM_NOT_PUBLISHED", "Form not published: " + code);
        }
        if (draftId != null && reworkTaskId != null) {
            throw new BizException("BAD_REQUEST", "choose a draft or a rework task");
        }
        if (draftId != null) {
            draftService.requireUsableDraft(draftId, form.getId(), authorizationService.currentUserId());
        } else if (reworkTaskId != null) {
            ReworkTaskDto rework = reworks.getReworkTask(reworkTaskId, authorizationService.currentUserId());
            if (!Objects.equals(rework.formCode(), code)) {
                throw new BizException("BAD_REQUEST", "rework task does not belong to form");
            }
        } else {
            authorizationService.requireFormUse(form.getId());
        }
        ProcessDefinition process = processDefinitionService.latestPublishedForForm(form.getId());
        JsonNode processTree = projection.readJsonObject(
            process == null ? null : process.getProcess(), "BAD_FLOW_JSON");
        JsonNode schema = projection.readJsonArray(form.getSchema(), "BAD_SCHEMA_JSON");
        return new MobileFormDto(form.getCode(), form.getName(), form.getVersion(),
            formDefinitionService.projectStarterSchema(schema, processTree),
            projection.readJsonObject(form.getSettings(), "BAD_SETTINGS_JSON"),
            processTree, formDefinitionService.starterFieldModes(processTree));
    }
}
