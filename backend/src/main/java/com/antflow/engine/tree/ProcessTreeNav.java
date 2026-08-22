package com.antflow.engine.tree;

import com.fasterxml.jackson.databind.JsonNode;

/** 钉钉式流程树的只读遍历工具。 */
public final class ProcessTreeNav {
    private ProcessTreeNav() {}

    public static boolean isBranch(JsonNode n) {
        if (n == null) return false;
        String type = n.path("type").asText();
        return "CONDITIONS".equals(type) || "PARALLEL".equals(type);
    }

    public static boolean isEmpty(JsonNode n) {
        return n != null && "EMPTY".equals(n.path("type").asText());
    }

    /** 返回节点的唯一后继；无后继返回 null。 */
    public static JsonNode childrenOf(JsonNode n) {
        if (n == null) return null;
        JsonNode c = n.get("children");
        return (c == null || c.isNull() || !c.has("id")) ? null : c;
    }

    /** 在整棵树内按 id 查找节点（深度优先，含 branchs）。找不到返回 null。 */
    public static JsonNode findById(JsonNode node, String id) {
        if (node == null || node.isNull() || !node.has("id")) return null;
        if (id.equals(node.path("id").asText())) return node;
        if (isBranch(node)) {
            for (JsonNode b : node.withArray("branchs")) {
                JsonNode hit = findById(b, id);
                if (hit != null) return hit;
            }
        }
        return findById(node.get("children"), id);
    }

    public static boolean isInsideParallelBranch(JsonNode root, String id) {
        return isInsideParallelBranch(root, id, false);
    }

    /** Returns the nearest enclosing parallel branch for a nested node. */
    public static ParallelParent findParallelParent(JsonNode root, String id) {
        return findParallelParent(root, id, null);
    }

    private static ParallelParent findParallelParent(JsonNode node, String id,
                                                     ParallelParent current) {
        if (node == null || node.isNull() || !node.has("id")) return null;
        if (id.equals(node.path("id").asText())) return current;
        if ("PARALLEL".equals(node.path("type").asText())) {
            for (JsonNode branch : node.withArray("branchs")) {
                ParallelParent parent = new ParallelParent(
                    node.path("id").asText(), branch.path("id").asText());
                ParallelParent hit = findParallelParent(branch, id, parent);
                if (hit != null) return hit;
            }
        }
        return findParallelParent(node.get("children"), id, current);
    }

    public record ParallelParent(String parallelId, String branchId) {}

    private static boolean isInsideParallelBranch(JsonNode node, String id,
                                                  boolean insideParallelBranch) {
        if (node == null || node.isNull() || !node.has("id")) return false;
        if (id.equals(node.path("id").asText())) return insideParallelBranch;
        if (isBranch(node)) {
            boolean branchContext = insideParallelBranch
                || "PARALLEL".equals(node.path("type").asText());
            for (JsonNode branch : node.withArray("branchs")) {
                if (isInsideParallelBranch(branch, id, branchContext)) return true;
            }
        }
        return isInsideParallelBranch(node.get("children"), id, insideParallelBranch);
    }
}
