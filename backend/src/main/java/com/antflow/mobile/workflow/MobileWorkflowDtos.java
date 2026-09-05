package com.antflow.mobile.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import com.antflow.process.ApprovalCommentPresets;

record StartMobileInstanceRequest(String formCode, JsonNode data,
                                  Map<String, List<Long>> selfSelected,
                                  Long draftId, List<MobileFileRef> files) {
}

record MobileStartResult(Long instanceId, Long formDataId, String businessNo,
                         List<Long> firstTaskIds) {
}

record MobileFormDto(String code, String name, Integer version, JsonNode schema,
                     JsonNode settings, JsonNode process,
                     Map<String, String> starterFieldModes) {
}

record ApprovalPreviewRequest(JsonNode data, Map<String, List<Long>> selfSelected,
                              Long reworkTaskId) {
}

record ApprovalPreviewDto(List<ApprovalPreviewNodeDto> nodes) {
}

record ApprovalPreviewNodeDto(String nodeId, String nodeName, String approvalMode,
                              boolean deferred,
                              List<ApprovalPreviewAssigneeDto> assignees) {
}

record ApprovalPreviewAssigneeDto(Long userId, String displayName) {
}

record MobileTaskActionRequest(String comment, JsonNode data, String rejectToNodeId) {
    MobileTaskActionRequest(String comment, JsonNode data) {
        this(comment, data, null);
    }
}

record MobileDraftRequest(String formCode, JsonNode data) {
}

record MobileTaskDto(Long id, Long instanceId, String nodeId, String formCode, String formName,
                     String businessNo, String applicantName, String applicantEmployeeNo,
                     String applicantDepartment, String nodeName, String taskType,
                     String taskStatus, String instanceStatus, OffsetDateTime createdAt,
                     OffsetDateTime readAt) {
}

record MobilePageDto<T>(List<T> items, boolean hasMore) {
}

record MobileHistoryDto(Long id, String fromNodeId, String toNodeId, Long taskId,
                        String action, Long operatorId, String comment,
                        OffsetDateTime createdAt) {
}

record RejectTargetDto(String nodeId, String name) {
}

record ApprovalSummaryDto(int flowedCount, int completedCount, int processingCount,
                          boolean complete) {
}

record ApprovalRecordDto(String id, Long taskId, String nodeId, String nodeName,
                         String recordKind, String nodeType, String parallelId,
                         String branchId, String operationKind, String sourceOperatorName,
                          String status, String operatorName, String employeeNo,
                          String department, String comment, OffsetDateTime receivedAt,
                          OffsetDateTime completedAt, Integer roundNo) {
}

record MobileTaskDetailDto(MobileTaskDto task, JsonNode schema,
                           JsonNode formData, JsonNode processSnapshot,
                           List<MobileHistoryDto> history, List<String> allowedActions,
                           boolean rejectDisabled,
                           List<RejectTargetDto> rejectTargets, List<MobileFileDto> files,
                           ApprovalSummaryDto approvalSummary,
                           List<ApprovalRecordDto> approvalRecords,
                           ApprovalCommentPresets commentPresets) {
}

record MobileInstanceDto(Long id, String status, String formName, String businessNo,
                          String currentNodeName,
                          OffsetDateTime startedAt, OffsetDateTime finishedAt) {
}

record MobileInitiatedDto(String kind, Long id, String status, String formName,
                          String businessNo, String currentNodeName,
                          OffsetDateTime startedAt, OffsetDateTime finishedAt) {
}

record MobileDirectSubmissionDetailDto(Long id, String status, String formCode,
                                       String formName, String businessNo,
                                       OffsetDateTime submittedAt, JsonNode schema,
                                       JsonNode formData, List<MobileFileDto> files) {
}

record MobileInstanceDetailDto(String visibility, Long id, String status, String formName,
                               String businessNo, String applicantName,
                               String applicantEmployeeNo, String applicantDepartment,
                               OffsetDateTime startedAt, String currentNodeName,
                               JsonNode schema, JsonNode formData, JsonNode processSnapshot,
                               List<MobileHistoryDto> history, boolean canWithdraw,
                               List<MobileFileDto> files,
                               ApprovalSummaryDto approvalSummary,
                               List<ApprovalRecordDto> approvalRecords) {
}

record ReworkTaskDto(Long taskId, Long instanceId, String formCode, String formName,
                     String businessNo, JsonNode schema, JsonNode formData,
                     JsonNode processSnapshot, List<MobileFileDto> files) {
}

record ReworkTaskRequest(JsonNode data, List<MobileFileRef> files) {
}

record ReworkResult(Long instanceId, Long formDataId, String businessNo,
                    List<Long> firstTaskIds) {
}
