package com.antflow.form.correction;

import com.antflow.attachment.MediaSubmissionValidator;
import com.antflow.attachment.MobileFileLinkService;
import com.antflow.audit.AuditService;
import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.engine.BizException;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.runtime.FormData;
import com.antflow.notify.NotificationEvent;
import com.antflow.notify.NotificationPublisher;
import com.antflow.process.DefinitionVersionRepository;
import com.antflow.process.RevisionReasons;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 已提交表单数据的「事后修正」。
 *
 * <p>定位：这是**审批终态之后的纠错**，不是重新审批。它改写的是已经生效的业务数据，
 * 所以每一步都在压风险——schema 钉在单据自己的版本、字段走白名单、并发走基准比对、
 * 历史交给修订表、审计与通知都不能省。
 *
 * <p>不变量（改动这里前先读一遍）：
 * <ol>
 *   <li>修订表只增不改：唯一的写入入口是 {@code createRevision}</li>
 *   <li>主表数据与修订内容必须一致：先写主表、紧接着追加修订，同一事务</li>
 *   <li>结构性字段（单号、创建人、版本、状态、公司归属）永不改写</li>
 *   <li>实例的状态/节点/审批留痕永不改写，只动修订指针与乐观锁版本号</li>
 *   <li>{@code RUNNING} 实例一律拒绝——审批中的内容归审批流程管</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class FormDataCorrectionService {

    private static final String STATUS_APPLIED = "APPLIED";
    private static final String STATUS_PENDING = "PENDING_REVIEW";
    private static final Set<String> ALLOWED_REASONS =
        Set.of("AMOUNT_ERROR", "TYPO", "WRONG_ATTACHMENT", "MISSING_VALUE", "OTHER");

    private final FormDataCorrectionRepository repository;
    private final CorrectionSchemaResolver schemaResolver;
    private final CorrectionFieldPolicy fieldPolicy;
    private final AuthorizationService authorization;
    private final FormDefinitionService formDefinitions;
    private final DefinitionVersionRepository versions;
    private final MobileFileLinkService fileLinks;
    private final MediaSubmissionValidator mediaValidator;
    private final AuditService audit;
    private final NotificationPublisher notifications;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    /** 表单的修正相关配置（code/name/最新版本/是否要求复核/单号规则来源）。 */
    private record FormConfig(String code, String name, int latestVersion,
                              boolean reviewRequired, String settings) {
    }

    public CorrectionDtos.CorrectionFormView view(long formDataId) {
        FormDataCorrectionRepository.DataRow row = repository.findData(formDataId);
        if (row == null) throw new BizException("FORM_DATA_NOT_FOUND", "填报记录不存在");
        authorization.requireFormCorrection(row.formDefId());
        authorization.requireCorrectableFormData(formDataId);

        FormConfig config = formConfig(row.formDefId());
        CorrectionSchemaResolver.Resolved resolved =
            schemaResolver.resolve(row.formDefId(), row.formDefVersion(), row.currentRevisionId());
        CorrectionFieldPolicy.Policy policy =
            fieldPolicy.compute(row.formDefId(), config.settings(), resolved.schema());
        FormDataCorrectionRepository.InstanceRow instance = repository.latestInstance(formDataId);

        try {
            return new CorrectionDtos.CorrectionFormView(
                formDataId, row.formDefId(), config.code(), config.name(), row.businessNo(),
                row.formDefVersion(), config.latestVersion(),
                row.formDefVersion() < config.latestVersion(), resolved.approximate(),
                resolved.schema(), json.readTree(row.data()),
                List.copyOf(policy.allowed()), policy.blocked(),
                row.currentRevisionId(),
                instance == null ? null : instance.status(),
                config.reviewRequired());
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new BizException("CORRECTION_DATA_INVALID", "单据内容不是合法 JSON，无法修正");
        }
    }

    /**
     * 提交一次修正。
     *
     * <p>事务边界覆盖"锁行 → 校验 → 附件 → 主表 → 修订 → 实例指针 → 台账 → 审计"，
     * 中途任何一步失败都整体回滚，不会出现"数据改了但修订没记"的半成品。
     */
    @Transactional
    public CorrectionDtos.CorrectionResult correct(long formDataId,
                                                    CorrectionDtos.CorrectionRequest request) {
        PrincipalHolder.Principal principal = PrincipalHolder.current()
            .orElseThrow(() -> new BizException("UNAUTHENTICATED", "未登录"));
        long actorId = principal.userId();

        FormDataCorrectionRepository.DataRow row = repository.lockData(formDataId);
        if (row == null) throw new BizException("FORM_DATA_NOT_FOUND", "填报记录不存在");
        authorization.requireFormCorrection(row.formDefId());
        authorization.requireCorrectableFormData(formDataId);

        FormDataCorrectionRepository.InstanceRow instance = repository.latestInstance(formDataId);
        rejectRunning(instance);

        // 并发基准必须比对上：比的是"读到的那一版"，不是客户端声称的版本
        if (!Objects.equals(row.currentRevisionId(), request.baseRevisionId())) {
            throw new BizException("CORRECTION_CONFLICT", "这份数据已被其他人修改，请重新打开后再改");
        }
        String reasonCode = normalizeReason(request.reasonCode());

        FormConfig config = formConfig(row.formDefId());
        CorrectionSchemaResolver.Resolved resolved =
            schemaResolver.resolve(row.formDefId(), row.formDefVersion(), row.currentRevisionId());
        CorrectionFieldPolicy.Policy policy =
            fieldPolicy.compute(row.formDefId(), config.settings(), resolved.schema());

        Map<String, Object> merged = merge(row.data(), request.fields(), policy);
        Set<String> changed = new LinkedHashSet<>(request.fields().keySet());
        formDefinitions.validateSubmission(resolved.schema(), merged, changed);

        // 附件必须先落到主表集合，createRevision 会把它整体快照进新修订。
        // 顺序反了快照复制的就是旧附件（这条在 DefinitionVersionRepository:192 是硬编码的）。
        boolean attachmentsTouched = request.files() != null;
        if (attachmentsTouched) {
            fileLinks.reconcileEditable(formDataId, request.files(), actorId, Map.of());
            mediaValidator.validate(formDataId);
        }

        String mergedJson = write(merged);
        if (config.reviewRequired()) {
            // 待复核：数据一个字节都不动，只存"拟修正结果"。等复核通过才落库。
            long correctionId = repository.insertCorrection(formDataId, STATUS_PENDING, null,
                row.currentRevisionId(), instance == null ? null : instance.id(), actorId,
                reasonCode, request.reasonText(), changesJson(row.data(), merged),
                mergedJson, attachmentsTouched, row.companyId(),
                instance == null ? null : instance.status(), null);
            audit.successAs(principal, "FORM_DATA_CORRECTION_SUBMIT", "FORM_DATA", formDataId,
                AuditService.RiskLevel.CRITICAL, readMap(changesJson(row.data(), merged)),
                Map.of("correctionId", correctionId, "status", STATUS_PENDING,
                    "reviewRequired", true));
            return new CorrectionDtos.CorrectionResult(correctionId, STATUS_PENDING, null);
        }

        Applied applied = apply(row, instance, mergedJson, merged, actorId, reasonCode,
            request.reasonText(), attachmentsTouched, null, principal);
        return new CorrectionDtos.CorrectionResult(applied.correctionId(), STATUS_APPLIED,
            applied.revisionId());
    }

    /** 复核：通过则把待复核的拟修正数据真正写入；驳回则只改台账状态。 */
    @Transactional
    public CorrectionDtos.CorrectionResult review(long formDataId, long correctionId,
                                                   CorrectionDtos.ReviewRequest request) {
        PrincipalHolder.Principal principal = PrincipalHolder.current()
            .orElseThrow(() -> new BizException("UNAUTHENTICATED", "未登录"));
        FormDataCorrectionRepository.DataRow row = repository.lockData(formDataId);
        if (row == null) throw new BizException("FORM_DATA_NOT_FOUND", "填报记录不存在");
        authorization.requireFormCorrection(row.formDefId());
        authorization.requireCorrectableFormData(formDataId);

        FormDataCorrectionRepository.PendingRow pending = repository.lockPending(correctionId);
        if (pending == null || pending.formDataId() != formDataId) {
            throw new BizException("CORRECTION_NOT_FOUND", "修正记录不存在");
        }
        if (!STATUS_PENDING.equals(pending.status())) {
            throw new BizException("CORRECTION_NOT_PENDING", "该修正已处理，不能重复复核");
        }
        // 提交人不能自己复核自己
        if (request.approve() && Objects.equals(pending.actorId(), principal.userId())) {
            throw new BizException("CORRECTION_SELF_REVIEW", "不能复核自己提交的修正");
        }
        if (!request.approve()) {
            repository.markRejected(correctionId, principal.userId(), request.note());
            audit.successAs(principal, "FORM_DATA_CORRECTION_REJECT", "FORM_DATA", formDataId,
                AuditService.RiskLevel.CRITICAL, Map.of(),
                Map.of("correctionId", correctionId));
            return new CorrectionDtos.CorrectionResult(correctionId, "REJECTED", null);
        }

        FormDataCorrectionRepository.InstanceRow instance = repository.latestInstance(formDataId);
        rejectRunning(instance);
        Map<String, Object> merged = readMap(pending.proposedData());
        // 复核意见走 request.note()，不能沿用"修正原因"那一栏——
        // 复核路径的 reasonText 参数本来就是空的，之前传 null 导致复核意见静默丢失。
        Applied applied = apply(row, instance, pending.proposedData(), merged,
            principal.userId(), null, request.note(), false, correctionId, principal);
        return new CorrectionDtos.CorrectionResult(applied.correctionId(), STATUS_APPLIED,
            applied.revisionId());
    }

    public List<CorrectionDtos.CorrectionView> history(long formDataId, int limit) {
        FormDataCorrectionRepository.DataRow row = repository.findData(formDataId);
        if (row == null) throw new BizException("FORM_DATA_NOT_FOUND", "填报记录不存在");
        authorization.requireFormCorrection(row.formDefId());
        authorization.requireCorrectableFormData(formDataId);
        return repository.list(formDataId, Math.min(Math.max(limit, 1), 200));
    }

    /** 台账列表的「已修正」徽标。 */
    public Set<Long> correctedIds(List<Long> formDataIds) {
        return repository.correctedFormDataIds(formDataIds);
    }

    private record Applied(long correctionId, long revisionId) {
    }

    /**
     * 真正把新数据落库的顺序执行体（直接生效与复核通过共用）。
     *
     * <p>顺序不可调换：主表 CAS → 追加修订 → 实例指针。修订内部会再锁一次行并取号，
     * 同一事务内重复加锁是安全的。
     */
    private Applied apply(FormDataCorrectionRepository.DataRow row,
                          FormDataCorrectionRepository.InstanceRow instance,
                          String mergedJson, Map<String, Object> merged, long actorId,
                          String reasonCode, String reasonText, boolean attachmentsTouched,
                          Long pendingCorrectionId, PrincipalHolder.Principal principal) {
        if (repository.updateDataCas(row.id(), mergedJson, row.currentRevisionId()) == 0) {
            throw new BizException("CORRECTION_CONFLICT", "这份数据已被其他人修改，请重新打开后再改");
        }
        FormData formData = new FormData();
        formData.setId(row.id());
        formData.setFormDefId(row.formDefId());
        formData.setFormDefVersion(row.formDefVersion());
        formData.setData(mergedJson);
        long revisionId = versions.createRevision(formData, "SUBMITTED",
            RevisionReasons.ADMIN_CORRECTION, actorId);

        if (instance != null) {
            if (repository.updateInstancePointer(instance.id(), revisionId, instance.version()) == 0) {
                throw new BizException("CORRECTION_CONFLICT", "审批实例已变化，请重试");
            }
        }

        String fieldChanges = changesJson(row.data(), merged);
        long correctionId;
        if (pendingCorrectionId == null) {
            correctionId = repository.insertCorrection(row.id(), STATUS_APPLIED, revisionId,
                row.currentRevisionId(), instance == null ? null : instance.id(), actorId,
                normalizeReason(reasonCode), reasonText, fieldChanges, null, attachmentsTouched,
                row.companyId(), instance == null ? null : instance.status(), null);
        } else {
            // 复核路径：复用那条 PENDING 记录，回填修订指针，不新增一行
            if (repository.markApplied(pendingCorrectionId, revisionId, actorId, reasonText) == 0) {
                throw new BizException("CORRECTION_NOT_PENDING", "该修正已处理，不能重复复核");
            }
            correctionId = pendingCorrectionId;
        }

        audit.successAs(principal, "FORM_DATA_CORRECTION_APPLY", "FORM_DATA", row.id(),
            AuditService.RiskLevel.CRITICAL, readMap(fieldChanges),
            Map.of("correctionId", correctionId, "revisionId", revisionId));

        notifyCorrection(row, instance, actorId);
        return new Applied(correctionId, revisionId);
    }

    /** 通知提交人与最后审批人；操作者本人不重复收。 */
    private void notifyCorrection(FormDataCorrectionRepository.DataRow row,
                                  FormDataCorrectionRepository.InstanceRow instance,
                                  long actorId) {
        if (instance == null) return;
        String message = "单据 " + (row.businessNo() == null ? "#" + row.id() : row.businessNo())
            + " 的数据已被修正";
        Set<Long> recipients = new LinkedHashSet<>();
        if (row.createdBy() != null) recipients.add(row.createdBy());
        Long approver = repository.lastApprover(instance.id());
        if (approver != null) recipients.add(approver);
        recipients.remove(actorId);
        for (Long recipient : recipients) {
            notifications.publish(new NotificationEvent(this, "DATA_CORRECTED",
                instance.id(), null, recipient, message));
        }
    }

    private void rejectRunning(FormDataCorrectionRepository.InstanceRow instance) {
        // 审批中的内容归流程管：此时改数据会与节点回写（NODE_EDIT）打架，
        // 也会让审批人看到的内容与已审的内容不一致。
        if (instance != null && "RUNNING".equals(instance.status())) {
            throw new BizException("CORRECTION_INSTANCE_RUNNING",
                "该单据正在审批中，请先撤回或等待流程结束");
        }
    }

    /** 合并只允许白名单内的字段；不在白名单的按原因给出明确错误。 */
    private Map<String, Object> merge(String dataJson, Map<String, Object> fields,
                                      CorrectionFieldPolicy.Policy policy) {
        if (fields == null || fields.isEmpty()) {
            throw new BizException("CORRECTION_EMPTY", "没有需要修改的字段");
        }
        Map<String, Object> merged = readMap(dataJson);
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String fieldId = entry.getKey();
            if (policy.blocked().containsKey(fieldId)) {
                throw new BizException("CORRECTION_FIELD_FORBIDDEN",
                    "字段 " + fieldId + " 不允许事后修正（"
                        + policy.blocked().get(fieldId) + "）");
            }
            if (!policy.allowed().contains(fieldId)) {
                throw new BizException("CORRECTION_FIELD_UNKNOWN",
                    "字段 " + fieldId + " 不在该单据的表单版本内");
            }
            merged.put(fieldId, entry.getValue());
        }
        return merged;
    }

    private String changesJson(String beforeJson, Map<String, Object> after) {
        Map<String, Object> before = readMap(beforeJson);
        Map<String, Object> changes = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : after.entrySet()) {
            Object old = before.get(entry.getKey());
            if (!Objects.equals(old, entry.getValue())) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("before", old);
                row.put("after", entry.getValue());
                changes.put(entry.getKey(), row);
            }
        }
        return write(changes);
    }

    private String normalizeReason(String reasonCode) {
        if (reasonCode == null || !ALLOWED_REASONS.contains(reasonCode)) {
            throw new BizException("CORRECTION_REASON_INVALID", "请选择一个有效的修正原因");
        }
        return reasonCode;
    }

    private FormConfig formConfig(long formDefId) {
        FormConfig config = jdbc.query("""
            SELECT code, name, version,
                   COALESCE((settings #>> '{correction,reviewRequired}')::boolean, false),
                   settings::text
            FROM t_form_definition WHERE id = ?
            """, rs -> rs.next()
                ? new FormConfig(rs.getString(1), rs.getString(2), rs.getInt(3),
                    rs.getBoolean(4), rs.getString(5))
                : null, formDefId);
        if (config == null) throw new BizException("FORM_NOT_FOUND", "表单不存在");
        return config;
    }

    private Map<String, Object> readMap(String text) {
        if (text == null || text.isBlank()) return new LinkedHashMap<>();
        try {
            JsonNode node = json.readTree(text);
            if (!node.isObject()) return new LinkedHashMap<>();
            return json.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception exception) {
            throw new BizException("CORRECTION_DATA_INVALID", "单据内容不是合法 JSON");
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new BizException("CORRECTION_DATA_INVALID", "无法序列化修正后的数据");
        }
    }
}
