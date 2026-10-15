package com.antflow.attachment;

import org.springframework.core.io.Resource;

public record MobileFileContent(MobileFileDto metadata, Resource resource) {
}
