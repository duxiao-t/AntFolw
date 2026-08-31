package com.antflow.engine.tree;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

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

    /** True when ancestorId is on the structural path above nodeId. */
    public static boolean isAncestor(JsonNode root, String ancestorId, String nodeId) {
        List<JsonNode> path = new ArrayList<>();
        if (!findPath(root, nodeId, path)) return false;
        return path.stream().limit(Math.max(0, path.size() - 1L))
            .anyMatch(node -> ancestorId.equals(node.path("id").asText()));
    }

    /** Structural path nodes strictly between an ancestor and descendant. */
    public static List<String> nodesBetween(JsonNode root, String ancestorId,
                                            String descendantId) {
        List<JsonNode> path = new ArrayList<>();
        if (!findPath(root, descendantId, path)) return List.of();
        int ancestor = -1;
        for (int i = 0; i < path.size(); i++) {
            if (ancestorId.equals(path.get(i).path("id").asText())) {
                ancestor = i;
                break;
            }
        }
        if (ancestor < 0) return List.of();
        return path.subList(ancestor + 1, Math.max(ancestor + 1, path.size() - 1)).stream()
            .filter(node -> "APPROVAL".equals(node.path("type").asText()))
            .map(node -> node.path("id").asText()).toList();
    }

    /**
     * 返回节点的唯一顺序后继。条件分支走到末端时回到所属 CONDITIONS 的
     * children；到达当前并行网关边界时停止，等待其他分支。
     */
    public static JsonNode next(JsonNode root, JsonNode node, String parallelBoundaryId) {
        JsonNode child = childrenOf(node);
        if (child != null) return child;
        if (root == null || node == null) return null;
        List<JsonNode> path = new ArrayList<>();
        if (!findPath(root, node.path("id").asText(), path)) return null;
        for (int i = path.size() - 2; i >= 0; i--) {
            JsonNode ancestor = path.get(i);
            JsonNode ancestorChild = childrenOf(ancestor);
            boolean pathThroughChild = ancestorChild != null
                && ancestorChild.path("id").asText()
                    .equals(path.get(i + 1).path("id").asText());
            if (parallelBoundaryId != null
                && parallelBoundaryId.equals(ancestor.path("id").asText())
                && !pathThroughChild) {
                return null;
            }
            if ("CONDITIONS".equals(ancestor.path("type").asText())) {
                if (ancestorChild != null && !pathThroughChild) {
                    return ancestorChild;
                }
            }
            if ("PARALLEL".equals(ancestor.path("type").asText())
                && !pathThroughChild) return null;
        }
        return null;
    }

    private static boolean findPath(JsonNode node, String id, List<JsonNode> path) {
        if (node == null || node.isNull() || !node.has("id")) return false;
        path.add(node);
        if (id.equals(node.path("id").asText())) return true;
        if (isBranch(node)) {
            for (JsonNode branch : node.withArray("branchs")) {
                if (findPath(branch, id, path)) return true;
            }
        }
        if (findPath(node.get("children"), id, path)) return true;
        path.remove(path.size() - 1);
        return false;
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
        if (isBranch(node)) {
            for (JsonNode branch : node.withArray("branchs")) {
                ParallelParent parent = "PARALLEL".equals(node.path("type").asText())
                    ? new ParallelParent(node.path("id").asText(), branch.path("id").asText())
                    : current;
                ParallelParent hit = findParallelParent(branch, id, parent);
                if (hit != null) return hit;
            }
        }
        return findParallelParent(node.get("children"), id, current);
    }

    public record ParallelParent(String parallelId, String branchId) {}

    /** 当前节点是否位于指定并行网关的任意深层分支中。 */
    public static boolean isInsideParallel(JsonNode root, String parallelId, String nodeId) {
        ParallelParent parent = findParallelParent(root, nodeId);
        while (parent != null) {
            if (parallelId.equals(parent.parallelId())) return true;
            parent = findParallelParent(root, parent.parallelId());
        }
        return false;
    }

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
