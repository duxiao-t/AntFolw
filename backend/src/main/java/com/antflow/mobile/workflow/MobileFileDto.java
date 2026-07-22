package com.antflow.mobile.workflow;

import java.util.UUID;

public record MobileFileDto(UUID id, String name, String contentType, long size, String contentUrl) {
}
