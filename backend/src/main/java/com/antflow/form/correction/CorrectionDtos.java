package com.antflow.form.correction;

import com.antflow.attachment.MobileFileRef;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 表单数据修正的请求/响应契约。字段名与前端 `shared/api.generated.d.ts` 对齐。 */
public final class CorrectionDtos {

    private CorrectionDtos() {
    }

    /**
     * 「可修正视图」：前端据此渲染弹窗。
     *
     * @param schema          **单据自己那一版**的 schema（不是表单最新版）
     * @param versionDrifted  单据版本落后于表单当前版本——UI 必须显式告知，
     *                        否则用户会以为看到的是最新表单
     * @param blockedFields   字段 id → 不可修改原因（CONDITION_FIELD / BUSINESS_NUMBER_FIELD）
     * @param baseRevisionId  并发基准，提交时必须原样回传
     * @param reviewRequired  该表单是否要求双人复核（表单级开关，默认关）
     */
    public record CorrectionFormView(
        long formDataId,
        long formDefId,
        String formCode,
        String formName,
        String businessNo,
        int formDefVersion,
        Integer latestFormVersion,
        boolean versionDrifted,
        boolean schemaApproximate,
        String schema,
        Object data,
        List<String> allowedFields,
        Map<String, String> blockedFields,
        Long baseRevisionId,
        String instanceStatus,
        boolean reviewRequired) {
    }

    /**
     * 提交一次修正。
     *
     * @param baseRevisionId 客户端拿到的并发基准；服务端比对不上即 409
     * @param fields         只传要改的字段（fieldId → 新值），未出现的字段保持不变
     * @param files          **null 表示不动附件**；非 null 表示用这份列表整体替换当前附件集合
     */
    public record CorrectionRequest(
        Long baseRevisionId,
        Map<String, Object> fields,
        String reasonCode,
        String reasonText,
        List<MobileFileRef> files) {
    }

    /** @param status APPLIED（已生效）或 PENDING_REVIEW（待复核） */
    public record CorrectionResult(long correctionId, String status, Long revisionId) {
    }

    /** 复核决定。 */
    public record ReviewRequest(boolean approve, String note) {
    }

    public record CorrectionView(
        long id,
        String status,
        String reasonCode,
        String reasonText,
        Map<String, Object> fieldChanges,
        boolean attachmentsChanged,
        String actorName,
        OffsetDateTime createdAt,
        String reviewerName,
        OffsetDateTime reviewedAt,
        String reviewNote) {
    }
}
