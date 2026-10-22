package com.antflow.process;

import com.antflow.automation.WebhookSecurityPolicy;
import com.antflow.engine.BizException;
import com.antflow.form.FormDefinitionService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 流程定义的应用服务：定义读写 + 发布编排 + 对外的校验/规范化门面。
 *
 * <p>**这个类只允许做两件事**：
 * <ol>
 *   <li>流程定义聚合的读写（{@code getById}/{@code saveOrUpdateDraft}/{@code publish}/
 *       {@code latestPublishedFor*}）——唯一写入口，事务边界只在这里；</li>
 *   <li>把校验、规范化、意见预设读取一行委托给同包协作者。</li>
 * </ol>
 *
 * <p>**为什么 {@code publish} 不搬到独立的发布服务**：它与 {@code saveOrUpdateDraft} 共用
 * 同一条写路径（读行 → 改 status/version → {@code updateById} → 写版本表），拆开后
 * "谁负责推进 version"就有两个答案。协作者 {@link ProcessConditionNormalizer} /
 * {@link ProcessTreeValidator} / {@link ProcessStarterFieldGuard} 只做纯函数式的校验与改写，
 * 不写库、不开事务。
 *
 * <p>**为什么版本读取留在原地**：版本表读写已由 {@link DefinitionVersionRepository} 承担；
 * 这里只剩"运行时读哪个版本"的两处口径（版本表优先、缺的退回已发布行），它们与
 * {@code publish} 共用同一个可空 {@code versions} 依赖。抽出去只能得到一个转发壳，
 * 还要为可空依赖再搭一层装配。
 *
 * <p>**协作者不得反向依赖本类**：它们需要的上下文（流程树 JSON、字段类型表、可空的
 * Webhook 策略）全部由构造器传入。新增能力加到协作者里，不要在这里回填私有方法——
 * 这个类只负责编排与写库。
 */
@Service
public class ProcessDefinitionService {

    private final ProcessDefinitionMapper mapper;
    private final FormDefinitionService formDefinitionService;
    private final ObjectMapper json;
    /** 条件分支取值规范化与旧版流程级兜底配置迁移（发布口径与运行时口径共用一套实现）。 */
    private final ProcessConditionNormalizer conditionNormalizer;
    /** 审批节点的可选配置规则；同时承担意见预设的运行时读取（校验与读取共用一套归一化）。 */
    private final ProcessApprovalRules approvalRules;
    /** 树结构校验：形状、深度、ID 唯一、驳回目标。 */
    private final ProcessTreeValidator treeValidator;
    /** 发布前的发起人视角检查：只读必填默认值、动态人员字段来源。 */
    private final ProcessStarterFieldGuard starterFieldGuard;

    @Autowired(required = false)
    private DefinitionVersionRepository versions;

    public ProcessDefinitionService(ProcessDefinitionMapper mapper,
                                    FormDefinitionService formDefinitionService,
                                    ObjectMapper json) {
        this(mapper, formDefinitionService, json, null);
    }

    @Autowired
    public ProcessDefinitionService(ProcessDefinitionMapper mapper,
                                    FormDefinitionService formDefinitionService,
                                    ObjectMapper json,
                                    WebhookSecurityPolicy webhookSecurityPolicy) {
        this.mapper = mapper;
        this.formDefinitionService = formDefinitionService;
        this.json = json;
        // 协作者都是无状态纯函数，随门面一起装配。没有做成 @Component：
        // 单测直接 new 这个类（3 参构造器）来跑纯校验，做成 bean 会出现两套装配路径，
        // 而 3 参构造器拿不到容器里的实例。
        this.approvalRules = new ProcessApprovalRules(json);
        this.conditionNormalizer = new ProcessConditionNormalizer(json);
        this.treeValidator = new ProcessTreeValidator(json,
            new ProcessNodeValidator(approvalRules, webhookSecurityPolicy), approvalRules);
        this.starterFieldGuard = new ProcessStarterFieldGuard(json);
    }

    public ProcessDefinition getById(Long id) {
        return mapper.selectById(id);
    }

    @Transactional
    public ProcessDefinition saveOrUpdateDraft(Long id, Long formDefId, Object process,
                                                Long userId) {
        ProcessDefinition pd;
        if (id == null) {
            pd = mapper.selectOne(new QueryWrapper<ProcessDefinition>()
                .eq("form_def_id", formDefId)
                .orderByDesc("id")
                .last("LIMIT 1"));
            if (pd == null) {
                pd = new ProcessDefinition();
                pd.setFormDefId(formDefId);
                pd.setVersion(1);
                pd.setProcess(writeJson(process));
                pd.setStatus("DRAFT");
                pd.setCreatedBy(userId);
                mapper.insert(pd);
            } else {
                pd.setProcess(writeJson(process));
                pd.setStatus("DRAFT");
                mapper.updateById(pd);
            }
        } else {
            pd = mapper.selectById(id);
            if (pd == null) {
                throw new BizException("PROCESS_NOT_FOUND", "Process not found: " + id);
            }
            if (!formDefId.equals(pd.getFormDefId())) {
                throw new BizException("PROCESS_FORM_MISMATCH",
                    "Process does not belong to form: " + formDefId);
            }
            pd.setProcess(writeJson(process));
            pd.setStatus("DRAFT");
            mapper.updateById(pd);
        }
        return pd;
    }

    @Transactional
    public ProcessDefinition publish(Long id) {
        ProcessDefinition pd = mapper.selectById(id);

        var fd = formDefinitionService.getById(pd.getFormDefId());
        if (!"PUBLISHED".equals(fd.getStatus())) {
            throw new BizException("FOR_FORM_NOT_PUBLISHED",
                "Associated form must be PUBLISHED before publishing the flow");
        }

        pd.setProcess(conditionNormalizer.normalizeConditionValuesForPublish(
            pd.getProcess(), fd.getSchema()));
        pd.setProcess(conditionNormalizer.applyNodeFallbackPolicyForPublish(pd.getProcess()));
        validateProcessTree(pd.getProcess(),
            formDefinitionService.leafFieldTypes(fd.getSchema()));
        starterFieldGuard.validateStarterReadonlyDefaults(pd.getProcess(), fd.getSchema());
        starterFieldGuard.validateDynamicUserFields(pd.getProcess(), fd.getSchema());

        pd.setStatus("PUBLISHED");
        pd.setVersion(pd.getVersion() + 1);
        mapper.updateById(pd);
        if (versions != null) versions.publishProcess(pd, fd);
        return pd;
    }

    public ProcessDefinition latestPublishedForForm(Long formDefId) {
        ProcessDefinition published = versions == null ? null
            : versions.runtimeProcessForForm(formDefId);
        return published != null ? published : mapper.selectOne(new QueryWrapper<ProcessDefinition>()
            .eq("form_def_id", formDefId).eq("status", "PUBLISHED")
            .orderByDesc("version").last("LIMIT 1"));
    }

    /**
     * latestPublishedForForm 的批量版，口径一致（版本表优先，缺的退回已发布行）。
     * 列表接口用它替代逐张表单调用，把 N 次往返压成最多 2 次。
     */
    public Map<Long, ProcessDefinition> latestPublishedForForms(
            java.util.Collection<Long> formDefIds) {
        if (formDefIds == null || formDefIds.isEmpty()) return Map.of();
        Map<Long, ProcessDefinition> result = new java.util.LinkedHashMap<>(
            versions == null ? Map.of() : versions.runtimeProcessesForForms(formDefIds));
        List<Long> missing = formDefIds.stream().filter(java.util.Objects::nonNull)
            .distinct().filter(id -> !result.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            // form_def_id 在 t_process_definition 上唯一，一张表单最多一行。
            for (ProcessDefinition definition : mapper.selectList(
                    new QueryWrapper<ProcessDefinition>()
                        .in("form_def_id", missing).eq("status", "PUBLISHED"))) {
                result.putIfAbsent(definition.getFormDefId(), definition);
            }
        }
        return result;
    }

    public ProcessDefinition findByForm(Long formDefId) {
        return mapper.selectOne(new QueryWrapper<ProcessDefinition>()
            .eq("form_def_id", formDefId)
            .orderByDesc("id").last("LIMIT 1"));
    }

    public List<ProcessDefinition> list() {
        return mapper.selectList(null);
    }

    /** New definitions must opt in to explicit, per-node fallback handling. */
    public void requireNodeFallbackPolicy(String processJson) {
        treeValidator.requireNodeFallbackPolicy(processJson);
    }

    /** 将选项条件严格规范化为 Schema 中的真实 value；兼容唯一精确匹配的旧 label。 */
    public String normalizeConditionValues(String processJson, String formSchema) {
        return conditionNormalizer.normalizeConditionValues(processJson, formSchema);
    }

    String normalizeConditionValuesForPublish(String processJson, String formSchema) {
        return conditionNormalizer.normalizeConditionValuesForPublish(processJson, formSchema);
    }

    @Transactional
    public void deleteByForm(Long formDefId) {
        mapper.delete(new QueryWrapper<ProcessDefinition>().eq("form_def_id", formDefId));
    }

    void validateProcessTree(String processJson) {
        treeValidator.validateProcessTree(processJson);
    }

    /** 带表单字段表校验流程树（节点级字段权限需要知道字段是否存在于当前表单）。 */
    void validateProcessTree(String processJson, Map<String, String> fieldTypes) {
        treeValidator.validateProcessTree(processJson, fieldTypes);
    }

    public ApprovalCommentPresets commentPresets(Object process, String nodeId) {
        return approvalRules.commentPresets(process, nodeId);
    }

    private String writeJson(Object o) {
        try { return json.writeValueAsString(o); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("BAD_JSON", e.getMessage());
        }
    }
}
