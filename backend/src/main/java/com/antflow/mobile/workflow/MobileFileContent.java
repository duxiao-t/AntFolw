package com.antflow.mobile.workflow;

import org.springframework.core.io.Resource;

public record MobileFileContent(MobileFileDto metadata, Resource resource) {
}
