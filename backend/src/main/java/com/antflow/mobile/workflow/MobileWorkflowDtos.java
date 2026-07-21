package com.antflow.mobile.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

record MobileFileRef(UUID fileId, String fieldId, int sortOrder) {
}

record StartMobileInstanceRequest(String formCode, JsonNode data,
                                  Map<String, List<Long>> selfSelected,
                                  Long draftId, List<MobileFileRef> files) {
}

record MobileStartResult(Long instanceId, Long formDataId, List<Long> firstTaskIds) {
}

record MobileFormDto(String code, String name, Integer version, JsonNode schema) {
}

record MobileTaskActionRequest(String comment, String rejectToNodeId) {
}

record MobileDraftRequest(String formCode, JsonNode data) {
}

record MobileTaskDto(Long id, Long instanceId, String formName,
                     String applicantName, String applicantDepartment, String nodeName,
                     String taskStatus, String instanceStatus, OffsetDateTime createdAt) {
}

record MobileHistoryDto(Long id, String fromNodeId, String toNodeId, Long taskId,
                        String action, Long operatorId, String comment,
                        OffsetDateTime createdAt) {
}

record RejectTargetDto(String nodeId, String name) {
}

record MobileTaskDetailDto(MobileTaskDto task, JsonNode schema,
                           JsonNode formData, JsonNode processSnapshot,
                           List<MobileHistoryDto> history, List<String> allowedActions,
                           List<RejectTargetDto> rejectTargets, List<MobileFileDto> files) {
}

record MobileInstanceDto(Long id, String status, String formName, String currentNodeName,
                         OffsetDateTime startedAt, OffsetDateTime finishedAt) {
}

record MobileInstanceDetailDto(Long id, String status, String formName,
                               JsonNode schema, JsonNode formData, JsonNode processSnapshot,
                               List<MobileHistoryDto> history, boolean canWithdraw,
                               List<MobileFileDto> files) {
}
