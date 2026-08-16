package com.antflow.process;

import com.antflow.engine.BizException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validates the recursive process-tree invariant introduced by the
 * 钉钉式流程改造 (Task 3). No Postgres / Spring context required —
 * we construct the service directly with a null mapper because
 * {@code validateProcessTree} is pure.
 */
class ProcessDefinitionServiceValidationTest {

    private final ProcessDefinitionService service =
        new ProcessDefinitionService(null, null, new ObjectMapper());

    @Test void validate_rejects_approval_without_assignee() {
        String tree = """
            {"id":"root","type":"ROOT","props":{},"children":{"id":"a1","type":"APPROVAL",
              "props":{"assignedType":"ASSIGN_USER","assignedUser":[]}}}""";
        assertThatThrownBy(() -> service.validateProcessTree(tree))
            .isInstanceOf(BizException.class)
            .matches(e -> "BAD_FLOW".equals(((BizException) e).getCode()));
    }

    @Test void validate_rejects_conditions_without_default_branch() {
        String tree = """
            {"id":"root","type":"ROOT","props":{},"children":{"id":"c1","type":"CONDITIONS",
              "props":{},"branchs":[
                {"id":"b1","type":"CONDITION","props":{"isDefault":false,"groups":[]},
                 "children":null}
              ]}}""";
        assertThatThrownBy(() -> service.validateProcessTree(tree))
            .isInstanceOf(BizException.class)
            .matches(e -> "BAD_FLOW".equals(((BizException) e).getCode()));
    }

    @Test void validate_rejects_tree_without_approval_node() {
        String tree = """
            {"id":"root","type":"ROOT","props":{},"children":null}""";
        assertThatThrownBy(() -> service.validateProcessTree(tree))
            .isInstanceOf(BizException.class)
            .matches(e -> "BAD_FLOW".equals(((BizException) e).getCode()));
    }

    @Test void validate_accepts_wellformed_tree() {
        String tree = """
            {"id":"root","type":"ROOT","props":{},"children":{"id":"a1","type":"APPROVAL",
              "props":{"assignedType":"ASSIGN_USER","assignedUser":[1]},"children":null}}""";
        assertThatCode(() -> service.validateProcessTree(tree)).doesNotThrowAnyException();
    }

    @Test void validate_accepts_delay_and_trigger_with_complete_configuration() {
        String tree = """
            {"id":"root","type":"ROOT","children":{"id":"a1","type":"APPROVAL",
              "props":{"assignedType":"SELF"},"children":{"id":"d1","type":"DELAY",
              "props":{"mode":"DURATION","amount":1,"unit":"HOURS"},"children":{
              "id":"t1","type":"TRIGGER","props":{"method":"POST",
              "url":"https://hooks.example.com/flow","contentType":"application/json",
              "continueMode":"ON_SUCCESS","secret":"12345678","headers":[],"parameters":[]}}}}}
            """;
        assertThatCode(() -> service.validateProcessTree(tree)).doesNotThrowAnyException();
    }

    @Test void validate_rejects_async_node_inside_parallel_branch() {
        String tree = """
            {"id":"root","type":"ROOT","children":{"id":"p1","type":"PARALLEL",
              "branchs":[
                {"id":"b1","type":"BRANCH","children":{"id":"d1","type":"DELAY",
                  "props":{"mode":"DURATION","amount":1,"unit":"HOURS"}}},
                {"id":"b2","type":"BRANCH","children":{"id":"a1","type":"APPROVAL",
                  "props":{"assignedType":"SELF"}}}
              ],"children":{"id":"join","type":"EMPTY"}}}
            """;
        assertThatThrownBy(() -> service.validateProcessTree(tree))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("只允许审批/抄送");
    }

    @Test void validate_accepts_conditional_parallel_branch_with_always_fallback() {
        String tree = """
            {"id":"root","type":"ROOT","children":{"id":"p1","type":"PARALLEL",
              "branchs":[
                {"id":"b1","type":"BRANCH","props":{"conditionMode":"WHEN_MATCHED",
                  "groupsType":"OR","groups":[{"groupType":"AND","conditions":[
                    {"field":"amount","operator":">","value":"100"}]}]},
                  "children":{"id":"a1","type":"APPROVAL","props":{"assignedType":"SELF"}}},
                {"id":"b2","type":"BRANCH","props":{"conditionMode":"ALWAYS"},
                  "children":{"id":"a2","type":"APPROVAL","props":{"assignedType":"SELF"}}}
              ],"children":{"id":"join","type":"EMPTY"}}}
            """;
        assertThatCode(() -> service.validateProcessTree(tree)).doesNotThrowAnyException();
    }

    @Test void validate_accepts_nonempty_array_for_in_condition() {
        String tree = conditionalTree("[\"BJ\",\"SH\"]");

        assertThatCode(() -> service.validateProcessTree(tree)).doesNotThrowAnyException();
    }

    @Test void validate_rejects_scalar_for_in_condition() {
        String tree = conditionalTree("\"BJ\"");

        assertThatThrownBy(() -> service.validateProcessTree(tree))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("条件表达式配置不完整");
    }

    @Test void validate_rejects_parallel_without_always_branch() {
        String tree = """
            {"id":"root","type":"ROOT","children":{"id":"p1","type":"PARALLEL",
              "branchs":[
                {"id":"b1","type":"BRANCH","props":{"conditionMode":"WHEN_MATCHED",
                  "groups":[{"groupType":"AND","conditions":[
                    {"field":"amount","operator":">","value":"100"}]}]},
                  "children":{"id":"a1","type":"APPROVAL","props":{"assignedType":"SELF"}}},
                {"id":"b2","type":"BRANCH","props":{"conditionMode":"WHEN_MATCHED",
                  "groups":[{"groupType":"AND","conditions":[
                    {"field":"amount","operator":"<=","value":"100"}]}]},
                  "children":{"id":"a2","type":"APPROVAL","props":{"assignedType":"SELF"}}}
              ],"children":{"id":"join","type":"EMPTY"}}}
            """;
        assertThatThrownBy(() -> service.validateProcessTree(tree))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("始终执行");
    }

    @Test void validate_accepts_formPerms_for_existing_fields() {
        String tree = """
            {"id":"root","type":"ROOT","children":{"id":"a1","type":"APPROVAL",
              "props":{"assignedType":"ASSIGN_USER","assignedUser":[1],
                "formPerms":[
                  {"fieldId":"amount","mode":"EDITABLE"},
                  {"fieldId":"secret","mode":"HIDDEN"}
                ]},"children":null}}
            """;

        assertThatCode(() -> service.validateProcessTree(tree,
            Map.of("amount", "number", "secret", "text")))
            .doesNotThrowAnyException();
    }

    @Test void validate_rejects_formPerms_for_unknown_field() {
        String tree = approvalWithPerms("[{\"fieldId\":\"ghost\",\"mode\":\"EDITABLE\"}]");

        assertThatThrownBy(() -> service.validateProcessTree(tree,
            Map.of("amount", "number")))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("不存在于当前表单");
    }

    @Test void validate_rejects_formPerms_with_invalid_mode() {
        String tree = approvalWithPerms("[{\"fieldId\":\"amount\",\"mode\":\"WRITE\"}]");

        assertThatThrownBy(() -> service.validateProcessTree(tree,
            Map.of("amount", "number")))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("权限模式非法");
    }

    @Test void validate_rejects_formPerms_with_duplicate_field() {
        String tree = approvalWithPerms("""
            [{"fieldId":"amount","mode":"EDITABLE"},
             {"fieldId":"amount","mode":"HIDDEN"}]
            """);

        assertThatThrownBy(() -> service.validateProcessTree(tree,
            Map.of("amount", "number")))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("重复配置权限");
    }

    @Test void validate_rejects_editable_attachment_field() {
        String tree = approvalWithPerms("[{\"fieldId\":\"proof\",\"mode\":\"EDITABLE\"}]");

        assertThatThrownBy(() -> service.validateProcessTree(tree,
            Map.of("proof", "file_upload")))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("暂不支持编辑");
    }

    @Test void validate_rejects_formPerms_that_is_not_array() {
        String tree = """
            {"id":"root","type":"ROOT","children":{"id":"a1","type":"APPROVAL",
              "props":{"assignedType":"ASSIGN_USER","assignedUser":[1],
                "formPerms":{"amount":"EDITABLE"}},"children":null}}
            """;

        assertThatThrownBy(() -> service.validateProcessTree(tree,
            Map.of("amount", "number")))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("必须是数组");
    }

    private static String approvalWithPerms(String formPerms) {
        return """
            {"id":"root","type":"ROOT","children":{"id":"a1","type":"APPROVAL",
              "props":{"assignedType":"ASSIGN_USER","assignedUser":[1],
                "formPerms":%s},"children":null}}
            """.formatted(formPerms);
    }

    @Test void findByForm_returns_current_definition_including_draft() {
        ProcessDefinitionMapper mapper = Mockito.mock(ProcessDefinitionMapper.class);
        ProcessDefinitionService service =
            new ProcessDefinitionService(mapper, null, new ObjectMapper());
        ProcessDefinition draft = new ProcessDefinition();
        draft.setId(12L);
        draft.setFormDefId(7L);
        draft.setStatus("DRAFT");
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(draft);

        ProcessDefinition result = service.findByForm(7L);

        assertThat(result).isSameAs(draft);
        verify(mapper).selectOne(any(QueryWrapper.class));
    }

    @Test void saveOrUpdateDraft_turns_published_process_back_to_draft() {
        ProcessDefinitionMapper mapper = Mockito.mock(ProcessDefinitionMapper.class);
        ProcessDefinitionService service =
            new ProcessDefinitionService(mapper, null, new ObjectMapper());
        ProcessDefinition published = new ProcessDefinition();
        published.setId(12L);
        published.setFormDefId(7L);
        published.setVersion(2);
        published.setStatus("PUBLISHED");
        when(mapper.selectById(12L)).thenReturn(published);
        Map<String, Object> process = new HashMap<>();
        process.put("id", "root");
        process.put("type", "ROOT");
        process.put("children", null);

        ProcessDefinition result = service.saveOrUpdateDraft(
            12L,
            7L,
            process,
            1L
        );

        assertThat(result.getStatus()).isEqualTo("DRAFT");
        verify(mapper).updateById(published);
    }

    private static String conditionalTree(String value) {
        return """
            {"id":"root","type":"ROOT","children":{"id":"c1","type":"CONDITIONS",
              "branchs":[
                {"id":"b1","type":"CONDITION","props":{"groups":[{"groupType":"AND",
                  "conditions":[{"field":"city","operator":"in","value":%s}]}]},
                  "children":{"id":"a1","type":"APPROVAL","props":{"assignedType":"SELF"}}},
                {"id":"b2","type":"CONDITION","props":{"isDefault":true},"children":null}
              ],"children":null}}
            """.formatted(value);
    }
}
