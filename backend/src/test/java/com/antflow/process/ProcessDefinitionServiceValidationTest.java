package com.antflow.process;

import com.antflow.engine.BizException;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
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

    @Test void validate_rejects_sequential_approval_mode_for_new_definitions() {
        String tree = """
            {"id":"root","type":"ROOT","children":{"id":"a1","type":"APPROVAL",
              "props":{"assignedType":"SELF","mode":"SEQUENTIAL"},"children":null}}
            """;

        assertThatThrownBy(() -> service.validateProcessTree(tree))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("多人审批方式无效");
    }

    @Test void normalize_condition_keeps_numeric_option_value() throws Exception {
        String normalized = service.normalizeConditionValues(
            optionCondition("==", "2"), optionSchema(false));

        assertThat(new ObjectMapper().readTree(normalized)
            .at("/children/branchs/0/props/groups/0/conditions/0/value").isNumber())
            .isTrue();
    }

    @Test void normalize_condition_converts_unique_legacy_label() throws Exception {
        String normalized = service.normalizeConditionValues(
            optionCondition("==", "\"选项二\""), optionSchema(false));

        assertThat(new ObjectMapper().readTree(normalized)
            .at("/children/branchs/0/props/groups/0/conditions/0/value").asInt())
            .isEqualTo(2);
    }

    @Test void normalize_condition_rejects_duplicate_or_unknown_label() {
        assertThatThrownBy(() -> service.normalizeConditionValues(
            optionCondition("==", "\"选项二\""), optionSchema(true)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("标签重复");
        assertThatThrownBy(() -> service.normalizeConditionValues(
            optionCondition("==", "\"不存在\""), optionSchema(false)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("选项不存在");
    }

    @Test void normalize_condition_rejects_dynamic_other_option() {
        assertThatThrownBy(() -> service.normalizeConditionValues(
            optionCondition("==", "\"其他\""), optionSchema(false)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("不能作为流程条件");
    }

    @Test void normalize_removes_only_form_permissions_for_deleted_fields() throws Exception {
        String tree = """
            {"id":"root","type":"ROOT","children":{"id":"a1","type":"APPROVAL",
              "props":{"assignedType":"SELF","formPerms":[
                {"fieldId":"deleted","mode":"HIDDEN"},
                {"fieldId":"kind","mode":"EDITABLE"}]}}}
            """;

        JsonNode normalized = new ObjectMapper().readTree(
            service.normalizeConditionValuesForPublish(tree, optionSchema(false)));

        assertThat(normalized.at("/children/props/formPerms")).hasSize(1);
        assertThat(normalized.at("/children/props/formPerms/0/fieldId").asText())
            .isEqualTo("kind");
    }

    @Test void publish_normalization_repairs_stale_permission_after_condition_reconfigured()
            throws Exception {
        String tree = """
            {"id":"root","type":"ROOT","children":{"id":"conditions",
              "type":"CONDITIONS","branchs":[
                {"id":"matched","type":"CONDITION","props":{"groups":[{
                  "conditions":[{"field":"subject","operator":"==","value":"yes"}]}]},
                  "children":{"id":"a1","type":"APPROVAL",
                    "props":{"assignedType":"SELF"}}},
                {"id":"default","type":"CONDITION","props":{"isDefault":true},
                  "children":{"id":"a2","type":"APPROVAL",
                    "props":{"assignedType":"SELF","formPerms":[
                      {"fieldId":"deleted-select","mode":"HIDDEN"}]}}}]}}
            """;
        String schema = "[{\"id\":\"subject\",\"type\":\"text\"}]";

        String normalized = service.normalizeConditionValuesForPublish(tree, schema);

        JsonNode process = new ObjectMapper().readTree(normalized);
        assertThat(process.at(
            "/children/branchs/1/children/props/formPerms")).isEmpty();
        assertThatCode(() -> service.validateProcessTree(normalized,
            Map.of("subject", "text"))).doesNotThrowAnyException();
    }

    @Test void normalize_rejects_condition_referencing_deleted_field() {
        String tree = optionCondition("==", "2")
            .replace("\"field\":\"kind\"", "\"field\":\"deleted\"");

        assertThatThrownBy(() ->
            service.normalizeConditionValuesForPublish(tree, optionSchema(false)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("条件分支")
            .hasMessageContaining("已删除的表单字段 deleted");
    }

    @Test void runtime_normalization_keeps_snapshot_references_for_inflight_instances()
            throws Exception {
        String tree = optionCondition("==", "2")
            .replace("\"field\":\"kind\"", "\"field\":\"deleted\"");

        JsonNode normalized = new ObjectMapper().readTree(
            service.normalizeConditionValues(tree, optionSchema(false)));

        assertThat(normalized.at(
            "/children/branchs/0/props/groups/0/conditions/0/field").asText())
            .isEqualTo("deleted");
    }

    @Test void normalize_multi_select_requires_contains_and_preserves_value_type() throws Exception {
        String schema = optionSchema(false).replace("\"select\"", "\"multi_select\"");
        String normalized = service.normalizeConditionValues(
            optionCondition("contains", "2"), schema);
        assertThat(new ObjectMapper().readTree(normalized)
            .at("/children/branchs/0/props/groups/0/conditions/0/value").asInt())
            .isEqualTo(2);
        assertThatThrownBy(() -> service.normalizeConditionValues(
            optionCondition("==", "2"), schema))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("仅支持包含");
    }

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

    @Test void validate_accepts_async_node_inside_parallel_branch() {
        String tree = """
            {"id":"root","type":"ROOT","children":{"id":"p1","type":"PARALLEL",
              "branchs":[
                {"id":"b1","type":"BRANCH","children":{"id":"d1","type":"DELAY",
                  "props":{"mode":"DURATION","amount":1,"unit":"HOURS"}}},
                {"id":"b2","type":"BRANCH","children":{"id":"a1","type":"APPROVAL",
                  "props":{"assignedType":"SELF"}}}
              ],"children":{"id":"join","type":"EMPTY"}}}
            """;
        assertThatCode(() -> service.validateProcessTree(tree)).doesNotThrowAnyException();
    }

    @Test void validate_rejects_conditional_parallel_branch() {
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
        assertThatThrownBy(() -> service.validateProcessTree(tree))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("执行方式无效");
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

    @Test void validate_accepts_parallel_with_always_branches() {
        String tree = """
            {"id":"root","type":"ROOT","children":{"id":"p1","type":"PARALLEL",
              "branchs":[
                {"id":"b1","type":"BRANCH","props":{"conditionMode":"ALWAYS"},
                  "children":{"id":"a1","type":"APPROVAL","props":{"assignedType":"SELF"}}},
                {"id":"b2","type":"BRANCH","props":{"conditionMode":"ALWAYS"},
                  "children":{"id":"a2","type":"APPROVAL","props":{"assignedType":"SELF"}}}
              ],"children":{"id":"join","type":"EMPTY"}}}
            """;
        assertThatCode(() -> service.validateProcessTree(tree)).doesNotThrowAnyException();
    }

    @Test void validate_rejects_webhook_parameter_referencing_deleted_field() {
        String tree = """
            {"id":"root","type":"ROOT","children":{"id":"a1","type":"APPROVAL",
              "props":{"assignedType":"SELF"},"children":{"id":"t1","type":"TRIGGER",
              "props":{"method":"POST","url":"https://hooks.example.com/flow",
              "contentType":"application/json","continueMode":"ON_SUCCESS",
              "secret":"12345678","headers":[],"parameters":[
                {"key":"amount","source":"FIELD","fieldId":"deleted"}]}}}}
            """;

        assertThatThrownBy(() -> service.validateProcessTree(tree,
            Map.of("amount", "number")))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("Webhook 节点 t1")
            .hasMessageContaining("已删除的表单字段 deleted");
    }

    @Test void validate_self_select_multiple_is_boolean_when_present() {
        String missing = """
            {"id":"root","type":"ROOT","children":{"id":"a1","type":"APPROVAL",
              "props":{"assignedType":"SELF_SELECT"}}}
            """;
        String multiple = missing.replace("\"assignedType\":\"SELF_SELECT\"",
            "\"assignedType\":\"SELF_SELECT\",\"selfSelect\":{\"multiple\":true}");
        String invalid = missing.replace("\"assignedType\":\"SELF_SELECT\"",
            "\"assignedType\":\"SELF_SELECT\",\"selfSelect\":{\"multiple\":\"true\"}");

        assertThatCode(() -> service.validateProcessTree(missing)).doesNotThrowAnyException();
        assertThatCode(() -> service.validateProcessTree(multiple)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.validateProcessTree(invalid))
            .isInstanceOf(BizException.class).hasMessageContaining("multiple 必须是布尔值");
    }

    @Test void validate_limits_full_tree_depth_to_fifty_nodes() {
        assertThatCode(() -> service.validateProcessTree(linearTree(50)))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> service.validateProcessTree(linearTree(51)))
            .isInstanceOf(BizException.class).hasMessageContaining("不能超过 50 层");
        assertThatCode(() -> service.validateProcessTree(parallelTree(46)))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> service.validateProcessTree(parallelTree(47)))
            .isInstanceOf(BizException.class).hasMessageContaining("不能超过 50 层");
    }

    @Test void validate_accepts_direct_manager_level() {
        String tree = """
            {"id":"root","type":"ROOT","children":{"id":"a1","type":"APPROVAL",
              "props":{"assignedType":"DIRECT_MANAGER","manager":{"level":3}}}}
            """;

        assertThatCode(() -> service.validateProcessTree(tree)).doesNotThrowAnyException();
    }

    @Test void validate_rejects_direct_manager_level_outside_range() {
        String tree = """
            {"id":"root","type":"ROOT","children":{"id":"a1","type":"APPROVAL",
              "props":{"assignedType":"DIRECT_MANAGER","manager":{"level":11}}}}
            """;

        assertThatThrownBy(() -> service.validateProcessTree(tree))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("直属上级层级必须为 1 到 10");
    }

    @Test void validate_accepts_legacy_empty_conditional_parallel_branch() {
        String tree = """
            {"id":"root","type":"ROOT","children":{"id":"p1","type":"PARALLEL",
              "branchs":[
                {"id":"b1","type":"BRANCH","props":{"conditionMode":"WHEN_MATCHED",
                  "groups":[{"groupType":"AND","conditions":[]}]},
                  "children":{"id":"a1","type":"APPROVAL","props":{"assignedType":"SELF"}}},
                {"id":"b2","type":"BRANCH","props":{"conditionMode":"ALWAYS"},
                  "children":{"id":"a2","type":"APPROVAL","props":{"assignedType":"SELF"}}}
              ],"children":{"id":"join","type":"EMPTY"}}}
            """;
        assertThatCode(() -> service.validateProcessTree(tree)).doesNotThrowAnyException();
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

    @Test void validatesAndReadsApprovalCommentPresets() {
        String tree = """
            {"id":"root","type":"ROOT","children":{"id":"a1","type":"APPROVAL",
              "props":{"assignedType":"SELF","commentPresets":{
                "approve":[" 同意 ","资料齐全"],"reject":["请补充附件"]}}}}
            """;

        assertThatCode(() -> service.validateProcessTree(tree))
            .doesNotThrowAnyException();
        ApprovalCommentPresets presets = service.commentPresets(tree, "a1");
        assertThat(presets.approve()).containsExactly("同意", "资料齐全");
        assertThat(presets.reject()).containsExactly("请补充附件");

        String duplicate = tree.replace("[\" 同意 \",\"资料齐全\"]",
            "[\"同意\",\" 同意 \"]");
        assertThatThrownBy(() -> service.validateProcessTree(duplicate))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("不能重复");
    }

    @Test void rejectsMoreThanTenApprovalCommentPresets() {
        String tree = """
            {"id":"root","type":"ROOT","children":{"id":"a1","type":"APPROVAL",
              "props":{"assignedType":"SELF","commentPresets":{"approve":[
                "1","2","3","4","5","6","7","8","9","10","11"]}}}}
            """;

        assertThatThrownBy(() -> service.validateProcessTree(tree))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("最多 10 条");
    }

    @Test void publishRejectsRequiredReadonlyStarterFieldWithoutDefault() {
        ProcessDefinitionMapper mapper = Mockito.mock(ProcessDefinitionMapper.class);
        FormDefinitionService formService = Mockito.mock(FormDefinitionService.class);
        ProcessDefinitionService publishService =
            new ProcessDefinitionService(mapper, formService, new ObjectMapper());
        FormDefinition form = new FormDefinition();
        form.setId(7L);
        form.setStatus("PUBLISHED");
        form.setSchema("""
            [{"id":"subject","type":"text","props":{"required":true}}]
            """);
        ProcessDefinition draft = new ProcessDefinition();
        draft.setId(12L);
        draft.setFormDefId(7L);
        draft.setVersion(1);
        draft.setStatus("DRAFT");
        draft.setProcess("""
            {"id":"root","type":"ROOT","props":{"formPerms":[
              {"fieldId":"subject","mode":"READONLY"}]},
             "children":{"id":"a1","type":"APPROVAL",
              "props":{"assignedType":"SELF"}}}
            """);
        when(mapper.selectById(12L)).thenReturn(draft);
        when(formService.getById(7L)).thenReturn(form);
        when(formService.leafFieldTypes(form.getSchema()))
            .thenReturn(Map.of("subject", "text"));

        assertThatThrownBy(() -> publishService.publish(12L))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("只读必填字段 subject")
            .hasMessageContaining("默认值");
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

    private static String optionCondition(String operator, String value) {
        return """
            {"id":"root","type":"ROOT","children":{"id":"c1","type":"CONDITIONS",
              "branchs":[{"id":"b1","type":"CONDITION","props":{"groups":[{
                "groupType":"AND","conditions":[{"field":"kind","operator":"%s",
                "value":%s}]}]}},{"id":"default","type":"CONDITION",
                "props":{"isDefault":true}}]}}
            """.formatted(operator, value);
    }

    private static String optionSchema(boolean duplicateLabel) {
        return """
            [{"id":"kind","type":"select","props":{"options":[
              {"label":"%s","value":1},{"label":"选项二","value":2},
              {"label":"其他","value":"__antflow_other__","isOther":true}]}}]
            """.formatted(duplicateLabel ? "选项二" : "选项一");
    }

    private static String linearTree(int depth) {
        String child = "{\"id\":\"n" + (depth - 1)
            + "\",\"type\":\"APPROVAL\",\"props\":{\"assignedType\":\"SELF\"}}";
        for (int index = depth - 2; index >= 1; index--) {
            child = "{\"id\":\"n" + index + "\",\"type\":\"EMPTY\",\"children\":"
                + child + "}";
        }
        return "{\"id\":\"root\",\"type\":\"ROOT\",\"children\":" + child + "}";
    }

    private static String parallelTree(int wrappers) {
        String child = """
            {"id":"parallel","type":"PARALLEL","props":{"joinMode":"ALL"},
             "branchs":[
               {"id":"branch-a","type":"BRANCH","props":{"conditionMode":"ALWAYS"},
                "children":{"id":"approval-a","type":"APPROVAL",
                  "props":{"assignedType":"SELF"}}},
               {"id":"branch-b","type":"BRANCH","props":{"conditionMode":"ALWAYS"},
                "children":{"id":"approval-b","type":"APPROVAL",
                  "props":{"assignedType":"SELF"}}}],
             "children":{"id":"join","type":"EMPTY"}}
            """;
        for (int index = wrappers; index >= 1; index--) {
            child = "{\"id\":\"w" + index + "\",\"type\":\"EMPTY\",\"children\":"
                + child + "}";
        }
        return "{\"id\":\"root\",\"type\":\"ROOT\",\"children\":" + child + "}";
    }
}
