package com.antflow.form.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 台账显示规则。用例与前端 `pages/admin/FormData/index.test.ts` 的那批一一对应——规则搬到后端时
 * 前端那套已删，留在这里是为了别在搬的过程中悄悄改掉显示口径。
 */
class FormValueDisplayTest {
    private final ObjectMapper json = new ObjectMapper();

    private FormValueDisplay display(String schema, String data) throws Exception {
        JsonNode dataNode = json.readTree(data);
        return new FormValueDisplay(json, json.readTree(schema), List.of(dataNode));
    }

    @Test
    void scalarAndEmptyValuesShowAsThemselves() throws Exception {
        FormValueDisplay display = display("""
            [{"id":"name","type":"text","label":"姓名"}]
            """, "{}");

        assertThat(display.cell("name", json.readTree("\"张三\""))).isEqualTo("张三");
        assertThat(display.cell("name", json.readTree("128.5"))).isEqualTo("128.5");
        assertThat(display.cell("name", json.readTree("null"))).isEmpty();
        assertThat(display.cell("name", json.readTree("\" \""))).isEqualTo(" ");
        // 空值留空由前端渲染成破折号，详情里才给 —（和前端同一个口径）。
        assertThat(display.detail("name", json.readTree("null"))).isEqualTo("—");
        assertThat(display.detail("name", json.readTree("\"\""))).isEqualTo("—");
    }

    @Test
    void compositeValuesAreShapedInsteadOfDumpedAsJson() throws Exception {
        FormValueDisplay display = display("""
            [{"id":"tags","type":"multi_select","label":"标签","props":{"options":[]}}]
            """, "{}");

        assertThat(display.cell("tags", json.readTree("[\"甲\",\"乙\"]"))).isEqualTo("甲、乙");
        assertThat(display.cell("tags", json.readTree("""
            [{"name":"a.png","contentUrl":"/f/a"},{"name":"b.png","contentUrl":"/f/b"}]
            """))).isEqualTo("2 个附件");
        // 检查项也带 name，但它是业务数据不是附件——靠 contentUrl 区分。
        assertThat(display.cell("tags", json.readTree("[{\"id\":\"item-1\",\"name\":\"检查项1\"}]")))
            .isEqualTo("1 项");
        assertThat(display.cell("tags", json.readTree("[]"))).isEmpty();
        assertThat(display.detail("tags", json.readTree("[{\"name\":\"a.png\",\"url\":\"/f/a\"}]")))
            .isEqualTo("a.png");
        // 旧上传件只有 fileName。
        assertThat(display.detail("tags", json.readTree("[{\"fileName\":\"b.png\",\"url\":\"/f/b\"}]")))
            .isEqualTo("b.png");
        assertThat(display.detail("tags", json.readTree("[{\"contentUrl\":\"/f/a\"}]")))
            .isEqualTo("1 个附件");
    }

    @Test
    void staticOptionsShowLabelsAndUnknownValuesFallBackToTheRawValue() throws Exception {
        FormValueDisplay display = display("""
            [{"id":"craft","type":"select","label":"工艺","props":{"options":[
               {"value":"option_1","label":"车削"},{"value":"option_2","label":"铣削"}]}},
             {"id":"level","type":"multi_select","label":"等级","props":{"options":[
               {"value":"a","label":"甲"},{"value":"b","label":"乙"}]}}]
            """, "{}");

        assertThat(display.cell("craft", json.readTree("\"option_1\""))).isEqualTo("车削");
        // 表单改了选项之后旧单据还得看得见当时选的是什么。
        assertThat(display.cell("craft", json.readTree("\"option_9\""))).isEqualTo("option_9");
        assertThat(display.cell("level", json.readTree("[\"a\",\"b\"]"))).isEqualTo("甲、乙");
        assertThat(display.detail("craft", json.readTree("\"\""))).isEqualTo("—");

        // 拿不到类型/字典时只能退化成原始值。
        FormValueDisplay bare = display("""
            [{"id":"craft","type":"select","label":"工艺"}]
            """, "{}");
        assertThat(bare.cell("craft", json.readTree("\"option_1\""))).isEqualTo("option_1");
    }

    @Test
    void anOptionLiterallyCalledUnfilledIsNotTreatedAsAnEmptyValue() throws Exception {
        // 前端老实现拿「未填写」当"空"的哨兵：选项真叫这个名字时会被抹成空白。
        FormValueDisplay display = display("""
            [{"id":"craft","type":"select","label":"工艺","props":{"options":[
               {"value":"none","label":"未填写"}]}}]
            """, "{}");

        assertThat(display.cell("craft", json.readTree("\"none\""))).isEqualTo("未填写");
        assertThat(display.cell("craft", json.readTree("\"\""))).isEmpty();
    }

    @Test
    void checklistSummarisesStatusesInCellsAndExpandsEachItemInDetails() throws Exception {
        FormValueDisplay display = display("""
            [{"id":"checks","type":"checklist","label":"检查项","props":{
               "results":[{"id":"pass","label":"通过"},{"id":"na","label":"不适用"}],
               "items":[{"id":"item-1","label":"检查项1"},{"id":"item-2","label":"检查项2"}]}}]
            """, "{}");
        JsonNode value = json.readTree("""
            [{"id":"item-1","status":"pass","images":[{"name":"a.png","url":"/f/a"}]},
             {"id":"item-2","status":"na","description":"电机正常"}]
            """);

        assertThat(display.cell("checks", value)).isEqualTo("通过 1 · 不适用 1");
        assertThat(display.detail("checks", value))
            .isEqualTo("检查项1：通过（1 张图：a.png）\n检查项2：不适用；说明：电机正常");
        // 旧数据把说明放在 remark 下。
        assertThat(display.detail("checks", json.readTree("""
            [{"id":"item-1","status":"pass","remark":"旧字段名"}]
            """))).isEqualTo("检查项1：通过；说明：旧字段名");
    }

    @Test
    void tableListRowsAreFormattedByTheirChildColumns() throws Exception {
        FormValueDisplay display = display("""
            [{"id":"rows","type":"table_list","label":"明细","children":[
               {"id":"c-name","type":"text","label":"名称"},
               {"id":"c-craft","type":"select","label":"工艺","props":{"options":[
                 {"value":"option_1","label":"车削"}]}}]}]
            """, "{}");

        assertThat(display.detail("rows", json.readTree("""
            [{"c-name":"齿轮","c-craft":"option_1"},{"c-name":"轴"}]
            """))).isEqualTo("名称=齿轮；工艺=车削\n名称=轴");
    }

    @Test
    void externalOptionSourcesAreResolvedFromThePinnedVersion() throws Exception {
        String schema = """
            [{"id":"craft","type":"multi_select","label":"工艺","props":{"optionSource":{
               "sourceId":3,"versionId":9,"valueColumn":"code","labelColumn":"name"}}}]
            """;
        JsonNode data = json.readTree("{\"craft\":[\"option_1\",\"option_2\"]}");
        FormValueDisplay display = new FormValueDisplay(json, json.readTree(schema), List.of(data));

        // 谁绑了外链、该查哪个版本哪两列，schema 里就写着；只有真的被选过的值才去查。
        assertThat(display.pendingRefs()).containsExactly(
            new FormValueDisplay.OptionRef("craft", 9L, "code", "name"));
        assertThat(display.pendingValues("craft")).containsExactly("option_1", "option_2");

        display.bindLabels("craft", Map.of("option_1", "车削"));

        assertThat(display.cell("craft", data.path("craft"))).isEqualTo("车削、option_2");
        // 没人选过的外链下拉不该产生查询。
        FormValueDisplay untouched = new FormValueDisplay(json, json.readTree(schema),
            List.of(json.readTree("{\"craft\":[]}")));
        assertThat(untouched.pendingRefs()).isEmpty();
    }

    @Test
    void valuesCarryTheDisplayTextsAndKeepTheRawValue() throws Exception {
        FormValueDisplay display = display("""
            [{"id":"craft","type":"select","label":"工艺","props":{"options":[
               {"value":"option_1","label":"车削"}]}}]
            """, "{}");

        List<FormData.FieldValue> values = display.values(json.readTree(
            "{\"craft\":\"option_1\",\"applicant\":\"张三\"}"));

        assertThat(values).containsExactly(
            new FormData.FieldValue("craft", "工艺", "option_1", "车削", "车削"),
            new FormData.FieldValue("applicant", "applicant", "张三", "张三", "张三"));
    }
}
