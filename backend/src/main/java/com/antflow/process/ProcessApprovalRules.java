package com.antflow.process;

import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 审批节点的**可选配置**规则：兜底对象、超时策略、节点字段权限、审批意见预设。
 *
 * <p>这些东西的共同点：不配也能跑，一旦配了就必须自洽。它们和"审批人填了没有"分开，
 * 是因为它们在产品上分属不同开关、由不同需求驱动，且其中字段权限的合法值依赖表单 schema
 * （由调用方传入字段类型表），与树结构无关。
 *
 * <p>审批意见预设的**校验**与**读取**必须放在一起：校验按 trim + 最长 100 字 + 最多 10 条 + 去重，
 * 读取时也必须按同一套规则裁剪（旧快照里可能存着不规范的值）。两边分开过一次，
 * 结果前端拿到 11 条、后端说最多 10 条，同一个快照两种口径。
 */
class ProcessApprovalRules {
    /** 附件/检查项等复杂字段在 v1 不支持审批节点编辑，只能隐藏或只读。 */
    private static final Set<String> EDITABLE_FORBIDDEN_TYPES = Set.of(
        "image_upload", "video_upload", "file_upload", "audio_upload", "location", "checklist");

    private final ObjectMapper json;

    ProcessApprovalRules(ObjectMapper json) {
        this.json = json;
    }

    void validateFallbackAssignee(com.fasterxml.jackson.databind.JsonNode node) {
        var fallback = node.path("props").path("fallbackAssignee");
        String type = fallback.path("type").asText();
        if (!fallback.isObject() || !Set.of("USER", "ROLE").contains(type)
            || !fallback.path("ids").isArray() || fallback.path("ids").isEmpty()) {
            throw new BizException("BAD_FLOW", "审批节点 " + node.path("id").asText()
                + " 必须配置转交指定人兜底对象");
        }
        for (var id : fallback.path("ids")) {
            boolean valid = id.isIntegralNumber() && id.asLong() > 0;
            if (!valid && id.isTextual()) {
                try { valid = Long.parseLong(id.asText()) > 0; }
                catch (NumberFormatException ignored) { }
            }
            if (!valid) {
                throw new BizException("BAD_FLOW", "审批节点 " + node.path("id").asText()
                    + " 的兜底对象包含无效 ID");
            }
        }
    }

    void validateTimeout(com.fasterxml.jackson.databind.JsonNode node) {
        var policy = node.path("props").path("timeoutPolicy");
        if (policy.isMissingNode() || policy.isNull()) return;
        long minutes = policy.path("afterMinutes").asLong(0);
        String action = policy.path("action").asText();
        if (!policy.isObject() || minutes < 1 || minutes > 525_600
            || !Set.of("REMIND", "ESCALATE", "AUTO_APPROVE").contains(action)) {
            throw new BizException("BAD_FLOW", "审批节点超时策略无效");
        }
        if ("AUTO_APPROVE".equals(action)
            && !"LOW".equals(policy.path("riskLevel").asText())) {
            throw new BizException("BAD_FLOW", "仅低风险流程允许超时自动通过");
        }
    }

    /** 校验节点级字段权限：字段必须存在于表单、模式合法、无重复；附件/检查项不可编辑。 */
    void validateFormPerms(com.fasterxml.jackson.databind.JsonNode n,
                           Map<String, String> fieldTypes,
                           boolean allowComplexEditable) {
        com.fasterxml.jackson.databind.JsonNode perms = n.path("props").path("formPerms");
        if (perms.isMissingNode() || perms.isNull()) {
            return;
        }
        if (!perms.isArray()) {
            throw new BizException("BAD_FLOW", nodeLabel(n) + " " + n.path("id").asText()
                + " 的 formPerms 必须是数组");
        }
        Set<String> seen = new HashSet<>();
        for (var entry : perms) {
            String fieldId = entry.path("fieldId").asText("").trim();
            String mode = entry.path("mode").asText("");
            if (fieldId.isBlank()) {
                throw new BizException("BAD_FLOW", nodeLabel(n) + " " + n.path("id").asText()
                    + " 存在缺少字段 id 的权限配置");
            }
            if (!Set.of("HIDDEN", "READONLY", "EDITABLE").contains(mode)) {
                throw new BizException("BAD_FLOW", nodeLabel(n) + " " + n.path("id").asText()
                    + " 字段 " + fieldId + " 的权限模式非法: " + mode);
            }
            if (!seen.add(fieldId)) {
                throw new BizException("BAD_FLOW", nodeLabel(n) + " " + n.path("id").asText()
                    + " 字段 " + fieldId + " 重复配置权限");
            }
            String type = fieldTypes.get(fieldId);
            if (type == null) {
                throw new BizException("BAD_FLOW", nodeLabel(n) + " " + n.path("id").asText()
                    + " 字段 " + fieldId + " 不存在于当前表单");
            }
            if (!allowComplexEditable && "EDITABLE".equals(mode)
                && EDITABLE_FORBIDDEN_TYPES.contains(type)) {
                throw new BizException("BAD_FLOW", nodeLabel(n) + " " + n.path("id").asText()
                    + " 字段 " + fieldId + "（" + type + "）暂不支持编辑");
            }
        }
    }

    private String nodeLabel(com.fasterxml.jackson.databind.JsonNode node) {
        return "ROOT".equals(node.path("type").asText()) ? "发起人节点" : "审批节点";
    }

    void validateCommentPresets(com.fasterxml.jackson.databind.JsonNode node) {
        var presets = node.path("props").path("commentPresets");
        if (presets.isMissingNode() || presets.isNull()) return;
        if (!presets.isObject()) {
            throw new BizException("BAD_FLOW", "审批意见预设必须是对象");
        }
        validateCommentPresetList(presets.path("approve"), "同意");
        validateCommentPresetList(presets.path("reject"), "驳回");
    }

    private void validateCommentPresetList(com.fasterxml.jackson.databind.JsonNode values,
                                           String action) {
        if (values.isMissingNode() || values.isNull()) return;
        if (!values.isArray() || values.size() > 10) {
            throw new BizException("BAD_FLOW", action + "意见预设最多 10 条");
        }
        Set<String> seen = new HashSet<>();
        for (var value : values) {
            String normalized = value.isTextual() ? value.asText().trim() : "";
            if (normalized.isBlank() || normalized.length() > 100) {
                throw new BizException("BAD_FLOW", action + "意见预设须为 1 到 100 字");
            }
            if (!seen.add(normalized)) {
                throw new BizException("BAD_FLOW", action + "意见预设不能重复");
            }
        }
    }

    ApprovalCommentPresets commentPresets(Object process, String nodeId) {
        if (process == null || nodeId == null || nodeId.isBlank()) {
            return ApprovalCommentPresets.empty();
        }
        try {
            var root = process instanceof com.fasterxml.jackson.databind.JsonNode node
                ? node : process instanceof String string
                    ? json.readTree(string) : json.valueToTree(process);
            var current = com.antflow.engine.tree.ProcessTreeNav.findById(root, nodeId);
            if (current == null) return ApprovalCommentPresets.empty();
            var presets = current.path("props").path("commentPresets");
            return new ApprovalCommentPresets(
                normalizedPresets(presets.path("approve")),
                normalizedPresets(presets.path("reject")));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new BizException("BAD_FLOW_JSON", error.getMessage());
        }
    }

    private List<String> normalizedPresets(com.fasterxml.jackson.databind.JsonNode values) {
        if (!values.isArray()) return List.of();
        var result = new java.util.LinkedHashSet<String>();
        for (var value : values) {
            String normalized = value.isTextual() ? value.asText().trim() : "";
            if (!normalized.isBlank() && normalized.length() <= 100 && result.size() < 10) {
                result.add(normalized);
            }
        }
        return List.copyOf(result);
    }
}
