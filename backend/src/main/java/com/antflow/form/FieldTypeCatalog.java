package com.antflow.form;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 字段类型的**唯一真源**（后端维护）。
 *
 * <p>以前这份清单在四个地方各写一遍：后端 {@code FormDefinitionService.FIELD_TYPES}、桌面
 * {@code registry/formRegistry.ts}、移动 {@code schema/fieldRegistry.ts}，再加台账 SQL 视图里的分支。
 * 它已经漂了：后端与移动 26 种、桌面 21 种，缺的 {@code radio/checkbox/search/time/switch}
 * 在桌面开出来是个洞。现在这里生成 {@code fixtures/field-semantics/field-types.json}，
 * 两端注册表与它对账——**缺哪种类型必须是显式记录的**，不能再靠"没人发现"。
 *
 * <p>这里只放**行为**属性（角色/是否媒体/是否带选项/能否作为显示条件来源/能否必填），
 * 不放文案与组件：各端的展示差异是合理的（桌面"单行文本"、移动"单行文本"，布局也各写各的）。
 *
 * <p>生成物是**发布产物**：改这里 → 重跑生成（见 {@code FieldTypeCatalogSpecTest}）→ 提交；
 * 生成物与代码不一致时后端测试直接红。
 */
public final class FieldTypeCatalog {

    /**
     * 字段在 schema 里扮演的角色——决定值形状与各处特殊分支，与展示无关。
     *
     * <p>{@code FIELD} 之外的每一种在代码里都有专门分支：{@code CONTAINER} 与父级共用扁平值域、
     * {@code TABLE} 值是按行数组（行内另有字段作用域）、{@code CHECKLIST} 值是逐项结构（含照片）、
     * {@code MATRIX} 值是按行列矩阵、{@code DISPLAY} 不产生值。
     */
    public enum Kind { FIELD, DISPLAY, CONTAINER, TABLE, CHECKLIST, MATRIX }

    /**
     * @param id            与 schema 里 {@code node.type} 一致
     * @param kind          角色（见 {@link Kind}）
     * @param media         值里含"上传后要等服务端就绪"的文件（上传类与检查项照片分开处理）
     * @param options       配置项里有可选项（下拉/单选/多选）
     * @param displaySource 能作为 displayCondition 的来源（设计器只允许 select，见
     *                      {@code useFormDesignerStore.allowedDisplayRuleTargetIds}）
     * @param requiredAble  能被标为必填（容器/明细表/纯展示不行）
     */
    public record FieldType(String id, Kind kind, boolean media, boolean options,
                            boolean displaySource, boolean requiredAble) { }

    private static FieldType field(String id) {
        return new FieldType(id, Kind.FIELD, false, false, false, true);
    }

    private static FieldType media(String id) {
        return new FieldType(id, Kind.FIELD, true, false, false, true);
    }

    private static FieldType options(String id) {
        return new FieldType(id, Kind.FIELD, false, true, false, true);
    }

    /** 26 种，按 id 排序——生成物也按这个顺序，diff 稳定。 */
    public static final List<FieldType> ALL = List.of(
        media("audio_upload"),
        options("checkbox"),
        new FieldType("checklist", Kind.CHECKLIST, false, false, false, true),
        field("date"),
        field("date_range"),
        field("dept_picker"),
        new FieldType("description", Kind.DISPLAY, false, false, false, false),
        media("file_upload"),
        media("image_upload"),
        field("location"),
        new FieldType("matrix_fill", Kind.MATRIX, false, false, false, true),
        field("money"),
        options("multi_select"),
        field("number"),
        options("radio"),
        field("scan_code"),
        field("search"),
        new FieldType("select", Kind.FIELD, false, true, true, true),
        new FieldType("span_layout", Kind.CONTAINER, false, false, false, false),
        field("switch"),
        new FieldType("table_list", Kind.TABLE, false, false, false, false),
        field("text"),
        field("textarea"),
        field("time"),
        field("user_picker"),
        media("video_upload")
    );

    private FieldTypeCatalog() { }

    public static Set<String> ids() {
        return ALL.stream().map(FieldType::id).collect(Collectors.toUnmodifiableSet());
    }

    public static Set<String> idsOfKind(Kind kind) {
        return ALL.stream().filter(type -> type.kind() == kind)
            .map(FieldType::id).collect(Collectors.toUnmodifiableSet());
    }

    /** 规格版本：spec 的形状变了（新增/改名属性）才 +1，加字段类型不算。 */
    public static final int SPEC_VERSION = 1;

    /** 生成物内容（{@code fixtures/field-semantics/field-types.json}）。同一个函数既用于生成也用于对账。 */
    public static String toJson(ObjectMapper json) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("specVersion", SPEC_VERSION);
        root.put("note", "字段类型的唯一真源是后端 com.antflow.form.FieldTypeCatalog；本文件由 "
            + "FieldTypeCatalogSpecTest 生成，不要手改。两端注册表与它对账：缺类型必须显式记录。");
        root.put("roles", List.of(
            "FIELD=单值输入", "DISPLAY=不产生值", "CONTAINER=children 与父级共用扁平值域",
            "TABLE=值是按行数组（行内另有字段作用域）", "CHECKLIST=值逐项（含照片）", "MATRIX=按行列矩阵"));
        List<Map<String, Object>> types = ALL.stream().map(type -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", type.id());
            entry.put("kind", type.kind().name());
            entry.put("media", type.media());
            entry.put("options", type.options());
            entry.put("displaySource", type.displaySource());
            entry.put("requiredAble", type.requiredAble());
            return entry;
        }).toList();
        root.put("fieldTypes", types);
        try {
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("cannot render field type spec", e);
        }
    }
}
