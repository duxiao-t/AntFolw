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
}