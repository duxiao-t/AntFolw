package com.antflow.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 流程树 props 的纯策略读取（无状态、无 IO）。
 *
 * <p>这些方法原来散在 {@code WorkflowRuntimeV2} 里，被审批人解析、决策计算、并行分支三簇共用；
 * 放在任何一簇都会让另外两簇反向依赖它，所以单独提出来。**只读 JSON，不写库**。
 *
 * <p>两个易错点：
 * <ol>
 *   <li>{@link #mode} 把历史别名 {@code OR}/{@code AND} 归一成 {@code ANY}/{@code ALL}，
 *       但 {@code RATIO}/{@code SEQUENTIAL} 原样返回——不要"顺手"加默认分支，
 *       V1 存量流程里有只认 {@code OR}/{@code AND} 的节点。</li>
 *   <li>{@link #settings} 优先读 {@code props.settings}，缺失时**回落到 {@code props} 本身**；
 *       这条回落在 V1 老流程上生效，删掉会让老流程的 {@code skipStarterAsApprover} 全部失效。</li>
 * </ol>
 */
final class NodePolicies {

    private NodePolicies() { }

    static String mode(JsonNode node) {
        return switch (node.path("props").path("mode").asText("ANY")) {
            case "OR" -> "ANY";
            case "AND" -> "ALL";
            default -> node.path("props").path("mode").asText("ANY");
        };
    }

    static String joinMode(JsonNode node) {
        if (node == null) return "ALL";
        return switch (node.path("props").path("joinMode").asText("ALL")) {
            case "OR", "ANY" -> "ANY";
            default -> "ALL";
        };
    }

    static JsonNode settings(JsonNode root) {
        JsonNode nested = root.path("props").path("settings");
        return nested.isObject() ? nested : root.path("props");
    }

    static boolean hasNodeFallbackPolicy(JsonNode root) {
        return "NODE_REQUIRED".equals(root.path("props").path("fallbackPolicy").asText());
    }

    /** RATIO 模式的通过门槛：比例夹在 1..100，向上取整，至少 1 票。 */
    static int required(int total, JsonNode node) {
        int ratio = node.path("props").path("ratio").asInt(
            node.path("props").path("passRatio").asInt(100));
        return Math.max(1, (int) Math.ceil(total * Math.min(100, Math.max(1, ratio)) / 100.0));
    }

    static List<Long> readIds(JsonNode values) {
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        if (values.isArray()) values.forEach(value -> addLong(result, value));
        return new ArrayList<>(result);
    }

    /** 只接受整数与数字字符串；非法值静默丢弃（配置脏数据不能阻断审批）。 */
    static void addLong(LinkedHashSet<Long> result, JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return;
        if (value.isIntegralNumber()) result.add(value.asLong());
        else if (value.isTextual()) {
            try { result.add(Long.parseLong(value.asText())); }
            catch (NumberFormatException ignored) { }
        }
    }

    static OffsetDateTime timeoutAt(JsonNode node) {
        long minutes = node.path("props").path("timeoutPolicy").path("afterMinutes").asLong(0);
        return minutes > 0 ? OffsetDateTime.now().plusMinutes(minutes) : null;
    }
}
