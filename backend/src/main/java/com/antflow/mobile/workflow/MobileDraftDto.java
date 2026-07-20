package com.antflow.mobile.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

public record MobileDraftDto(Long id, Long formDefId, String formCode, String formName,
                             Integer formVersion, JsonNode data, boolean readOnly,
                             OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
