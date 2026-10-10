package com.antflow.mobile.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

public record MobileDraftDto(Long id, Long formDefId, String formCode, String formName,
                             Integer formVersion, JsonNode data, JsonNode schema, boolean readOnly,
                             // P4a 归属快照：确认页只读展示，提交时**不再重新解析**（见 CompanyAttribution.forDraft）。
                             Long companyId, Boolean companyInferred,
                             OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
