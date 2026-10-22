package com.antflow.process;

import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 流程树的**结构**校验：唯一 ROOT、节点 ID 唯一、深度上限、条件/并行分支的形状、
 * 驳回目标必须是上游审批节点，以及"新流程必须显式声明节点级兜底"这道开关。
 *
 * <p>**为什么结构校验和单节点配置校验分开**：树这一层的规则由"流程可以怎么连"决定
 * （分支、汇聚、深度、ID 唯一），节点那一层由"某类节点必须填什么"决定；两者的评审人、
 * 变更节奏都不同。合在一起时，改一条 Webhook 规则要重读 300 行的递归骨架。
 *
 * <p>**深度上限为什么在规范化里也要用**：规范化同样会递归整棵树（{@code normalizeConditions}），
 * 只在校验里设限意味着一个人可以先用 10 万层的树把发布接口打进 StackOverflow——那时还没走到校验。
 * 所以 {@link #ensureTreeDepth} 是包内静态方法，两个入口共用同一份 {@link #MAX_TREE_DEPTH}。
 */
class ProcessTreeValidator {
    private static final int MAX_TREE_DEPTH = 50;

    private final ObjectMapper json;
    private final ProcessNodeValidator nodeValidator;
    private final ProcessApprovalRules approvalRules;

    ProcessTreeValidator(ObjectMapper json, ProcessNodeValidator nodeValidator,
                         ProcessApprovalRules approvalRules) {
        this.json = json;
        this.nodeValidator = nodeValidator;
        this.approvalRules = approvalRules;
    }

    /** Tree validation: ROOT is the unique root; APPROVAL has assignees configured;
     *  CONDITIONS has at least 1 branch including a default branch; node type is known. */
    void validateProcessTree(String processJson) {
        validateProcessTree(processJson, Map.of());
    }

    /** 带表单字段表校验流程树（节点级字段权限需要知道字段是否存在于当前表单）。 */
    void validateProcessTree(String processJson, Map<String, String> fieldTypes) {
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                json.readTree(processJson == null ? "{}" : processJson);
            if (!"ROOT".equals(root.path("type").asText())) {
                throw new BizException("BAD_FLOW", "流程必须以 ROOT 节点开始");
            }
            boolean nodeFallbackPolicy = hasNodeFallbackPolicy(root);
            if (!walk(root, new HashSet<>(), fieldTypes, 1, nodeFallbackPolicy)) {
                throw new BizException("BAD_FLOW", "审批流程至少需要 1 个审批节点");
            }
            validateRootSettings(root, nodeFallbackPolicy);
            approvalRules.validateFormPerms(root, fieldTypes, true);
            validateRejectTargets(root, root);
        } catch (BizException e) {
            throw e;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("BAD_FLOW_JSON", e.getMessage());
        }
    }

    private boolean walk(com.fasterxml.jackson.databind.JsonNode n, Set<String> ids,
                         Map<String, String> fieldTypes, int depth,
                         boolean nodeFallbackPolicy) {
        if (n == null || n.isNull() || !n.has("id")) return false;
        ensureTreeDepth(n, depth);
        registerId(n, ids);
        boolean hasApprovalNode = false;
        String type = n.path("type").asText();
        switch (type) {
            case "ROOT", "EMPTY" -> {}
            case "CC" -> nodeValidator.validateCc(n, fieldTypes);
            case "APPROVAL" -> {
                nodeValidator.validateApproval(n, fieldTypes, nodeFallbackPolicy);
                hasApprovalNode = true;
            }
            case "DELAY" -> nodeValidator.validateDelay(n);
            case "TRIGGER" -> nodeValidator.validateTrigger(n, fieldTypes);
            case "CONDITIONS" -> {
                com.fasterxml.jackson.databind.JsonNode branchs = n.path("branchs");
                if (!branchs.isArray() || branchs.size() < 1) {
                    throw new BizException("BAD_FLOW", "条件分支至少需要 1 个分支");
                }
                int defaultCount = 0;
                for (com.fasterxml.jackson.databind.JsonNode b : branchs) {
                    if (!"CONDITION".equals(b.path("type").asText())) {
                        throw new BizException("BAD_FLOW", "条件节点包含非法分支类型");
                    }
                    registerId(b, ids);
                    ensureTreeDepth(b, depth + 1);
                    if (b.path("props").path("isDefault").asBoolean(false)) {
                        defaultCount++;
                    } else {
                        nodeValidator.validateCondition(b);
                    }
                    hasApprovalNode = walk(b.path("children"), ids, fieldTypes, depth + 2,
                        nodeFallbackPolicy)
                        || hasApprovalNode;
                }
                if (defaultCount != 1) {
                    throw new BizException("BAD_FLOW", "条件分支必须且只能包含一个默认分支");
                }
            }
            case "CONDITION" -> {}
            case "PARALLEL" -> {
                String joinMode = n.path("props").path("joinMode").asText("ALL");
                if (!Set.of("ALL", "ANY", "AND", "OR").contains(joinMode)) {
                    throw new BizException("BAD_FLOW", "并行网关汇聚方式必须是 ALL 或 ANY");
                }
                com.fasterxml.jackson.databind.JsonNode branchs = n.path("branchs");
                if (!branchs.isArray() || branchs.size() < 2) {
                    throw new BizException("BAD_FLOW", "并行网关至少需要 2 个分支");
                }
                for (com.fasterxml.jackson.databind.JsonNode b : branchs) {
                    if (!"BRANCH".equals(b.path("type").asText())) {
                        throw new BizException("BAD_FLOW", "并行节点包含非法分支类型");
                    }
                    registerId(b, ids);
                    ensureTreeDepth(b, depth + 1);
                    String conditionMode = b.path("props").path("conditionMode")
                        .asText(null);
                    if ("ALWAYS".equals(conditionMode) || conditionMode == null) {
                        // Unconditional parallel branches are the current format;
                        // missing mode remains compatible with older snapshots.
                    } else if ("WHEN_MATCHED".equals(conditionMode)) {
                        // Drafts created by the short-lived conditional-parallel UI
                        // used WHEN_MATCHED with an empty groups array. Treat that
                        // shape as the current unconditional branch format.
                        var groups = b.path("props").path("groups");
                        boolean emptyGroups = groups.isArray() &&
                            groups.elements().hasNext() &&
                            java.util.stream.StreamSupport.stream(
                                java.util.Spliterators.spliteratorUnknownSize(
                                    groups.elements(), 0), false)
                                .allMatch(group -> group.path("conditions").isArray()
                                    && group.path("conditions").isEmpty());
                        if (!(groups.isArray() && (groups.isEmpty() || emptyGroups))) {
                            throw new BizException("BAD_FLOW", "并行分支执行方式无效");
                        }
                    } else {
                        throw new BizException("BAD_FLOW", "并行分支执行方式无效");
                    }
                    com.fasterxml.jackson.databind.JsonNode inner = b.path("children");
                    if (inner == null || inner.isNull() || !inner.has("id")) {
                        throw new BizException("BAD_FLOW", "并行分支不能为空");
                    }
                    hasApprovalNode = walkParallelBranch(inner, ids, fieldTypes, depth + 2,
                        nodeFallbackPolicy)
                        || hasApprovalNode;
                }
                if (n.path("children") == null || n.path("children").isNull()
                        || !n.path("children").has("id")) {
                    throw new BizException("BAD_FLOW", "并行网关必须配置汇聚后的后继节点");
                }
            }
            default -> throw new BizException("BAD_NODE_TYPE", "未知节点类型: " + type);
        }
        return walk(n.path("children"), ids, fieldTypes, depth + 1, nodeFallbackPolicy)
            || hasApprovalNode;
    }

    /** 并行分支沿完整流程树递归校验，允许嵌套分支和自动化节点。 */
    private boolean walkParallelBranch(com.fasterxml.jackson.databind.JsonNode n,
                                       Set<String> ids, Map<String, String> fieldTypes,
                                       int depth, boolean nodeFallbackPolicy) {
        return walk(n, ids, fieldTypes, depth, nodeFallbackPolicy);
    }

    static void ensureTreeDepth(com.fasterxml.jackson.databind.JsonNode node, int depth) {
        if (node != null && !node.isNull() && node.has("id") && depth > MAX_TREE_DEPTH) {
            throw new BizException("BAD_FLOW", "流程树深度不能超过 " + MAX_TREE_DEPTH + " 层");
        }
    }

    private void registerId(com.fasterxml.jackson.databind.JsonNode n, Set<String> ids) {
        String id = n.path("id").asText();
        if (id.isBlank() || !ids.add(id)) {
            throw new BizException("BAD_FLOW", "流程节点 ID 为空或重复: " + id);
        }
    }

    private void validateRootSettings(com.fasterxml.jackson.databind.JsonNode root,
                                      boolean nodeFallbackPolicy) {
        var settings = root.path("props").path("settings");
        if (!settings.isObject()) settings = root.path("props");
        String strategy = settings.path("resubmitStrategy").asText("FULL");
        if (!Set.of("FULL", "DIFF_CONTINUE").contains(strategy)) {
            throw new BizException("BAD_FLOW", "重提策略必须是 FULL 或 DIFF_CONTINUE");
        }
        var fallback = settings.path("fallbackAssignee");
        if (nodeFallbackPolicy && !fallback.isMissingNode() && !fallback.isNull()) {
            throw new BizException("BAD_FLOW", "流程级兜底已废止，请在每个审批节点配置转交对象");
        }
        if (!nodeFallbackPolicy && !fallback.isMissingNode() && !fallback.isNull()
            && (!fallback.isObject()
                || !Set.of("USER", "ROLE").contains(fallback.path("type").asText())
                || !fallback.path("ids").isArray() || fallback.path("ids").isEmpty())) {
            throw new BizException("BAD_FLOW", "兜底审批人必须配置用户或角色");
        }
    }

    private static boolean hasNodeFallbackPolicy(JsonNode root) {
        return "NODE_REQUIRED".equals(root.path("props").path("fallbackPolicy").asText());
    }

    /** New definitions must opt in to explicit, per-node fallback handling. */
    void requireNodeFallbackPolicy(String processJson) {
        try {
            JsonNode root = json.readTree(processJson == null ? "{}" : processJson);
            if (!hasNodeFallbackPolicy(root)) {
                throw new BizException("FALLBACK_CONFIGURATION_REQUIRED",
                    "流程尚未配置每个审批节点的兜底对象，请重新配置并发布");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("BAD_FLOW_JSON", e.getMessage());
        }
    }

    private void validateRejectTargets(com.fasterxml.jackson.databind.JsonNode root,
                                       com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull() || !node.has("id")) return;
        var targets = node.path("props").path("rejectTargets");
        if (!targets.isMissingNode() && !targets.isNull()) {
            if (!targets.isArray()) throw new BizException("BAD_FLOW", "驳回目标必须是数组");
            for (var value : targets) {
                String targetId = value.asText();
                var target = com.antflow.engine.tree.ProcessTreeNav.findById(root, targetId);
                if (target == null || !"APPROVAL".equals(target.path("type").asText())
                    || !com.antflow.engine.tree.ProcessTreeNav.isAncestor(
                        root, targetId, node.path("id").asText())) {
                    throw new BizException("BAD_FLOW", "驳回目标必须是当前节点的上游审批节点");
                }
            }
        }
        if (node.has("branchs")) {
            node.path("branchs").forEach(branch -> validateRejectTargets(root, branch));
        }
        validateRejectTargets(root, node.path("children"));
    }
}
