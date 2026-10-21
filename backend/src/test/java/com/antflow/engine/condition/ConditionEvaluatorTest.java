package com.antflow.engine.condition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纯逻辑组件测试 — 不依赖 Spring 上下文，也不连接数据库。
 */
class ConditionEvaluatorTest {

    private final ConditionEvaluator evaluator = new ConditionEvaluator();
    private final ObjectMapper om = new ObjectMapper();

    private JsonNode node(String json) throws Exception {
        return om.readTree(json);
    }

    @Test
    void gte_number_matches() throws Exception {
        JsonNode props = node("""
            { "groupsType":"OR", "groups":[
              { "groupType":"AND", "conditions":[
                { "field":"amount", "operator":">=", "value":5000 }
              ]}
            ]}
            """);
        JsonNode data = node("{ \"amount\":6000 }");
        assertThat(evaluator.matches(props, data)).isTrue();
    }

    @Test
    void gte_number_notMatch() throws Exception {
        JsonNode props = node("""
            { "groupsType":"OR", "groups":[
              { "groupType":"AND", "conditions":[
                { "field":"amount", "operator":">=", "value":5000 }
              ]}
            ]}
            """);
        JsonNode data = node("{ \"amount\":100 }");
        assertThat(evaluator.matches(props, data)).isFalse();
    }

    @Test
    void isDefault_alwaysMatches() throws Exception {
        JsonNode props = node("{ \"isDefault\": true }");
        JsonNode data = node("{}");
        assertThat(evaluator.matches(props, data)).isTrue();
    }

    /**
     * 关系比较遇到空值必须"不满足"。
     *
     * <p>回归 A8：cmp() 的兜底是按字典序比较，字段缺失时 `"" < "5000"` 会成立，
     * 于是"金额小于 5000 才走某分支"对未填字段误判为真。空值一律不满足，
     * 同时保留非空字符串的字典序比较（日期 `2026-01-01 < 2026-02-01` 依赖它）。
     */
    @Test
    void relationalOperatorsTreatBlankAsNotMatched() throws Exception {
        JsonNode lt = node("""
            { "groupsType":"OR", "groups":[
              { "groupType":"AND", "conditions":[
                { "field":"amount", "operator":"<", "value":5000 }
              ]}
            ]}
            """);
        assertThat(evaluator.matches(lt, node("{}"))).isFalse();               // 字段缺失
        assertThat(evaluator.matches(lt, node("{ \"amount\":\"\" }"))).isFalse(); // 空串
        assertThat(evaluator.matches(lt, node("{ \"amount\":\"  \" }"))).isFalse();
        assertThat(evaluator.matches(lt, node("{ \"amount\":100 }"))).isTrue();   // 正常数值

        JsonNode dateLt = node("""
            { "groupsType":"OR", "groups":[
              { "groupType":"AND", "conditions":[
                { "field":"day", "operator":"<", "value":"2026-02-01" }
              ]}
            ]}
            """);
        assertThat(evaluator.matches(dateLt, node("{ \"day\":\"2026-01-15\" }"))).isTrue();
        assertThat(evaluator.matches(dateLt, node("{ \"day\":\"2026-03-01\" }"))).isFalse();
    }

    @Test
    void in_operator() throws Exception {
        JsonNode props = node("""
            { "groupsType":"OR", "groups":[
              { "groupType":"AND", "conditions":[
                { "field":"city", "operator":"in", "value":["BJ","SH"] }
              ]}
            ]}
            """);
        assertThat(evaluator.matches(props, node("{ \"city\":\"SH\" }"))).isTrue();
        assertThat(evaluator.matches(props, node("{ \"city\":\"GZ\" }"))).isFalse();
    }

    @Test
    void in_operator_treatsLegacyScalarAsSingleton() throws Exception {
        JsonNode props = node("""
            { "groupsType":"OR", "groups":[
              { "groupType":"AND", "conditions":[
                { "field":"city", "operator":"in", "value":"BJ" }
              ]}
            ]}
            """);

        assertThat(evaluator.matches(props, node("{ \"city\":\"BJ\" }"))).isTrue();
        assertThat(evaluator.matches(props, node("{ \"city\":\"SH\" }"))).isFalse();
    }
}
