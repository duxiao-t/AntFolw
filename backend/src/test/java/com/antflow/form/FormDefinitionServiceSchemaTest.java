package com.antflow.form;

import com.antflow.authz.FormGrantService;
import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

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
