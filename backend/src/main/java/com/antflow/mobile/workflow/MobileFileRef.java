package com.antflow.mobile.workflow;

import java.util.UUID;

public record MobileFileRef(UUID fileId, String fieldId, int sortOrder) {
}
