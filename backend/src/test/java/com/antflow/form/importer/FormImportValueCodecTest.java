package com.antflow.form.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.antflow.engine.BizException;
import com.antflow.form.FieldTypeCatalog;
import com.antflow.form.importer.FormImportValueCodec.DeptRef;
import com.antflow.form.importer.FormImportValueCodec.UserRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 26 种字段类型逐项覆盖：合法值、边界、非法值。
 *
 * <p>最后那条参数化断言是**防新增类型漏处理**的门禁：{@code FieldTypeCatalog} 加一种类型，
 * 这里没有对应规则就会红。它断言的是"清单相等"，不是"contains"——多登记一个不存在的类型
 * 同样应该被 review 看到。
 */
class FormImportValueCodecTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 重名数据是刻意的：人员"张三"两个、部门"研发部"两个，用来验证"不猜，报候选"。 */
    private static final FormImportValueCodec.Directory DIRECTORY =
        new FormImportValueCodec.Directory() {
            @Override
            public List<UserRef> users(Collection<String> tokens) {
                return List.of(new UserRef(11, "E001", "张三"),
                    new UserRef(12, "E002", "李四"),
                    new UserRef(13, "E003", "张三"));
            }

            @Override
            public List<DeptRef> departments() {
                return List.of(new DeptRef(1, "总部", null),
                    new DeptRef(2, "研发部", 1L),
                    new DeptRef(3, "销售部", 1L),
                    new DeptRef(4, "华东销售", 3L),
                    new DeptRef(5, "研发部", 3L));
            }

            @Override
            public List<Map<String, String>> optionRows(long versionId) {
                return List.of(Map.of("code", "A", "name", "甲"),
                    Map.of("code", "B", "name", "乙"));
            }
        };

    private final FormImportValueCodec codec = new FormImportValueCodec(JSON, DIRECTORY);

    // ---------------------------------------------------------------- 清单门禁

    static Stream<String> catalogTypes() {
        return FieldTypeCatalog.ALL.stream().map(FieldTypeCatalog.FieldType::id);
    }

    @ParameterizedTest
    @MethodSource("catalogTypes")
    void everyCatalogTypeHasAnImportRule(String type) {
        assertThat(FormImportValueCodec.HANDLED_TYPES)
            .as("FieldTypeCatalog 里的每个 type 都必须有导入规则（新增类型请同步 codec）")
            .contains(type);
    }

    @Test
    void handledTypesMatchCatalogExactly() {
        assertThat(FormImportValueCodec.HANDLED_TYPES)
            .containsExactlyInAnyOrderElementsOf(FieldTypeCatalog.ids());
    }

    // ---------------------------------------------------------------- 基础类型

    @Test
    void textTypesKeepRawTextAndTextareaKeepsNewlines() {
        assertThat(decode(field("text"), "  合同 A  ")).isEqualTo("  合同 A  ");
        assertThat(decode(field("textarea"), "第一行\n第二行")).isEqualTo("第一行\n第二行");
        assertThat(decode(field("textarea"), "关键词")).isEqualTo("关键词");
        assertThat(decode(field("scan_code"), "6901234567890")).isEqualTo("6901234567890");
    }

    @Test
    void blankCellYieldsNull() {
        assertThat(decode(field("text"), "   ")).isNull();
    }

    @Test
    void numberAndMoneyParseAsBigDecimalAndRejectThousandSeparators() {
        assertThat(decode(field("number"), "12.50")).isEqualTo(new BigDecimal("12.50"));
        assertThat(decode(field("money"), "-3")).isEqualTo(new BigDecimal("-3"));
        assertThatThrownBy(() -> decode(field("number"), "1,200"))
            .isInstanceOf(BizException.class).hasMessageContaining("千分位");
        assertThatThrownBy(() -> decode(field("money"), "abc"))
            .isInstanceOf(BizException.class).hasMessageContaining("不是合法数字");
    }

    @Test
    void dateAcceptsConfiguredFormatAndCommonHistoricalFormats() {
        assertThat(decode(field("date"), "2024-01-05")).isEqualTo("2024-01-05");
        assertThat(decode(field("date"), "2024/1/5")).isEqualTo("2024-01-05");
        assertThat(decode(field("date"), "20240105")).isEqualTo("2024-01-05");
        assertThat(decode(field("date", "{\"format\":\"yyyyMMdd\"}"), "20240105"))
            .isEqualTo("2024-01-05");
        assertThatThrownBy(() -> decode(field("date"), "2024-13-45"))
            .isInstanceOf(BizException.class).hasMessageContaining("不是合法日期");
    }

    @Test
    void timeKeepsSecondsOnlyWhenGiven() {
        assertThat(decode(field("time"), "9:05")).isEqualTo("09:05");
        assertThat(decode(field("time"), "09:05:06")).isEqualTo("09:05:06");
        assertThatThrownBy(() -> decode(field("time"), "25:00"))
            .isInstanceOf(BizException.class).hasMessageContaining("不是合法时间");
    }

    @Test
    void dateRangeSplitsOnTilde() {
        assertThat(decode(field("date_range"), "2024-01-01~2024-01-31"))
            .isEqualTo(List.of("2024-01-01", "2024-01-31"));
        assertThatThrownBy(() -> decode(field("date_range"), "2024-01-01"))
            .isInstanceOf(BizException.class).hasMessageContaining("起~止");
    }


    // ---------------------------------------------------------------- 选项

    @Test
    void selectResolvesLabelToValueAndAcceptsValueItself() {
        JsonNode field = field("select", "{\"options\":[{\"label\":\"甲\",\"value\":\"a\"},"
            + "{\"label\":\"乙\",\"value\":\"b\"}]}");
        assertThat(decode(field, "甲")).isEqualTo("a");
        assertThat(decode(field, "b")).isEqualTo("b");
        assertThatThrownBy(() -> decode(field, "丙"))
            .isInstanceOf(BizException.class).hasMessageContaining("不存在");
    }

    @Test
    void selectKeepsNumericOptionValueType() {
        JsonNode field = field("select", "{\"options\":[{\"label\":\"一\",\"value\":1},"
            + "{\"label\":\"二\",\"value\":2}]}");
        assertThat(decode(field, "二")).isEqualTo(2);
    }

    @Test
    void duplicateLabelAcrossDifferentValuesIsReportedWithCandidates() {
        JsonNode field = field("select", "{\"options\":[{\"label\":\"是\",\"value\":\"Y\"},"
            + "{\"label\":\"是\",\"value\":\"1\"}]}");
        assertThatThrownBy(() -> decode(field, "是"))
            .isInstanceOf(BizException.class).hasMessageContaining("对应多个值");
    }

    @Test
    void multiSelectSplitsDedupesAndPreservesOrder() {
        JsonNode field = field("multi_select", "{\"options\":[{\"label\":\"甲\",\"value\":\"a\"},"
            + "{\"label\":\"乙\",\"value\":\"b\"},{\"label\":\"丙\",\"value\":\"c\"}]}");
        assertThat(decode(field, "乙、甲，乙; 丙")).isEqualTo(List.of("b", "a", "c"));
        assertThatThrownBy(() -> decode(field, "甲、丁"))
            .isInstanceOf(BizException.class).hasMessageContaining("丁");
    }


    @Test
    void externalOptionSourceLoadsRowsOnceAndResolvesLabel() {
        JsonNode field = field("select", "{\"optionSource\":{\"sourceId\":9,\"versionId\":5,"
            + "\"valueColumn\":\"code\",\"labelColumn\":\"name\"}}");
        FormImportValueCodec.Prefetch prefetch = codec.prefetch(
            JSON.createArrayNode().add(field), List.of(Map.of("f", "乙")));
        assertThat(codec.decode(field, "乙", prefetch)).isEqualTo("B");
    }

    // ---------------------------------------------------------------- 人员/部门

    @Test
    void userPickerResolvesEmployeeNoNameAndExplicitId() {
        assertThat(decode(field("user_picker"), "E002")).isEqualTo(12L);
        assertThat(decode(field("user_picker"), "李四")).isEqualTo(12L);
        assertThat(decode(field("user_picker"), "id:11")).isEqualTo(11L);
    }

    @Test
    void duplicateNamesAreRejectedAndListCandidates() {
        assertThatThrownBy(() -> decode(field("user_picker"), "张三"))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("同名")
            .hasMessageContaining("E001")
            .hasMessageContaining("E003");
    }

    @Test
    void userPickerMultipleReturnsArray() {
        assertThat(decode(field("user_picker", "{\"multiple\":true}"), "李四、id:11"))
            .isEqualTo(List.of(12L, 11L));
        assertThatThrownBy(() -> decode(field("user_picker", "{\"multiple\":false}"), "李四、id:11"))
            .isInstanceOf(BizException.class).hasMessageContaining("只允许选择一个人员");
    }

    @Test
    void deptPickerResolvesNameAndFullPathAndRejectsAmbiguousName() {
        assertThat(decode(field("dept_picker"), "总部/研发部")).isEqualTo(2L);
        assertThat(decode(field("dept_picker"), "华东销售")).isEqualTo(4L);
        assertThat(decode(field("dept_picker"), "总部/销售部/研发部")).isEqualTo(5L);
        assertThatThrownBy(() -> decode(field("dept_picker"), "研发部"))
            .isInstanceOf(BizException.class).hasMessageContaining("重名");
    }

    @Test
    void deptPickerMultipleReturnsArray() {
        assertThat(decode(field("dept_picker", "{\"multiple\":true}"), "总部,华东销售"))
            .isEqualTo(List.of(1L, 4L));
    }

    // ---------------------------------------------------------------- 复合类型

    @Test
    void locationParsesCoordinatesWithFixedCoordinateSystem() {
        assertThat(decode(field("location"), "30.12,120.65")).isEqualTo(Map.of(
            "latitude", 30.12, "longitude", 120.65, "coordinateSystem", "WGS84"));
        assertThatThrownBy(() -> decode(field("location"), "95,120"))
            .isInstanceOf(BizException.class).hasMessageContaining("超出合法范围");
    }

    @Test
    void tableListParsesJsonAndRecursesIntoChildFields() {
        JsonNode field = field("table_list", "{}", "["
            + "{\"id\":\"c1\",\"type\":\"text\",\"label\":\"名称\"},"
            + "{\"id\":\"c2\",\"type\":\"user_picker\",\"label\":\"负责人\"},"
            + "{\"id\":\"c3\",\"type\":\"multi_select\",\"label\":\"标签\",\"props\":{\"options\":["
            + "{\"label\":\"甲\",\"value\":\"a\"},{\"label\":\"乙\",\"value\":\"b\"}]}}"
            + "]");
        Object value = decode(field, "[{\"c1\":\"合同\",\"c2\":\"李四\",\"c3\":[\"甲\",\"乙\"]}]");
        assertThat(value).isEqualTo(List.of(Map.of(
            "c1", "合同", "c2", 12L, "c3", List.of("a", "b"))));
    }

    @Test
    void checklistForcesEmptyImagesAndMapsResultLabels() {
        JsonNode field = field("checklist",
            "{\"results\":[{\"id\":\"pass\",\"label\":\"通过\"},{\"id\":\"fail\",\"label\":\"不通过\"}]}");
        Object value = decode(field,
            "[{\"id\":\"i1\",\"status\":\"通过\",\"description\":\"外观正常\"}]");
        assertThat(value).isEqualTo(List.of(Map.of(
            "id", "i1", "status", "pass", "images", List.of(), "description", "外观正常")));
    }

    @Test
    void matrixFillDecodesCellsByConfiguredCellType() {
        JsonNode field = field("matrix_fill", "{\"cellType\":\"number\"}");
        Object value = decode(field, "{\"cells\":{\"r1\":{\"c1\":\"3.5\",\"c2\":\"\"}}}");
        assertThat(value).isEqualTo(Map.of("cells", Map.of("r1", Map.of("c1", new BigDecimal("3.5")))));
    }

    @Test
    void malformedJsonIsReportedAsValueError() {
        assertThatThrownBy(() -> decode(field("table_list", "{}"), "[{oops"))
            .isInstanceOf(BizException.class).hasMessageContaining("JSON");
        assertThatThrownBy(() -> decode(field("matrix_fill", "{}"), "[]"))
            .isInstanceOf(BizException.class).hasMessageContaining("JSON 对象");
    }

    // ---------------------------------------------------------------- 不支持

    @Test
    void mediaAndLayoutTypesAreExplicitlyUnsupported() {
        for (String type : List.of("file_upload", "image_upload", "video_upload", "audio_upload")) {
            assertThatThrownBy(() -> decode(field(type, "{}"), "x"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("媒体字段");
        }
        assertThatThrownBy(() -> decode(field("description", "{}"), "x"))
            .isInstanceOf(BizException.class).hasMessageContaining("不产生可导入的值");
        assertThatThrownBy(() -> decode(field("span_layout", "{}"), "x"))
            .isInstanceOf(BizException.class).hasMessageContaining("不产生可导入的值");
    }

    // ---------------------------------------------------------------- 工具

    private Object decode(JsonNode field, String text) {
        FormImportValueCodec.Prefetch prefetch = codec.prefetch(
            JSON.createArrayNode().add(field), List.of(Map.of("f", text)));
        return codec.decode(field, text, prefetch);
    }

    private static JsonNode field(String type) {
        return field(type, "{}");
    }

    private static JsonNode field(String type, String props) {
        return field(type, props, "[]");
    }

    private static JsonNode field(String type, String props, String children) {
        try {
            return JSON.readTree("{\"id\":\"f\",\"type\":\"" + type + "\",\"label\":\"字段\","
                + "\"props\":" + props + ",\"children\":" + children + "}");
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException(error);
        }
    }
}
