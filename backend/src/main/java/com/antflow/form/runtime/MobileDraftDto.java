package com.antflow.form.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

public record MobileDraftDto(Long id, Long formDefId, String formCode, String formName,
                             Integer formVersion, JsonNode data, JsonNode schema, boolean readOnly,
                             // P4a 归属快照：确认页只读展示，提交时**不再重新解析**（见 CompanyAttribution.forDraft）。
                             @com.fasterxml.jackson.annotation.JsonInclude(
                                 com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
                             OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
