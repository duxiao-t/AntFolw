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

    /** 驳回待改的哨兵：`t_process_instance.current_node_id` 用它表示"等发起人改单"，不是真节点。 */
    public static final String REWORK_NODE_ID = "__rework__";

    /**
     * 节点显示名：`props.name` → `props.title` → `name` → 节点 id。
     * 展示节点时一律走这里，别把内部 id（`node_adurTht3` 这种）直接渲染给用户。
     */
    public static String displayName(JsonNode node) {
        if (node == null) return null;
        String name = node.path("props").path("name").asText(null);
        if (name == null || name.isBlank()) name = node.path("props").path("title").asText(null);
        if (name == null || name.isBlank()) name = node.path("name").asText(null);
        return name == null || name.isBlank() ? node.path("id").asText() : name;
    }

    /**
     * 从实例冻结的流程快照（JSONB 文本）解析节点显示名；快照缺失或解析失败时回退 id。
     * 列表类接口只该取一次快照、逐行调这个，而不是每行查一次库。
     */
    public static String displayNameFromSnapshot(String snapshotJson, String nodeId) {
        if (nodeId == null || nodeId.isBlank()) return null;
        if (snapshotJson == null || snapshotJson.isBlank()) return nodeId;
        try {
            return displayName(SNAPSHOT_JSON.readTree(snapshotJson), nodeId);
        } catch (Exception ignored) {
            return nodeId;
        }
    }

    /** 只用于读快照：ObjectMapper 的读取是线程安全的，复用同一个实例。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper SNAPSHOT_JSON =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /** 按 id 取节点显示名；找不到节点时回退 id。哨兵给专门的中文文案。 */
    public static String displayName(JsonNode root, String nodeId) {
        if (nodeId == null || nodeId.isBlank()) return null;
        if (REWORK_NODE_ID.equals(nodeId)) return "待修改原单";
        JsonNode node = findById(root, nodeId);
        return node == null ? nodeId : displayName(node);
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
