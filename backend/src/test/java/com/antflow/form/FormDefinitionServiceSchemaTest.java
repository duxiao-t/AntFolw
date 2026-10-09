package com.antflow.form;

import com.antflow.authz.FormGrantService;
import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Schema validation unit test — runs without Postgres.
 */
class FormDefinitionServiceSchemaTest {

    private FormDefinitionService service;
    private FormDefinitionMapper mapper;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void setup() {
        mapper = Mockito.mock(FormDefinitionMapper.class);
        service = new FormDefinitionService(mapper, json, Mockito.mock(FormGrantService.class));
    }

    @Test void saveDraftKeepsPublishedFormWhenContentIsUnchanged() throws Exception {
        // 已发布表单只是改名称、或原样保存，不该被降级为草稿（那会让它掉出手机端目录）。
        when(mapper.selectById(1L)).thenReturn(published());

        var saved = service.saveDraft(1L, "leave", "新名称", "说明",
            json.readTree(SCHEMA), json.readTree("{}"), 7L);

        assertThat(saved.getStatus()).isEqualTo("PUBLISHED");
    }

    @Test void saveDraftDemotesToDraftWhenSchemaChanges() throws Exception {
        when(mapper.selectById(1L)).thenReturn(published());

        var saved = service.saveDraft(1L, "leave", "新名称", "说明",
            json.readTree("[{\"id\":\"b\",\"type\":\"text\"}]"), json.readTree("{}"), 7L);

        assertThat(saved.getStatus()).isEqualTo("DRAFT");
        assertThat(saved.getSchema()).isEqualTo("[{\"id\":\"b\",\"type\":\"text\"}]");
    }

    @Test void saveDraftKeepsSettingsWhenPayloadOmitsThem() throws Exception {
        // 客户端漏传 settings 时不能把已有的清成 {}（审批流开关、业务单号配置就藏在这里面）。
        var existing = published();
        existing.setSettings("{\"workflowEnabled\": true}");
        when(mapper.selectById(1L)).thenReturn(existing);

        var saved = service.saveDraft(1L, "leave", "新名称", "说明",
            json.readTree(SCHEMA), null, 7L);

        assertThat(saved.getSettings()).isEqualTo("{\"workflowEnabled\": true}");
        // 内容没变，也不该被降级为草稿。
        assertThat(saved.getStatus()).isEqualTo("PUBLISHED");
    }

    @Test void saveDraftKeepsNameWhenPayloadOmitsIt() throws Exception {
        var existing = published();
        existing.setName("真实名字");
        when(mapper.selectById(1L)).thenReturn(existing);

        var saved = service.saveDraft(1L, "leave", null, null, json.readTree(SCHEMA), null, 7L);

        assertThat(saved.getName()).isEqualTo("真实名字");
        assertThat(saved.getStatus()).isEqualTo("PUBLISHED");
    }

    @Test void saveDraftRejectsMutualDisplayConditionCycle() throws Exception {
        when(mapper.selectById(1L)).thenReturn(draft());

        assertThatThrownBy(() -> service.saveDraft(1L, null, null, null,
            json.readTree(CYCLE_MUTUAL), null, 7L))
            .isInstanceOfSatisfying(BizException.class, error -> {
                assertThat(error.getCode()).isEqualTo("BAD_SCHEMA");
                assertThat(error.getMessage()).contains("a → b → a");
            });
    }

    @Test void saveDraftRejectsSelfReferencingDisplayCondition() throws Exception {
        when(mapper.selectById(1L)).thenReturn(draft());

        assertThatThrownBy(() -> service.saveDraft(1L, null, null, null,
            json.readTree(CYCLE_SELF), null, 7L))
            .isInstanceOfSatisfying(BizException.class, error -> {
                assertThat(error.getCode()).isEqualTo("BAD_SCHEMA");
                assertThat(error.getMessage()).contains("a → a");
            });
    }

    @Test void saveDraftRejectsCycleHiddenInsideChildren() throws Exception {
        // 容器/明细项里的子字段同样进图：collectConditionSources 必须递归 children。
        String nested = "[{\"id\":\"container\",\"type\":\"span_layout\",\"children\":["
            + "{\"id\":\"x\",\"type\":\"text\",\"props\":{\"displayCondition\":"
            + "{\"fieldId\":\"y\",\"operator\":\"eq\",\"value\":\"1\"}}},"
            + "{\"id\":\"y\",\"type\":\"text\",\"props\":{\"displayCondition\":"
            + "{\"fieldId\":\"x\",\"operator\":\"eq\",\"value\":\"1\"}}}]}]";
        when(mapper.selectById(1L)).thenReturn(draft());

        assertThatThrownBy(() -> service.saveDraft(1L, null, null, null,
            json.readTree(nested), null, 7L))
            .isInstanceOfSatisfying(BizException.class, error ->
                assertThat(error.getCode()).isEqualTo("BAD_SCHEMA"));
    }

    @Test void saveDraftStillAcceptsForwardReferenceAndDanglingSource() throws Exception {
        // 拦环不能顺手把合法引用一起拦掉：前向引用（引用后面才声明的字段）与悬空来源都不是环。
        when(mapper.selectById(1L)).thenReturn(draft());

        String forward = "[{\"id\":\"dependent\",\"type\":\"text\",\"props\":{\"displayCondition\":"
            + "{\"fieldId\":\"source\",\"operator\":\"eq\",\"value\":\"yes\"}}},"
            + "{\"id\":\"source\",\"type\":\"select\"},"
            + "{\"id\":\"dangling\",\"type\":\"text\",\"props\":{\"displayCondition\":"
            + "{\"fieldId\":\"gone\",\"operator\":\"eq\",\"value\":\"yes\"}}}]";

        assertThatCode(() -> service.saveDraft(1L, null, null, null,
            json.readTree(forward), null, 7L)).doesNotThrowAnyException();
    }

    @Test void publishRejectsDisplayConditionCycle() throws Exception {
        var fd = draft();
        fd.setSchema(CYCLE_MUTUAL);
        when(mapper.selectById(1L)).thenReturn(fd);

        assertThatThrownBy(() -> service.publish(1L))
            .isInstanceOfSatisfying(BizException.class, error -> {
                assertThat(error.getCode()).isEqualTo("BAD_SCHEMA");
                assertThat(error.getMessage()).contains("互指");
            });
    }

    @Test void submittingALegacyCyclicSchemaStillWorks() throws Exception {
        // 故意**不**在 parseSchema 里拦环：存量已发布的表单可能已经带环（"界面禁止成环"是后来的事），
        // 在提交路径上拒绝会让那些表单当场填不了。所以环只在**保存与发布**拒绝，运行时给确定性结果
        // （环上节点按来源不满足处理）。前端相应地把 `visiting` 守卫写对，不再递归爆栈。
        assertThatCode(() -> service.validateSubmission(CYCLE_MUTUAL, Map.of("a", "yes", "b", "yes")))
            .doesNotThrowAnyException();
    }

    private static final String SCHEMA = "[{\"id\":\"a\",\"type\":\"text\"}]";
    private static final String CYCLE_MUTUAL = "[{\"id\":\"a\",\"type\":\"text\",\"props\":"
        + "{\"displayCondition\":{\"fieldId\":\"b\",\"operator\":\"eq\",\"value\":\"yes\"}}},"
        + "{\"id\":\"b\",\"type\":\"text\",\"props\":{\"displayCondition\":"
        + "{\"fieldId\":\"a\",\"operator\":\"eq\",\"value\":\"yes\"}}}]";
    private static final String CYCLE_SELF = "[{\"id\":\"a\",\"type\":\"text\",\"props\":"
        + "{\"displayCondition\":{\"fieldId\":\"a\",\"operator\":\"eq\",\"value\":\"yes\"}}}]";

    /** 可编辑草稿：保存/发布路径的入口状态。 */
    private FormDefinition draft() {
        var fd = published();
        fd.setStatus("DRAFT");
        return fd;
    }

    /**
     * 已发布表单。schema 故意写成 **Postgres jsonb 读回来的排版**（冒号/逗号后带空格），
     * 因为真实库里就是这种形式——拿它跟 writeJson 的紧凑输出直接比字符串会永远判定为有变化。
     */
    private FormDefinition published() {
        var fd = new FormDefinition();
        fd.setId(1L);
        fd.setCode("leave");
        fd.setStatus("PUBLISHED");
        fd.setSchema("[{\"id\": \"a\", \"type\": \"text\"}]");
        fd.setSettings("{}");
        return fd;
    }

    @Test void publishAcceptsNonEmptySchema() {
        var fd = new FormDefinition();
        fd.setId(1L);
        fd.setStatus("DRAFT");
        fd.setVersion(1);
        fd.setSchema("[{\"id\":\"a\",\"type\":\"text\"}]");
        when(mapper.selectById(1L)).thenReturn(fd);

        var pub = service.publish(1L);
        assertThat(pub.getStatus()).isEqualTo("PUBLISHED");
        assertThat(pub.getVersion()).isEqualTo(2);
    }

    @Test void updateCannotBypassTheDedicatedDisableEndpoint() {
        var fd = published();
        when(mapper.selectById(1L)).thenReturn(fd);

        assertThatThrownBy(() -> service.update(1L, null, null, "DEPRECATED", null, null))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("publish/disable endpoints");
    }

    @Test void publishRejectsSectionType() {
        var fd = new FormDefinition();
        fd.setId(1L);
        fd.setStatus("DRAFT");
        fd.setVersion(1);
        fd.setSchema("""
            [{"id":"basic","type":"section","label":"基础信息","children":[
              {"id":"a","type":"text","label":"姓名"}]}]
            """);
        when(mapper.selectById(1L)).thenReturn(fd);

        assertThatThrownBy(() -> service.publish(1L))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("unsupported field type");
    }

    @Test void publishRejectsEmptySchema() {
        var fd = new FormDefinition();
        fd.setId(1L);
        fd.setStatus("DRAFT");
        fd.setSchema("[]");
        when(mapper.selectById(1L)).thenReturn(fd);
        assertThatThrownBy(() -> service.publish(1L)).isInstanceOf(BizException.class);
    }

    @Test void publishRejectsNonArraySchema() {
        var fd = new FormDefinition();
        fd.setId(1L);
        fd.setStatus("DRAFT");
        fd.setSchema("{\"id\":\"a\"}");
        when(mapper.selectById(1L)).thenReturn(fd);
        assertThatThrownBy(() -> service.publish(1L)).isInstanceOf(BizException.class);
    }

    @Test void departmentPickerAcceptsMultiValuesAndLegacyScalarButRejectsInvalidValues() {
        String multiple = """
            [{"id":"departments","type":"dept_picker","label":"协作部门",
              "props":{"multiple":true,"required":true,"maxCount":2}}]
            """;
        String single = """
            [{"id":"department","type":"dept_picker","label":"所属部门","props":{}}]
            """;

        assertThatCode(() -> service.validateSubmission(multiple,
            Map.of("departments", List.of(1, 2)))).doesNotThrowAnyException();
        assertThatCode(() -> service.validateSubmission(multiple,
            Map.of("departments", 1))).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.validateSubmission(multiple,
            Map.of("departments", List.of(1, 2, 3))))
            .isInstanceOf(BizException.class).hasMessageContaining("maxCount");
        assertThatThrownBy(() -> service.validateSubmission(multiple,
            Map.of("departments", List.of(1, "2"))))
            .isInstanceOf(BizException.class).hasMessageContaining("department ids");
        assertThatThrownBy(() -> service.validateSubmission(single,
            Map.of("department", List.of(1))))
            .isInstanceOf(BizException.class).hasMessageContaining("department ids");
    }

    @Test void saveDraftTranslatesObjectToJsonString() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any(FormDefinition.class))).thenAnswer(inv -> {
            FormDefinition fd = inv.getArgument(0);
            fd.setId(42L);
            return 1;
        });
        var fd = service.saveDraft(null, "leave_req", "请假",
            "请假申请",
            List.of(Map.of("id", "a", "type", "text", "label", "x", "props", Map.of())),
            null, 1L);
        assertThat(fd.getId()).isEqualTo(42L);
        assertThat(fd.getSchema()).startsWith("[");
    }

    @Test void saveDraftTurnsPublishedFormBackToDraft() {
        var fd = new FormDefinition();
        fd.setId(1L);
        fd.setCode("leave_req");
        fd.setName("请假");
        fd.setDescription("old");
        fd.setStatus("PUBLISHED");
        fd.setVersion(2);
        fd.setSchema("[{\"id\":\"old\",\"type\":\"text\"}]");
        fd.setSettings("{}");
        when(mapper.selectById(1L)).thenReturn(fd);

        var saved = service.saveDraft(
            1L,
            "leave_req",
            "新版请假",
            "new",
            List.of(Map.of("id", "a", "type", "text")),
            Map.of("workflowEnabled", false),
            1L
        );

        assertThat(saved.getStatus()).isEqualTo("DRAFT");
        assertThat(saved.getName()).isEqualTo("新版请假");
        assertThat(saved.getSchema()).contains("\"id\":\"a\"");
    }

    @Test void publishRejectsUnsupportedFieldType() {
        var fd = new FormDefinition();
        fd.setId(1L);
        fd.setStatus("DRAFT");
        fd.setSchema("[{\"id\":\"a\",\"type\":\"unknown\"}]");
        when(mapper.selectById(1L)).thenReturn(fd);
        assertThatThrownBy(() -> service.publish(1L))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("unsupported field type");
    }

    @Test void validateSubmissionReadsRulesRequired() {
        assertThatThrownBy(() -> service.validateSubmission(
            "[{\"id\":\"a\",\"type\":\"text\",\"label\":\"姓名\",\"rules\":{\"required\":true}}]",
            Map.of("a", "")
        )).isInstanceOf(BizException.class)
            .hasMessageContaining("required");
    }

    @Test void validateSubmissionRecursesLayoutChildrenWithoutRequiringContainerValue() {
        assertThatThrownBy(() -> service.validateSubmission(
            """
                [{"id":"row","type":"span_layout","label":"布局","props":{"required":true},
                  "children":[{"id":"a","type":"text","label":"姓名","props":{"required":true}}]}]
                """,
            Map.of("a", "")
        )).isInstanceOf(BizException.class)
            .hasMessageContaining("姓名");

        assertThatCode(() -> service.validateSubmission(
            """
                [{"id":"row","type":"span_layout","label":"布局","props":{"required":true},
                  "children":[{"id":"a","type":"text","label":"姓名","props":{"required":true}}]}]
                """,
            Map.of("a", "张三")
        )).doesNotThrowAnyException();
    }

    @Test void publishAcceptsValidMatrixFillSchema() {
        var fd = new FormDefinition();
        fd.setId(1L);
        fd.setStatus("DRAFT");
        fd.setVersion(1);
        fd.setSchema(matrixSchema("textarea", "\"maxLength\":2000"));
        when(mapper.selectById(1L)).thenReturn(fd);

        assertThat(service.publish(1L).getStatus()).isEqualTo("PUBLISHED");
    }

    @Test void publishRejectsInvalidMatrixFillSchema() {
        var fd = new FormDefinition();
        fd.setId(1L);
        fd.setStatus("DRAFT");
        fd.setSchema("""
            [{"id":"matrix","type":"matrix_fill","props":{
              "rows":[{"id":"row_1","label":"行1"},{"id":"row_1","label":"行2"}],
              "columns":[{"id":"col_1","label":"列1"}],
              "cellType":"textarea","maxRows":20,"maxColumns":10,"maxLength":2000
            }}]
            """);
        when(mapper.selectById(1L)).thenReturn(fd);

        assertThatThrownBy(() -> service.publish(1L))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("unique");
    }

    @Test void validateMatrixSubmissionIdentifiesRequiredCell() {
        assertThatThrownBy(() -> service.validateSubmission(
            matrixSchema("textarea", "\"maxLength\":20,\"required\":true"),
            Map.of("matrix", Map.of("customRows", List.of(), "customColumns", List.of(), "cells", Map.of()))
        )).isInstanceOf(BizException.class)
            .hasMessageContaining("行1 / 列1");
    }

    @Test void validateMatrixSubmissionChecksRuntimeAxesAndNumberRules() {
        var schema = matrixSchema("number", "\"precision\":1,\"min\":0,\"max\":20");
        var value = Map.of(
            "customRows", List.of(Map.of("id", "runtime_row_a", "label", "运行行")),
            "customColumns", List.of(Map.of("id", "runtime_column_a", "label", "运行列")),
            "cells", Map.of(
                "row_1", Map.of("col_1", 5),
                "runtime_row_a", Map.of("runtime_column_a", 10.25)
            )
        );

        assertThatThrownBy(() -> service.validateSubmission(schema, Map.of("matrix", value)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("运行行 / 运行列")
            .hasMessageContaining("precision");
    }

    @Test void validateMatrixSubmissionKeepsOrphanedCellsCompatible() {
        var value = Map.of(
            "customRows", List.of(),
            "customColumns", List.of(),
            "cells", Map.of(
                "row_1", Map.of("col_1", "有效值"),
                "deleted_row", Map.of("deleted_col", Map.of("legacy", true))
            )
        );

        assertThatCode(() -> service.validateSubmission(
            matrixSchema("textarea", "\"maxLength\":20"),
            Map.of("matrix", value)
        )).doesNotThrowAnyException();
    }

    @Test void leafFieldTypesExcludesContainersAndDescription() {
        String schema = """
            [{"id":"layout","type":"span_layout","children":[
               {"id":"name","type":"text"},
               {"id":"file","type":"file_upload"}
             ]},
             {"id":"table","type":"table_list","children":[{"id":"qty","type":"number"}]},
             {"id":"note","type":"description"}]
            """;

        assertThat(service.leafFieldIds(schema))
            .containsExactlyInAnyOrder("name", "file", "qty");
        assertThat(service.leafFieldTypes(schema))
            .containsEntry("file", "file_upload")
            .containsEntry("qty", "number");
    }

    @Test void subsetValidationOnlyValidatesSelectedFields() {
        String schema = """
            [{"id":"name","type":"text","props":{"required":true}},
             {"id":"note","type":"text","props":{"required":true}}]
            """;

        assertThatCode(() -> service.validateSubmission(
            schema, Map.of("name", "张三"), Set.of("name")))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> service.validateSubmission(
            schema, Map.of("name", ""), Set.of("name")))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("required");
    }

    @Test void publishRejectsEmptyOptions() {
        var fd = new FormDefinition();
        fd.setId(1L);
        fd.setStatus("DRAFT");
        fd.setSchema("[{\"id\":\"kind\",\"type\":\"select\",\"props\":{\"options\":[{\"label\":\"\",\"value\":\"a\"}]}}]");
        when(mapper.selectById(1L)).thenReturn(fd);
        assertThatThrownBy(() -> service.publish(1L))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("incomplete option");
    }

    @Test void publishAcceptsMissingNullAndKnownSelectDisplayStyles() {
        for (String displayStyle : List.of(
            "", ",\"displayStyle\":null", ",\"displayStyle\":\"list\"",
            ",\"displayStyle\":\"dropdown\"", ",\"displayStyle\":\"block_single\"",
            ",\"displayStyle\":\"block_double\""
        )) {
            var fd = new FormDefinition();
            fd.setId(1L);
            fd.setStatus("DRAFT");
            fd.setVersion(1);
            fd.setSchema("[{\"id\":\"kind\",\"type\":\"select\",\"props\":{\"options\":[{\"label\":\"甲\",\"value\":\"a\"}]" + displayStyle + "}}]");
            when(mapper.selectById(1L)).thenReturn(fd);
            assertThatCode(() -> service.publish(1L)).doesNotThrowAnyException();
        }
    }

    @Test void publishRejectsUnknownSelectDisplayStyle() {
        var fd = new FormDefinition();
        fd.setId(1L);
        fd.setStatus("DRAFT");
        fd.setSchema("[{\"id\":\"kind\",\"type\":\"multi_select\",\"props\":{\"displayStyle\":\"cards\",\"options\":[{\"label\":\"甲\",\"value\":\"a\"}]}}]");
        when(mapper.selectById(1L)).thenReturn(fd);

        assertThatThrownBy(() -> service.publish(1L))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("displayStyle");
    }

    @Test void publishValidatesOptionalChecklistResultColors() {
        for (String color : List.of("", ",\"color\":null", ",\"color\":\"#22A052\"")) {
            var fd = new FormDefinition();
            fd.setId(1L);
            fd.setStatus("DRAFT");
            fd.setVersion(1);
            fd.setSchema("[{\"id\":\"check\",\"type\":\"checklist\",\"props\":{\"results\":[{\"id\":\"ok\",\"label\":\"合格\"" + color + "}]}}]");
            when(mapper.selectById(1L)).thenReturn(fd);
            assertThatCode(() -> service.publish(1L)).doesNotThrowAnyException();
        }

        var invalid = new FormDefinition();
        invalid.setId(2L);
        invalid.setStatus("DRAFT");
        invalid.setSchema("[{\"id\":\"check\",\"type\":\"checklist\",\"props\":{\"results\":[{\"id\":\"ok\",\"label\":\"合格\",\"color\":\"red\"}]}}]");
        when(mapper.selectById(2L)).thenReturn(invalid);
        assertThatThrownBy(() -> service.publish(2L))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("color");
    }

    @Test void clearedNumberBoundsAreUnlimited() {
        String schema = "[{\"id\":\"count\",\"type\":\"number\",\"props\":{}}]";
        assertThatCode(() -> service.validateSubmission(schema, Map.of("count", -1000000)))
            .doesNotThrowAnyException();
        assertThatCode(() -> service.validateSubmission(schema, Map.of("count", 10000000)))
            .doesNotThrowAnyException();
    }

    @Test void validatesNativeAudioAndLocationValuesWithHistoryCompatibility() {
        String schema = """
            [{"id":"voice","type":"audio_upload","props":{"maxCount":2,"maxDuration":30}},
             {"id":"place","type":"location"}]
            """;
        assertThatCode(() -> service.validateSubmission(schema, Map.of(
            "voice", List.of(Map.of("id", "file-1")),
            "place", Map.of("latitude", 31.2, "longitude", 121.5))))
            .doesNotThrowAnyException();
        assertThatCode(() -> service.validateSubmission(schema, Map.of(
            "voice", List.of(Map.of("id", "file-1", "durationSeconds", 20)),
            "place", Map.of("latitude", 31.2, "longitude", 121.5,
                "accuracy", 12, "coordinateSystem", "WGS84"))))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> service.validateSubmission(schema, Map.of(
            "voice", List.of(Map.of("id", "a"), Map.of("id", "b"), Map.of("id", "c")))))
            .isInstanceOf(BizException.class).hasMessageContaining("maxCount");
        assertThatThrownBy(() -> service.validateSubmission(schema, Map.of(
            "place", Map.of("latitude", 31.2, "longitude", 121.5,
                "coordinateSystem", "BD09"))))
            .isInstanceOf(BizException.class).hasMessageContaining("coordinates");
    }

    @Test void publishRejectsAudioLimitsOutsideDesignerRange() {
        var fd = new FormDefinition();
        fd.setId(1L);
        fd.setStatus("DRAFT");
        fd.setSchema("[{\"id\":\"voice\",\"type\":\"audio_upload\",\"props\":{\"maxCount\":11,\"maxDuration\":60}}]");
        when(mapper.selectById(1L)).thenReturn(fd);

        assertThatThrownBy(() -> service.publish(1L))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("audio_upload limits");
    }

    @Test void displayConditionsSupportInAndSkipHiddenRequiredFields() {
        String schema = """
            [{"id":"kind","type":"select","props":{"options":[
               {"label":"甲","value":"a"},{"label":"乙","value":"b"}]}},
             {"id":"detail","type":"text","label":"详情","props":{"required":true,
               "displayCondition":{"fieldId":"kind","operator":"in","value":["a","b"]}}}]
            """;

        assertThatCode(() -> service.validateSubmission(schema, Map.of("kind", "c")))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> service.validateSubmission(schema, Map.of("kind", "b")))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("详情");
    }

    @Test void publishRejectsMalformedInConditions() {
        var fd = new FormDefinition();
        fd.setId(1L);
        fd.setStatus("DRAFT");
        fd.setSchema("[{\"id\":\"detail\",\"type\":\"text\",\"props\":{\"displayCondition\":{\"fieldId\":\"kind\",\"operator\":\"in\",\"value\":\"a\"}}}]");
        when(mapper.selectById(1L)).thenReturn(fd);
        assertThatThrownBy(() -> service.publish(1L))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("non-empty array");
    }

    @Test void filterVisibleSubmissionUsesCompleteValuesAndDropsHiddenFields() {
        String schema = """
            [{"id":"kind","type":"select","props":{"options":[{"label":"甲","value":"a"}]}},
             {"id":"detail","type":"text","props":{"displayCondition":
               {"fieldId":"kind","operator":"eq","value":"a"}}}]
            """;
        var values = Map.of("kind", "x", "detail", "保留值");
        assertThat(service.filterVisibleSubmission(schema, values)).containsExactly(Map.entry("kind", "x"));
        assertThat(values).containsEntry("detail", "保留值");
    }

    @Test void hiddenConditionalSourcesAlsoHideNestedTargets() {
        String schema = """
            [{"id":"level1","type":"select","props":{"options":[{"label":"显示","value":"show"}]}},
             {"id":"level2","type":"select","props":{"options":[{"label":"显示","value":"show"}],
               "displayCondition":{"fieldId":"level1","operator":"eq","value":"show"}}},
             {"id":"level3","type":"text","props":{"displayCondition":
               {"fieldId":"level2","operator":"eq","value":"show"}}}]
            """;
        assertThat(service.filterVisibleSubmission(schema, Map.of(
            "level1", "hide", "level2", "show", "level3", "保留值")))
            .containsExactly(Map.entry("level1", "hide"));
    }

    @Test void starterPermissionsCanonicalizeDraftAndSubmission() {
        String schema = """
            [{"id":"subject","type":"text","label":"主题","props":{"required":true}},
             {"id":"department","type":"text","label":"部门","props":{
               "required":true,"defaultValue":"研发部"}},
             {"id":"secret","type":"text","label":"内部字段","props":{"required":true}}]
            """;
        String process = """
            {"id":"root","type":"ROOT","props":{"formPerms":[
              {"fieldId":"department","mode":"READONLY"},
              {"fieldId":"secret","mode":"HIDDEN"}]}}
            """;

        assertThat(service.canonicalizeStarterData(schema, Map.of(), process))
            .containsExactly(Map.entry("department", "研发部"));
        assertThatThrownBy(() -> service.canonicalizeStarterSubmission(
            schema, Map.of(), process))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("主题");

        Map<String, Object> submitted = service.canonicalizeStarterSubmission(schema,
            Map.of("subject", "采购申请", "department", "篡改部门", "secret", "篡改内容"),
            process);
        assertThat(submitted).containsExactly(
            Map.entry("subject", "采购申请"), Map.entry("department", "研发部"));
    }

    @Test void starterPermissionsApplyInsideTableRowsAndPreserveRestrictedReworkValues()
            throws Exception {
        String schema = """
            [{"id":"lines","type":"table_list","label":"采购明细","props":{"minRows":1},
              "children":[
                {"id":"item","type":"text","label":"物品","props":{"required":true}},
                {"id":"approvedPrice","type":"number","label":"核定价"},
                {"id":"internalNote","type":"text","label":"内部备注"}]}]
            """;
        String process = """
            {"id":"root","type":"ROOT","props":{"formPerms":[
              {"fieldId":"approvedPrice","mode":"READONLY"},
              {"fieldId":"internalNote","mode":"HIDDEN"}]}}
            """;
        Map<String, Object> incoming = Map.of("lines", List.of(Map.of(
            "item", "显示器", "approvedPrice", 1, "internalNote", "篡改")));
        Map<String, Object> existing = Map.of("lines", List.of(Map.of(
            "item", "旧物品", "approvedPrice", 2600, "internalNote", "审批人备注")));

        Map<String, Object> revised = service.canonicalizeStarterRevision(
            schema, incoming, existing, process);
        JsonNode revisedJson = json.valueToTree(revised);
        assertThat(revisedJson.at("/lines/0/item").asText()).isEqualTo("显示器");
        assertThat(revisedJson.at("/lines/0/approvedPrice").asInt()).isEqualTo(2600);
        assertThat(revisedJson.at("/lines/0/internalNote").asText()).isEqualTo("审批人备注");
        assertThatCode(() -> service.validateStarterSubmission(schema, revised, process))
            .doesNotThrowAnyException();

        JsonNode projectedSchema = service.projectStarterSchema(schema, process);
        JsonNode projectedData = service.projectStarterData(revised, schema, process);
        assertThat(projectedSchema.at("/0/children")).hasSize(2);
        assertThat(projectedData.at("/lines/0/internalNote").isMissingNode()).isTrue();
        assertThat(projectedData.at("/lines/0/approvedPrice").asInt()).isEqualTo(2600);
    }

    private String matrixSchema(String cellType, String extraProps) {
        return """
            [{"id":"matrix","type":"matrix_fill","label":"矩阵","props":{
              "rows":[{"id":"row_1","label":"行1"}],
              "columns":[{"id":"col_1","label":"列1"}],
              "cellType":"%s","maxRows":20,"maxColumns":10,%s
            }}]
            """.formatted(cellType, extraProps);
    }
}
