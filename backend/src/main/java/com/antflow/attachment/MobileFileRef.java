package com.antflow.attachment;

import java.util.UUID;

public record MobileFileRef(UUID fileId, String fieldId, int sortOrder) {
}
