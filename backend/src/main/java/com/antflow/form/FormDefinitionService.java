package com.antflow.form;

import com.antflow.authz.FormGrantService;
import com.antflow.engine.BizException;
import com.antflow.form.schema.FormSchemaJson;
import com.antflow.form.schema.FormSchemaParser;
import com.antflow.form.schema.FormSchemaValidator;
import com.antflow.form.schema.FormStarterCanonicalizer;
import com.antflow.form.schema.FormStarterProjector;
import com.antflow.form.schema.FormSubmissionValidator;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import com.antflow.process.DefinitionVersionRepository;
import com.antflow.common.BusinessNumberService;

import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 表单定义的应用服务：读模型 + 草稿/发布/停用/删除 + 对外的 schema 能力门面。
 *
 * <p>**这个类只允许做两件事**：
 * <ol>
 *   <li>表单定义聚合的读写（{@code list}/{@code get*}/{@code saveDraft}/{@code publish}/...）——
 *       唯一写入口，事务边界只在这里；</li>
 *   <li>把 schema 相关的公开 API 一行委托给 {@code com.antflow.form.schema} 下的协作者。</li>
 * </ol>
 *
 * <p>**为什么保留 24 个委托方法**：{@code engine}/{@code mobile}/{@code process}/{@code task}
 * 四个包都在调用它们，且不在本次拆分边界内；把调用方改成注入协作者会让"只重构"的 diff 扩散到
 * 整个后端。代价是方法数贴着 25 的上限——**新能力请加到协作者，不要回填这里**，
 * 否则 H7 会当场报红。详见 {@code docs/class-decomposition-form-definition-2026-10-22.md}。
 */
@Service
@RequiredArgsConstructor
public class FormDefinitionService {

    private final FormDefinitionMapper mapper;
    private final ObjectMapper json;
    /** schema/settings 的 JSON 边界：jsonb 排版无关的比较与宽松解析都在这里。 */
    private final FormSchemaJson schemaJson;
    /** schema 解析与结构契约；发布校验、提交校验、首填投影都从这棵树开始。 */
    private final FormSchemaParser schemaParser;
    /** 发布期结构校验：字段类型、选项、展示条件环。 */
    private final FormSchemaValidator schemaValidator;
    /** 提交期值校验：必填、形状、长度、选项值、明细行。 */
    private final FormSubmissionValidator submissionValidator;
    /** 首填/返工的字段权限落地。 */
    private final FormStarterCanonicalizer starterCanonicalizer;
    /** 面向发起人的可见性投影。 */
    private final FormStarterProjector starterProjector;
    private final FormGrantService formGrantService;
    @Autowired(required = false)
    private DefinitionVersionRepository versions;
    @Autowired(required = false)
    private BusinessNumberService businessNumbers;
    @Autowired(required = false)
    private com.antflow.form.options.OptionRuntimeService optionRuntimeService;

    private static final Set<String> STATUSES = Set.of("DRAFT", "PUBLISHED", "DEPRECATED");
    public Page<FormDefinitionMapper.Summary> list(long page, long size, String keyword,
                                                   String status) {
        return list(page, size, keyword, status, null, true);
    }

    public Page<FormDefinitionMapper.Summary> list(long page, long size, String keyword,
                                                   String status, Long userId, boolean admin) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), 100);
        if (status != null && !status.isBlank()) {
            validateStatus(status);
        }
        return mapper.selectSummaryPage(Page.of(safePage, safeSize),
            keyword == null || keyword.isBlank() ? null : keyword.trim(),
            status == null || status.isBlank() ? null : status.trim(), userId, admin);
    }

    public FormDefinition getByCode(String code) {
        FormDefinition published = versions == null ? null : versions.runtimeFormByCode(code);
        return published != null ? published
            : mapper.selectOne(new QueryWrapper<FormDefinition>().eq("code", code));
    }

    public FormDefinition getPublishedByCode(String code) {
        FormDefinition published = versions == null ? null : versions.runtimeFormByCode(code);
        return published != null ? published : mapper.selectOne(new QueryWrapper<FormDefinition>()
            .eq("code", code)
            .eq("status", "PUBLISHED"));
    }
    public FormDefinition getById(Long id) { return mapper.selectById(id); }

    /** 批量取表单定义（去重后一次查库），供列表类接口替代逐条 getById。 */
    public Map<Long, FormDefinition> mapByIds(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        List<Long> distinct = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) return Map.of();
        return mapper.selectBatchIds(distinct).stream()
            .collect(java.util.stream.Collectors.toMap(FormDefinition::getId,
                java.util.function.Function.identity()));
    }

    @Transactional
    public FormDefinition saveDraft(Long id, String code, String name,
                                    String description, Object schema,
                                    Object settings, Long userId) {
        FormDefinition fd;
        if (id == null) {
            if (mapper.selectCount(new QueryWrapper<FormDefinition>().eq("code", code)) > 0) {
                throw new BizException("CODE_EXISTS", "form code already exists: " + code);
            }
            fd = new FormDefinition();
            fd.setCode(code);
            fd.setName(name);
            fd.setDescription(description);
            fd.setVersion(1);
            schemaValidator.rejectDisplayConditionCycles(schema);
            fd.setSchema(schemaJson.writeJson(schema));
            fd.setSettings(schemaJson.writeJson(settings == null ? java.util.Map.of() : settings));
            fd.setStatus("DRAFT");
            fd.setCreatedBy(userId);
            fd.setDeleted(0);
            mapper.insert(fd);
            formGrantService.grantCreator(fd.getId(), userId);
        } else {
            fd = mapper.selectById(id);
            if (fd == null) {
                throw new BizException("FORM_NOT_FOUND", "Form not found: " + id);
            }
            if ("DEPRECATED".equals(fd.getStatus())) {
                throw new BizException("NOT_DRAFT", "Only DRAFT form_definitions can be edited");
            }
            // 为 null 的字段表示「这次请求没带它」，**保持原值**，不要覆盖。
            // 这里曾经是无条件写入：客户端一旦漏传（或拿到的是兜底值），name 会被写成"未命名表单"、
            // settings 会被清成 {}（schemaJson.writeJson(null) 存的是 JSON null，列是 NOT NULL 所以静默通过），
            // 表单的审批流开关、业务单号配置就这样无声消失。
            String nextSchema = schema == null ? fd.getSchema() : schemaJson.writeJson(schema);
            String nextSettings = settings == null ? fd.getSettings() : schemaJson.writeJson(settings);
            // 草稿不校验结构（历来如此），但**环**要拦：它会让设计器预览与两端渲染都给出
            // 一个说不清的可见性结果，留到发布才发现太晚。
            if (schema != null) schemaValidator.rejectDisplayConditionCycles(schema);
            // 数据源的「可引用表单」清单只拦**新增**绑定：引用被撤销后，已经绑着它的字段照常保存
            // （见 OptionRuntimeService.requireNewBindingsAreReferenced 的说明）。
            if (schema != null && optionRuntimeService != null) {
                optionRuntimeService.requireNewBindingsAreReferenced(
                    fd.getId(), fd.getSchema(), nextSchema);
            }
            // 只有 schema/settings 真的变了才降级为草稿。已发布表单改个名称、或只是点了一下保存，
            // 不该让它掉出手机端目录——发布快照只含 schema/settings，名称与描述不入快照。
            boolean contentChanged = !schemaJson.sameJson(fd.getSchema(), nextSchema)
                || !schemaJson.sameJson(fd.getSettings(), nextSettings);
            if (name != null) fd.setName(name);
            if (description != null) fd.setDescription(description);
            fd.setSchema(nextSchema);
            fd.setSettings(nextSettings);
            if (contentChanged) {
                fd.setStatus("DRAFT");
            }
            mapper.updateById(fd);
        }
        return fd;
    }

    @Transactional
    public FormDefinition update(Long id, String name, String description,
                                 String status, Object schema, Object settings) {
        FormDefinition fd = mapper.selectById(id);
        if (fd == null) {
            throw new BizException("FORM_NOT_FOUND", "Form not found: " + id);
        }
        if (name != null) fd.setName(name);
        if (description != null) fd.setDescription(description);
        if (status != null) {
            validateStatus(status);
            if (!status.equals(fd.getStatus())) {
                throw new BizException("USE_STATUS_ENDPOINT",
                    "Use publish/disable endpoints to change form status");
            }
        }
        if (schema != null || settings != null) {
            if (!"DRAFT".equals(fd.getStatus())) {
                throw new BizException("NOT_DRAFT", "Only DRAFT form_definitions can change schema/settings");
            }
            if (schema != null && optionRuntimeService != null) {
                optionRuntimeService.requireNewBindingsAreReferenced(
                    fd.getId(), fd.getSchema(), schemaJson.writeJson(schema));
            }
            if (schema != null) {
                schemaValidator.rejectDisplayConditionCycles(schema);
                fd.setSchema(schemaJson.writeJson(schema));
            }
            if (settings != null) fd.setSettings(schemaJson.writeJson(settings));
        }
        mapper.updateById(fd);
        return fd;
    }

    @Transactional
    public FormDefinition publish(Long id) {
        FormDefinition fd = mapper.selectById(id);
        if (fd == null) {
            throw new BizException("FORM_NOT_FOUND", "Form not found: " + id);
        }
        if (!"DRAFT".equals(fd.getStatus())) return fd;
        schemaValidator.validateSchema(fd.getSchema());
        if (optionRuntimeService != null) {
            optionRuntimeService.validateSchema(json.valueToTree(schemaParser.parseSchema(fd.getSchema())));
        }
        if (businessNumbers != null) businessNumbers.validate(fd);
        fd.setStatus("PUBLISHED");
        fd.setVersion(fd.getVersion() + 1);
        mapper.updateById(fd);
        if (versions != null) versions.publishForm(fd);
        return fd;
    }

    @Transactional
    public FormDefinition disable(Long id) {
        FormDefinition fd = mapper.selectById(id);
        if (fd == null) {
            throw new BizException("FORM_NOT_FOUND", "Form not found: " + id);
        }
        fd.setStatus("DEPRECATED");
        mapper.updateById(fd);
        return fd;
    }

    @Transactional
    public void softDelete(Long id) {
        FormDefinition fd = mapper.selectById(id);
        if (fd == null) {
            throw new BizException("FORM_NOT_FOUND", "Form not found: " + id);
        }
        mapper.deleteById(id);
    }







    private void validateStatus(String status) {
        if (!STATUSES.contains(status)) {
            throw new BizException("BAD_STATUS", "Unsupported form status: " + status);
        }
    }

    public Set<String> leafFieldIds(String schema) {
        return schemaParser.leafFieldIds(schema);
    }

    /** 返回叶子字段 id → 字段类型映射，供发布校验判断可编辑类型。 */
    public Map<String, String> leafFieldTypes(String schema) {
        return schemaParser.leafFieldTypes(schema);
    }

    public void validateSubmission(String schema, Object data) {
        submissionValidator.validateSubmission(schema, data);
    }

    /** 只校验指定字段集合（节点级字段权限用）：容器递归，其余字段跳过。 */
    public void validateSubmission(String schema, Object data, Set<String> fieldIds) {
        submissionValidator.validateSubmission(schema, data, fieldIds);
    }

    public Map<String, Object> filterVisibleSubmission(String schema, Object data) {
        return starterProjector.filterVisibleSubmission(schema, data);
    }

    /**
     * Applies the ROOT node field policy before a draft or workflow submission is persisted.
     * Missing ROOT permissions are intentionally treated as editable for old definitions.
     */
    public Map<String, Object> canonicalizeStarterSubmission(String schema, Object data,
                                                              Object process) {
        return starterCanonicalizer.canonicalizeStarterSubmission(schema, data, process);
    }

    /** Applies ROOT permissions without requiring an incomplete draft to pass validation. */
    public Map<String, Object> canonicalizeStarterData(String schema, Object data,
                                                        Object process) {
        return starterCanonicalizer.canonicalizeStarterData(schema, data, process);
    }

    /**
     * Applies ROOT permissions to a rework edit. Restricted values are retained from the
     * persisted revision, while editable values come from the initiator's request.
     */
    public Map<String, Object> canonicalizeStarterRevision(String schema, Object data,
                                                            Object existingData,
                                                            Object process) {
        return starterCanonicalizer.canonicalizeStarterRevision(schema, data, existingData, process);
    }

    /** Validates already-canonical initiator data while skipping ROOT-hidden fields. */
    public void validateStarterSubmission(String schema, Object data, Object process) {
        starterCanonicalizer.validateStarterSubmission(schema, data, process);
    }

    /** Rework retains persisted restricted values, so only editable fields are revalidated. */
    public void validateStarterRevision(String schema, Object data, Object process) {
        starterCanonicalizer.validateStarterRevision(schema, data, process);
    }

    /** ROOT formPerms as field id -> mode. Unlisted fields default to EDITABLE. */
    public Map<String, String> starterFieldModes(Object process) {
        return starterProjector.starterFieldModes(process);
    }

    /** Removes ROOT-hidden fields from starter-facing schema responses. */
    public JsonNode projectStarterSchema(Object schema, Object process) {
        return starterProjector.projectStarterSchema(schema, process);
    }

    /** Removes ROOT-hidden fields from starter-facing form data responses. */
    public JsonNode projectStarterData(Object data, Object schema, Object process) {
        return starterProjector.projectStarterData(data, schema, process);
    }
}
