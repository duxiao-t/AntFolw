package com.antflow.engine.tree;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉钉式流程树遍历工具测试。不依赖数据库或 Spring。
 */
class ProcessTreeNavTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test void childrenOfReturnsNullWhenNoChildren() {
        JsonNode leaf = parse("{\"id\":\"end\",\"type\":\"APPROVAL\"}");
        assertThat(ProcessTreeNav.childrenOf(leaf)).isNull();
    }

    @Test void isBranchTrueForConditions() {
        JsonNode conditions = parse("{\"id\":\"c1\",\"type\":\"CONDITIONS\"}");
        assertThat(ProcessTreeNav.isBranch(conditions)).isTrue();
    }

    @Test void findByIdWalksChildrenAndBranchs() {
        // ROOT -> CONDITIONS(branchs: [CONDITION -> a1], children: a2)
        String json = """
            {
              "id": "root",
              "type": "ROOT",
              "children": {
                "id": "c1",
                "type": "CONDITIONS",
                "branchs": [
                  {
                    "id": "b1",
                    "type": "CONDITION",
                    "children": {
                      "id": "a1",
                      "type": "APPROVAL"
                    }
                  }
                ],
                "children": {
                  "id": "a2",
                  "type": "APPROVAL"
                }
              }
            }
            """;
        JsonNode root = parse(json);

        JsonNode a1 = ProcessTreeNav.findById(root, "a1");
        assertThat(a1).isNotNull();
        assertThat(a1.path("id").asText()).isEqualTo("a1");

        JsonNode a2 = ProcessTreeNav.findById(root, "a2");
        assertThat(a2).isNotNull();
        assertThat(a2.path("id").asText()).isEqualTo("a2");

        assertThat(ProcessTreeNav.findById(root, "nope")).isNull();
    }

    @Test void nextJoinsConditionBranchAndNestedCondition() {
        JsonNode root = parse("""
            {"id":"root","type":"ROOT","children":{"id":"outer","type":"CONDITIONS",
              "branchs":[{"id":"ob","type":"CONDITION","children":{
                "id":"inner","type":"CONDITIONS","branchs":[{
                  "id":"ib","type":"CONDITION","children":{"id":"leaf","type":"APPROVAL"}}]}}],
              "children":{"id":"join","type":"APPROVAL"}}}
            """);

        assertThat(ProcessTreeNav.next(root, ProcessTreeNav.findById(root, "leaf"), null)
            .path("id").asText()).isEqualTo("join");
    }

    @Test void nextStopsAtParallelBoundaryButAllowsConditionJoinInsideIt() {
        JsonNode root = parse("""
            {"id":"root","type":"ROOT","children":{"id":"p","type":"PARALLEL",
              "branchs":[{"id":"pb","type":"BRANCH","children":{
                "id":"c","type":"CONDITIONS","branchs":[{
                  "id":"cb","type":"CONDITION","children":{"id":"leaf","type":"EMPTY"}}],
                "children":{"id":"insideJoin","type":"EMPTY"}}}],
              "children":{"id":"outsideJoin","type":"APPROVAL"}}}
            """);

        JsonNode insideJoin = ProcessTreeNav.next(root,
            ProcessTreeNav.findById(root, "leaf"), "p");
        assertThat(insideJoin.path("id").asText()).isEqualTo("insideJoin");
        assertThat(ProcessTreeNav.next(root, insideJoin, "p")).isNull();
    }
}
