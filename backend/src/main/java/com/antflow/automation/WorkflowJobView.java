package com.antflow.automation;

import java.time.OffsetDateTime;
import java.util.UUID;

public record WorkflowJobView(
    Long id,
    String nodeId,
    String jobType,
    String status,
    OffsetDateTime scheduledAt,
    int attempts,
    int maxAttempts,
    UUID deliveryId,
    boolean blocking,
    String lastError,
    OffsetDateTime completedAt,
    OffsetDateTime createdAt
) {
    public static WorkflowJobView from(WorkflowJob job) {
        return new WorkflowJobView(
            job.getId(), job.getNodeId(), job.getJobType(), job.getStatus(),
            job.getScheduledAt(), value(job.getAttempts()), value(job.getMaxAttempts()),
            job.getDeliveryId(), Boolean.TRUE.equals(job.getBlocking()),
            redactError(job.getLastError()), job.getCompletedAt(), job.getCreatedAt()
        );
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static String redactError(String error) {
        if (error == null) return null;
        String sanitized = error.replaceAll("(?i)(secret|authorization|token)=[^\\s,]+", "$1=***");
        return sanitized.length() <= 500 ? sanitized : sanitized.substring(0, 500);
    }
}
