package com.antflow.engine.condition;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 对 CONDITION_PROPS 相对表单数据求值。
 *
 * <p>CONDITION_PROPS 结构：
 * <pre>{@code
 * {
 *   "isDefault": false,        // true=默认分支，恒命中（忽略 groups）
 *   "groupsType": "OR",        // 组间关系 OR|AND
 *   "groups": [
 *     { "groupType": "AND",    // 组内关系 OR|AND
 *       "conditions": [
 *         { "field": "<formFieldId>", "operator": ">=", "value": 5000 }
 *       ]
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>当前仅依赖 Jackson 与 Spring 注解（{@link Component} 便于后续注入引擎），
 * 不访问数据库，可在单测中直接 new 使用。
 */
@Component
public class ConditionEvaluator {

    /**
     * 给定一个分支的 CONDITION_PROPS 与表单数据，判断该分支是否命中。
     * 默认分支永远命中；其余分支按 groupsType / groupType 递归求值。
     *
     * @param condProps 条件分支 props（CONDITION_PROPS）
     * @param formData  当前表单数据
     * @return true 表示该分支命中，应作为第一个命中分支被选中
     */
    public boolean matches(JsonNode condProps, JsonNode formData) {
        if (condProps.path("isDefault").asBoolean(false)) {
            return true;
        }
        JsonNode groups = condProps.path("groups");
        if (!groups.isArray() || groups.size() == 0) {
            return false;
        }
        boolean orGroups = !"AND".equals(condProps.path("groupsType").asText("OR"));
        boolean acc = !orGroups;   // AND 起点 true；OR 起点 false
        for (JsonNode g : groups) {
            boolean gv = evalGroup(g, formData);
            acc = orGroups ? (acc || gv) : (acc && gv);
        }
        return acc;
    }

    private boolean evalGroup(JsonNode group, JsonNode formData) {
        JsonNode conds = group.path("conditions");
        boolean orInner = !"AND".equals(group.path("groupType").asText("AND"));
        boolean acc = !orInner;
        if (!conds.isArray() || conds.size() == 0) {
            return true;
        }
        for (JsonNode c : conds) {
            boolean cv = evalOne(c, formData);
            acc = orInner ? (acc || cv) : (acc && cv);
        }
        return acc;
    }

    private boolean evalOne(JsonNode c, JsonNode formData) {
        String field = c.path("field").asText();
        String op = c.path("operator").asText();
        JsonNode expected = c.path("value");
        JsonNode actual = formData.path(field);
        return switch (op) {
            case "==" -> nodeEquals(actual, expected);
            case "!=" -> !nodeEquals(actual, expected);
            case ">"  -> cmp(actual, expected) > 0;
            case ">=" -> cmp(actual, expected) >= 0;
            case "<"  -> cmp(actual, expected) < 0;
            case "<=" -> cmp(actual, expected) <= 0;
            case "in" -> arrayContains(expected, actual);
            case "contains" -> arrayContains(actual, expected) || actual.asText().contains(expected.asText());
            default -> false;
        };
    }

    private boolean nodeEquals(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) {
            return cmp(a, b) == 0;
        }
        return a.asText().equals(b.asText());
    }

    private int cmp(JsonNode a, JsonNode b) {
        try {
            return new BigDecimal(a.asText()).compareTo(new BigDecimal(b.asText()));
        } catch (NumberFormatException e) {
            return a.asText().compareTo(b.asText());
        }
    }

    private boolean arrayContains(JsonNode arr, JsonNode v) {
        if (!arr.isArray()) {
            return false;
        }
        for (JsonNode x : arr) {
            if (nodeEquals(x, v)) {
                return true;
            }
        }
        return false;
    }
}