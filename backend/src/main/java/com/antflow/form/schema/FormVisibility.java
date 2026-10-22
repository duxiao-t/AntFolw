package com.antflow.form.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code displayCondition} 的运行时求值：给定 schema 与已填值，算出哪些节点可见。
 *
 * <p>为什么单独一个类：可见性是**一份语义，四个使用方**——提交校验（隐藏字段不校验）、
 * 首填投影（隐藏字段不下发）、首填规范化（隐藏字段不收值）、明细行内的嵌套求值。
 * 以前这四处的"可见"由同一个私有方法保证；拆开时若各带一份，迟早出现
 * "校验认为可见、投影认为隐藏"这种前后端都说不清的 bug。
 *
 * <p>两个易错点：
 * <ol>
 *   <li>环必须在这里给出**确定性结果**（环上节点按不可见处理），而不是抛错：存量已发布表单
 *       可能带环，提交路径拒绝会让那些表单当场填不了。环在保存/发布时由
 *       {@code FormSchemaValidator} 拦截。</li>
 *   <li>父节点不可见时子节点一律不可见，条件来源不可见时目标也不可见——所以递归要同时
 *       带 parent 与 source 两条链，不能只看节点自己的 props。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class FormVisibility {

    private final ObjectMapper json;

    public Set<String> visibleNodeIds(List<com.fasterxml.jackson.databind.JsonNode> roots,
                                       Map<?, ?> values) {
        var nodes = new java.util.LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        var parents = new java.util.HashMap<String, String>();
        roots.forEach(node -> collectNodes(node, null, nodes, parents));
        var visible = new java.util.LinkedHashSet<String>();
        var memo = new java.util.HashMap<String, Boolean>();
        nodes.keySet().forEach(id -> {
            if (resolveVisible(id, values, nodes, parents, memo, new java.util.HashSet<>())) {
                visible.add(id);
            }
        });
        return visible;
    }

    public Set<String> visibleNodeIds(JsonNode roots, Map<?, ?> values) {
        var nodes = new java.util.ArrayList<JsonNode>();
        if (roots.isArray()) roots.forEach(nodes::add);
        return visibleNodeIds(nodes, values);
    }

    private void collectNodes(com.fasterxml.jackson.databind.JsonNode node, String parentId,
                              Map<String, com.fasterxml.jackson.databind.JsonNode> nodes,
                              Map<String, String> parents) {
        String id = node.path("id").asText();
        nodes.put(id, node);
        if (parentId != null) parents.put(id, parentId);
        node.path("children").forEach(child -> collectNodes(child, id, nodes, parents));
    }

    private boolean resolveVisible(String id, Map<?, ?> values,
                                   Map<String, com.fasterxml.jackson.databind.JsonNode> nodes,
                                   Map<String, String> parents, Map<String, Boolean> memo,
                                   Set<String> visiting) {
        if (memo.containsKey(id)) return memo.get(id);
        if (!visiting.add(id)) return false;
        var node = nodes.get(id);
        String sourceId = node.path("props").path("displayCondition").path("fieldId")
            .asText(node.path("props").path("displayCondition").path("field").asText(""));
        boolean visible = (parents.get(id) == null
            || resolveVisible(parents.get(id), values, nodes, parents, memo, visiting))
            && (!nodes.containsKey(sourceId)
                || resolveVisible(sourceId, values, nodes, parents, memo, visiting))
            && isVisible(node, values);
        visiting.remove(id);
        memo.put(id, visible);
        return visible;
    }

    private boolean isVisible(com.fasterxml.jackson.databind.JsonNode node, Map<?, ?> values) {
        var props = node.path("props");
        if (props.path("hidden").asBoolean(false)) return false;
        var condition = props.path("displayCondition");
        String fieldId = condition.path("fieldId").asText(condition.path("field").asText(""));
        if (fieldId.isBlank()) return true;
        Object source = values.get(fieldId);
        var target = condition.path("value");
        return switch (condition.path("operator").asText("eq")) {
            case "in" -> target.isArray() && java.util.stream.StreamSupport.stream(target.spliterator(), false)
                .anyMatch(item -> sameValue(source, json.convertValue(item, Object.class)));
            case "ne", "!=", "!==" -> !sameValue(source, json.convertValue(target, Object.class));
            case "contains" -> source instanceof List<?> list
                ? list.stream().anyMatch(item -> sameValue(item, json.convertValue(target, Object.class)))
                : String.valueOf(source == null ? "" : source).contains(target.asText(""));
            case "empty" -> isEmpty(source);
            case "notEmpty" -> !isEmpty(source);
            case "gt", ">" -> compareNumbers(source, target, result -> result > 0);
            case "gte", ">=" -> compareNumbers(source, target, result -> result >= 0);
            case "lt", "<" -> compareNumbers(source, target, result -> result < 0);
            case "lte", "<=" -> compareNumbers(source, target, result -> result <= 0);
            default -> sameValue(source, json.convertValue(target, Object.class));
        };
    }

    private boolean sameValue(Object left, Object right) {
        return String.valueOf(left == null ? "" : left).equals(String.valueOf(right == null ? "" : right));
    }

    private boolean compareNumbers(Object source, com.fasterxml.jackson.databind.JsonNode target,
                                   java.util.function.IntPredicate predicate) {
        try {
            return predicate.test(new java.math.BigDecimal(String.valueOf(source)).compareTo(target.decimalValue()));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static boolean isEmpty(Object value) {
        return value == null
            || (value instanceof String s && s.isBlank())
            || (value instanceof List<?> list && list.isEmpty())
            || (value instanceof Map<?, ?> map && map.isEmpty());
    }
}
