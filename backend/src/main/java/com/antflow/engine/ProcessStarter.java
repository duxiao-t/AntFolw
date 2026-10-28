package com.antflow.engine;

import com.antflow.attachment.MediaSubmissionValidator;
import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.common.BusinessNumberService;
import com.antflow.common.FormalNumberService;
import com.antflow.engine.dto.StartCmd;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.runtime.FormData;
import com.antflow.form.runtime.FormDataMapper;
import com.antflow.notify.NotificationEvent;
import com.antflow.notify.NotificationPublisher;
import com.antflow.process.ProcessDefinition;
import com.antflow.process.ProcessDefinitionService;
import com.antflow.task.ProcessInstance;
import com.antflow.task.ProcessInstanceMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 发起流程：建运行时表单（SUBMITTED）→ 建实例（RUNNING）→ 从 ROOT 出发落地首个节点。
 *
 * <p>与审批推进分开的变化原因：发起阶段改的是**编号、快照、首节点**，
 * 审批阶段改的是**任务与分流**；两者的错误码集合也几乎不重叠（FORM_NOT_PUBLISHED /
 * NO_FLOW vs BAD_TASK_TYPE / BAD_FLOW / CONCURRENT_CONFLICT）。
 *
 * <p>五个易错点：
 * <ol>
 *   <li>**不能加 {@code @Transactional}**：入口 {@link ProcessEngine#start} 已开事务。拆开后
 *       "表单 + 实例 + 首个任务"必须同生共死，否则会留下没有任务的 RUNNING 实例。</li>
 *   <li>**不要再加公司归属检查**：单公司改造后发起不再写 {@code company_id}，
 *       以前那种"无归属优先报 BAD_COMPANY"的 fail-closed 已删除。</li>
 *   <li>{@code engineVersion} 的唯一写入点在这里，硬编码 2：新实例一律 V2，V1 只是只读兼容层。
 *       不要改成按配置读取。</li>
 *   <li>后续推进一律读 {@code pi.getProcessSnapshot()}（不是 {@code pd.getProcess()}），
 *       否则流程改版后已发起的实例会跑新树。</li>
 *   <li>{@code businessNumbers} 是可选 bean（{@code @Autowired(required = false)}），
 *       为 null 时才退回 {@link FormalNumberService}；不要为了"简化"直接调用后者，
 *       编号规则会绕过租户配置。</li>
 * </ol>
 */
final class ProcessStarter {

    private final EngineSupport support;
    private final FormDefinitionService formDefinitionService;
    private final FormDataMapper formDataMapper;
    private final ProcessDefinitionService processDefinitionService;
    private final ProcessInstanceMapper processInstanceMapper;
    private final FormalNumberService formalNumberService;
    private final AuthorizationService authorizationService;
    private final MediaSubmissionValidator mediaValidator;
    private final NotificationPublisher notifier;
    private final NodeLander nodeLander;
    private final Supplier<WorkflowRuntimeV2> runtimeV2;
    /**
     * 可选依赖也用 Supplier：它在 ProcessEngine 上是字段注入（required = false），
     * 构造期可能还没赋值，且单测会在构造后替换。
     */
    private final Supplier<BusinessNumberService> businessNumbers;

    ProcessStarter(EngineSupport support, FormDefinitionService formDefinitionService,
                   FormDataMapper formDataMapper,
                   ProcessDefinitionService processDefinitionService,
                   ProcessInstanceMapper processInstanceMapper,
                   FormalNumberService formalNumberService,
                   AuthorizationService authorizationService,
                   MediaSubmissionValidator mediaValidator, NotificationPublisher notifier,
                   NodeLander nodeLander, Supplier<WorkflowRuntimeV2> runtimeV2,
                   Supplier<BusinessNumberService> businessNumbers) {
        this.support = support;
        this.formDefinitionService = formDefinitionService;
        this.formDataMapper = formDataMapper;
        this.processDefinitionService = processDefinitionService;
        this.processInstanceMapper = processInstanceMapper;
        this.formalNumberService = formalNumberService;
        this.authorizationService = authorizationService;
        this.mediaValidator = mediaValidator;
        this.notifier = notifier;
        this.nodeLander = nodeLander;
        this.runtimeV2 = runtimeV2;
        this.businessNumbers = businessNumbers;
    }

    Map<String, Object> start(StartCmd cmd, long userId) {
        WorkflowRuntimeV2 v2 = runtimeV2.get();
        // 单公司：不再要求（也不再记录）公司归属。
        if (v2 == null) {
            throw new BizException("RUNTIME_REQUIRED", "发起流程需要 V2 运行时");
        }
        FormDefinition fd = formDefinitionService.getByCode(cmd.formCode());
        if (fd == null || !"PUBLISHED".equals(fd.getStatus())) {
            throw new BizException("FORM_NOT_PUBLISHED", "Form not published: " + cmd.formCode());
        }
        // 表单使用授权：未获得 t_form_resource_grant 授权的用户不能发起该表单的流程。
        authorizationService.requireFormUse(fd.getId());
        ProcessDefinition pd = processDefinitionService.latestPublishedForForm(fd.getId());
        if (pd == null) {
            throw new BizException("NO_FLOW", "No published process for form " + cmd.formCode());
        }
        processDefinitionService.requireNodeFallbackPolicy(pd.getProcess());
        var visibleData = formDefinitionService.canonicalizeStarterSubmission(
            fd.getSchema(), cmd.data(), pd.getProcess());

        FormData fd2 = new FormData();
        fd2.setFormDefId(fd.getId());
        fd2.setFormDefVersion(fd.getVersion());
        BusinessNumberService numbers = businessNumbers.get();
        fd2.setBusinessNo(numbers == null
            ? formalNumberService.businessNo() : numbers.next(fd, visibleData));
        fd2.setData(support.writeJson(visibleData));
        fd2.setStatus("SUBMITTED");
        fd2.setCreatedBy(userId);
        formDataMapper.insert(fd2);
        mediaValidator.validate(fd2.getId());

        ProcessInstance pi = new ProcessInstance();
        pi.setProcDefId(pd.getId());
        pi.setProcessDefVersion(pd.getVersion());
        String normalizedProcess = processDefinitionService.normalizeConditionValues(
            pd.getProcess(), fd.getSchema());
        pi.setProcessSnapshot(normalizedProcess == null ? pd.getProcess() : normalizedProcess);
        pi.setFormDataId(fd2.getId());
        pi.setStatus("RUNNING");
        pi.setStartedBy(userId);
        PrincipalHolder.current()
            .filter(principal -> principal.userId() == userId)
            .ifPresent(principal -> pi.setStartedDeptId(principal.departmentId()));
        pi.setStartedAt(OffsetDateTime.now());
        WorkflowRuntimeV2.StartState state = v2.prepareStart(fd2, pd, userId);
        pi.setEngineVersion(2);
        pi.setRoundNo(1);
        pi.setProcessDefinitionVersionId(state.processVersionId());
        pi.setCurrentFormRevisionId(state.revisionId());
        processInstanceMapper.insert(pi);

        // 引擎后续一律走快照树，而非 pd.getProcess()
        JsonNode root = support.readTree(pi.getProcessSnapshot());
        JsonNode formData = support.readTreeOrEmpty(fd2.getData());
        Map<String, List<Long>> selfSelected =
            cmd.selfSelected() == null ? Map.of() : cmd.selfSelected();

        List<Long> firstTasks = nodeLander.resolveAndLand(root, pi, formData, userId, selfSelected, root);
        if (v2 != null && v2.active(pi)) {
            v2.outbox(pi.getId(), userId, "INSTANCE_STARTED", null);
        } else {
            notifier.publish(new NotificationEvent(this, "INSTANCE_STARTED",
                pi.getId(), null, userId, "流程发起 #" + pi.getId()));
        }
        return Map.of(
            "instanceId", pi.getId(),
            "formDataId", fd2.getId(),
            "businessNo", fd2.getBusinessNo(),
            "firstTaskIds", firstTasks
        );
    }
}
