package com.antflow.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.antflow.form.runtime.FormData;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * CSV 转义这一段是导出里唯一容易写错的地方：一个带逗号的地址、一个带引号的备注、
 * 一段多行的说明，都能让整份表错列。规则很短，所以直接钉住。
 */
class FormDataCsvTest {

    @Test
    void escapesCommasQuotesNewlinesAndEdgeSpaces() {
        assertThat(FormDataCsv.cell("普通")).isEqualTo("普通");
        assertThat(FormDataCsv.cell("北京市, 朝阳区")).isEqualTo("\"北京市, 朝阳区\"");
        assertThat(FormDataCsv.cell("他说\"好\"")).isEqualTo("\"他说\"\"好\"\"\"");
        assertThat(FormDataCsv.cell("第一行\n第二行")).isEqualTo("\"第一行\n第二行\"");
        assertThat(FormDataCsv.cell(" 前导空格")).isEqualTo("\" 前导空格\"");
        assertThat(FormDataCsv.cell(null)).isEmpty();
    }

    @Test
    void writesBomFixedColumnsAndFieldUnionWithoutColumnShift() {
        FormData first = row(1L, "000000000001", "张三", "000003", "技术部",
            OffsetDateTime.of(2026, 9, 29, 2, 30, 0, 0, ZoneOffset.UTC),
            List.of(new FormData.FieldValue("f1", "申请人", "张三", null, null),
                new FormData.FieldValue("f2", "备注", "带,逗号", null, null)));
        // 第二行的字段集合不同（表单改过版本）：按第一行定表头的话，这里的值会错位到别的列。
        FormData second = row(2L, "000000000002", null, null, null, null,
            List.of(new FormData.FieldValue("f3", "金额", 100, null, null)));

        String csv = new String(FormDataCsv.export(List.of(first, second), ZoneOffset.ofHours(8)),
            StandardCharsets.UTF_8);

        assertThat(csv).startsWith("\uFEFF");
        assertThat(csv.lines().findFirst().orElseThrow())
            .isEqualTo("\uFEFF业务单号,提交人,工号,部门,提交时间,状态,申请人,备注,金额");
        // 时区换算：UTC 02:30 → +08 的 10:30（不换算导出里会比用户看到的早 8 小时）
        assertThat(csv).contains("000000000001,张三,000003,技术部,2026-09-29 10:30:00,已提交,张三,\"带,逗号\",");
        // 第二行缺的列留空，金额落在它自己的列上（9 列 → 8 个逗号）
        assertThat(csv).contains("000000000002,,,,,已提交,,,100");
    }

    @Test
    void formatsStructuredValuesReadably() {
        // 检查项这类值是"一串小对象"：别把 Java 的 map.toString() 原样塞进单元格，
        // 也别把 images=[]、description= 这些空键一起带出来。
        FormData data = row(1L, "000000000003", "张三", "000003", "技术部", null, List.of(
            new FormData.FieldValue("c1", "检查项", List.of(
                java.util.Map.of("id", "item-1", "name", "检查项1", "status", "na",
                    "images", List.of(), "description", ""),
                java.util.Map.of("id", "item-2", "name", "检查项2", "status", "pass")), null, null)));

        String csv = new String(FormDataCsv.export(List.of(data), ZoneOffset.UTC),
            StandardCharsets.UTF_8);

        // 键的顺序由来源 map 决定（Map.of 不保证顺序），所以逐项断言而不是比整串。
        assertThat(csv).contains("id=item-1", "name=检查项1", "status=na");
        assertThat(csv).contains("id=item-2", "name=检查项2", "status=pass");
        assertThat(csv).doesNotContain("images=");
        assertThat(csv).doesNotContain("description=");
        // 多项之间仍然是"、"分隔，别把两个检查项粘成一项
        assertThat(csv).contains("、");
    }

    private static FormData row(Long id, String businessNo, String name, String employeeNo,
                                String deptName, OffsetDateTime createdAt,
                                List<FormData.FieldValue> values) {
        FormData data = new FormData();
        data.setId(id);
        data.setBusinessNo(businessNo);
        data.setCreatedByName(name);
        data.setCreatedByEmployeeNo(employeeNo);
        data.setCreatedByDeptName(deptName);
        data.setCreatedAt(createdAt);
        data.setStatus("SUBMITTED");
        data.setFieldValues(new ArrayList<>(values));
        return data;
    }
}
