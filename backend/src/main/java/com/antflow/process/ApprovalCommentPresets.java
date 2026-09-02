package com.antflow.process;

import java.util.List;

public record ApprovalCommentPresets(List<String> approve, List<String> reject) {
    public static ApprovalCommentPresets empty() {
        return new ApprovalCommentPresets(List.of(), List.of());
    }
}
