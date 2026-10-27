package com.antflow.form.importer;

import com.antflow.engine.BizException;
import com.antflow.form.FieldTypeCatalog;
import com.antflow.form.options.OptionRuntimeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 单元格文本 → {@code t_form_data.data} 里的原始值。26 种字段类型全覆盖。
 *
 * <p>这是导入链路里**唯一**知道"每种类型怎么读"的地方。规则集中在这里的理由和
 * {@code FormFieldValueValidator} 一样：散开就会出现"模板允许这么填、导入却读不懂"。
 *
 * <p>四条硬约束：
 * <ol>
 *   <li>**批量预取，禁止逐行查库**：人员/部门/外部选项在 {@link #prefetch} 里各查一次，
 *       {@link #decode} 只读内存索引。2 万行的表逐行查一次人员就会把接口拖成分钟级。</li>
 *   <li>**重名不猜**：人员/部门重名时报错并列出候选（工号或全路径）。猜错比报错更糟——
 *       历史补录的数据一旦挂错人，后面所有审批与统计都是错的。</li>
 *   <li>**选项按 label 反查 value**：界面上用户看到的是 label，库里存的是 value；
 *       也接受直接给 value（补录数据常来自其它系统的导出）。</li>
 *   <li>**"不支持"也要显式**：媒体四类、description、span_layout 在 {@link #HANDLED_TYPES}
 *       里登记，但 decode 会给出明确的 {@code IMPORT_FIELD_UNSUPPORTED}，
 *       而不是掉进 default 变成"未知类型"。</li>
 * </ol>
 *
 * <p>**本类只做两件事**：预取哪些字典（{@link #prefetch}）、一个单元格该交给谁
 * （{@link #decode} 的 switch）。具体读法按"变化原因"分到四个族：
 * 标量 {@link FormImportScalarCodec}、选项 {@link FormImportOptionCodec}、
 * 人员部门 {@link FormImportOrgCodec}、结构化 {@link FormImportStructuredCodec}。
 * 加一种字段类型通常只改一族 + 这里的一行路由。
 */
@Component
public class FormImportValueCodec {

    /** 与解析器同一个上限：单格 500 字符。超长在 service 里先拦，这里做第二道。 */
    public static final int MAX_CELL_LENGTH = com.antflow.form.options.OptionTableImporter.MAX_CELL_LENGTH;

    /**
     * 22 种类型的显式清单。测试按 {@link FieldTypeCatalog#ids()} 逐项对账——
     * 新增类型忘了在这里登记，测试会红（而不是悄悄落进 default）。
     */
    public static final Set<String> HANDLED_TYPES = Set.of(
        "text", "textarea", "scan_code",
        "number", "money",
        "date", "time", "date_range",
        "select", "multi_select",
        "user_picker", "dept_picker", "location",
        "table_list", "checklist", "matrix_fill",
        "description", "span_layout",
        "file_upload", "image_upload", "video_upload", "audio_upload");

    private final ObjectMapper json;
    private final Directory directory;
    private final FormImportScalarCodec scalar;
    private final FormImportOptionCodec option;
    private final FormImportOrgCodec org;
    private final FormImportStructuredCodec structured;

    @Autowired
    public FormImportValueCodec(ObjectMapper json, JdbcTemplate jdbc,
                                FormImportScalarCodec scalar, FormImportOptionCodec option,
                                FormImportOrgCodec org, FormImportStructuredCodec structured) {
        this.json = json;
        this.directory = new FormImportJdbcDirectory(jdbc, json);
        this.scalar = scalar;
        this.option = option;
        this.org = org;
        this.structured = structured;
    }

    /** 测试用构造器：直接注入内存目录，避免为了解码去 mock 一堆 SQL。 */
    FormImportValueCodec(ObjectMapper json, Directory directory) {
        this.json = json;
        this.directory = directory;
        this.scalar = new FormImportScalarCodec();
        this.option = new FormImportOptionCodec(json);
        this.org = new FormImportOrgCodec();
        this.structured = new FormImportStructuredCodec(json);
    }

    // ---------------------------------------------------------------- 目录

    /** 预取数据源。生产实现是 {@link FormImportJdbcDirectory}，测试给内存实现。 */
    public interface Directory {
        /** @param tokens 工号、姓名或 {@code id:123}；实现方按这三种形态一次性查回 */
        List<UserRef> users(Collection<String> tokens);

        List<DeptRef> departments();

        /** 某个选项源版本的全部行（原样列名 → 文本值）。 */
        List<Map<String, String>> optionRows(long versionId);
    }

    public record UserRef(long id, String employeeNo, String displayName) { }

    /** {@code parentId} 用于在内存里拼全路径（总部/研发部/平台组）。 */
    public record DeptRef(long id, String name, Long parentId) { }

    /**
     * 一次导入用到的全部"字典"，在解析前一次性装好。
     *
     * <p>刻意做成不可变对象而不是缓存：一次导入一行代码只用一个实例，
     * 缓存反而会在"表单改选项/人员调岗"后返回旧值。
     *
     * <p>**注意**：这是门面对外暴露的数据契约（service 与测试都按
     * {@code FormImportValueCodec.Prefetch} 引用）。四个族 codec 只读它的字段，
     * 不会拿它回调本类的方法。
     */
    public static final class Prefetch {
        // 包内可见：四个族 codec 只读这些索引。它们都是构造后不再改的不可变引用，
        // 加上 7 个 getter 只会让调用点变噪音。
        final Map<Long, UserRef> usersById;
        final Map<String, UserRef> usersByEmployeeNo;
        final Map<String, List<UserRef>> usersByName;
        final Map<Long, DeptRef> deptsById;
        final Map<String, List<DeptRef>> deptsByName;
        final Map<String, List<DeptRef>> deptsByPath;
        final Map<Long, List<Map<String, String>>> optionRows;

        private Prefetch(Map<Long, UserRef> usersById, Map<String, UserRef> usersByEmployeeNo,
                         Map<String, List<UserRef>> usersByName, Map<Long, DeptRef> deptsById,
                         Map<String, List<DeptRef>> deptsByName,
                         Map<String, List<DeptRef>> deptsByPath,
                         Map<Long, List<Map<String, String>>> optionRows) {
            this.usersById = usersById;
            this.usersByEmployeeNo = usersByEmployeeNo;
            this.usersByName = usersByName;
            this.deptsById = deptsById;
            this.deptsByName = deptsByName;
            this.deptsByPath = deptsByPath;
            this.optionRows = optionRows;
        }

        static Prefetch empty() {
            return new Prefetch(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }
    }

    // ---------------------------------------------------------------- 预取

    /**
     * 扫描整张表，算出需要查哪些人、哪些部门、哪些选项版本，然后**各查一次**。
     *
     * @param schema 当前表单的 schema（**不是模板里的**：模板可能已过期，以库里为准）
     * @param rows   数据行，key 是列上的 fieldId
     */
    public Prefetch prefetch(JsonNode schema, List<Map<String, String>> rows) {
        Set<String> tokens = new LinkedHashSet<>();
        Set<Long> versionIds = new LinkedHashSet<>();
        for (JsonNode node : schema) {
            collectPrefetch(node, rows, tokens, versionIds);
        }
        List<UserRef> users = tokens.isEmpty() ? List.of() : directory.users(tokens);
        List<DeptRef> departments = directory.departments();

        Map<Long, UserRef> usersById = new LinkedHashMap<>();
        Map<String, UserRef> usersByEmployeeNo = new LinkedHashMap<>();
        Map<String, List<UserRef>> usersByName = new LinkedHashMap<>();
        for (UserRef user : users) {
            usersById.putIfAbsent(user.id(), user);
            if (user.employeeNo() != null && !user.employeeNo().isBlank()) {
                usersByEmployeeNo.putIfAbsent(user.employeeNo().toLowerCase(Locale.ROOT), user);
            }
            if (user.displayName() != null && !user.displayName().isBlank()) {
                usersByName.computeIfAbsent(user.displayName().trim(), key -> new ArrayList<>()).add(user);
            }
        }

        Map<Long, DeptRef> deptsById = new LinkedHashMap<>();
        departments.forEach(dept -> deptsById.put(dept.id(), dept));
        Map<Long, String> pathMemo = new LinkedHashMap<>();
        Map<String, List<DeptRef>> deptsByName = new LinkedHashMap<>();
        Map<String, List<DeptRef>> deptsByPath = new LinkedHashMap<>();
        for (DeptRef dept : departments) {
            if (dept.name() != null && !dept.name().isBlank()) {
                deptsByName.computeIfAbsent(dept.name().trim(), key -> new ArrayList<>()).add(dept);
            }
            String path = FormImportOrgCodec.deptPath(dept, deptsById, pathMemo, new LinkedHashSet<>());
            deptsByPath.computeIfAbsent(path, key -> new ArrayList<>()).add(dept);
        }

        Map<Long, List<Map<String, String>>> options = new LinkedHashMap<>();
        versionIds.forEach(versionId -> options.put(versionId, directory.optionRows(versionId)));

        return new Prefetch(usersById, usersByEmployeeNo, usersByName, deptsById,
            deptsByName, deptsByPath, options);
    }

    private void collectPrefetch(JsonNode node, List<Map<String, String>> rows,
                                 Set<String> tokens, Set<Long> versionIds) {
        String type = node.path("type").asText("");
        String id = node.path("id").asText("");
        if (FieldTypeCatalog.idsOfKind(FieldTypeCatalog.Kind.CONTAINER).contains(type)) {
            node.path("children").forEach(child -> collectPrefetch(child, rows, tokens, versionIds));
            return;
        }
        if ("user_picker".equals(type) || "dept_picker".equals(type)) {
            if (id.isBlank()) return;
            for (Map<String, String> row : rows) {
                String text = row.get(id);
                if (text != null && !text.isBlank()) tokens.addAll(FormImportSupport.split(text));
            }
            return;
        }
        if (Set.of("select", "multi_select").contains(type)
            && OptionRuntimeService.isBound(node.path("props").path("optionSource"))) {
            long versionId = node.path("props").path("optionSource").path("versionId").asLong(0);
            if (versionId > 0) versionIds.add(versionId);
            return;
        }
        if ("table_list".equals(type)) {
            if (id.isBlank()) return;
            for (Map<String, String> row : rows) {
                String text = row.get(id);
                if (text == null || text.isBlank()) continue;
                JsonNode array = FormImportSupport.readJson(json, text);
                if (array == null || !array.isArray()) continue;
                for (JsonNode item : array) {
                    if (!item.isObject()) continue;
                    node.path("children").forEach(child ->
                        collectFromJson(child, item, tokens, versionIds));
                }
            }
        }
    }

    /** 明细行里的子字段：值已经是 JSON 里的标量，转成文本后按同一套规则收集。 */
    private void collectFromJson(JsonNode node, JsonNode item,
                                 Set<String> tokens, Set<Long> versionIds) {
        String type = node.path("type").asText("");
        String id = node.path("id").asText("");
        if (FieldTypeCatalog.idsOfKind(FieldTypeCatalog.Kind.CONTAINER).contains(type)) {
            node.path("children").forEach(child -> collectFromJson(child, item, tokens, versionIds));
            return;
        }
        if (id.isBlank()) return;
        JsonNode raw = item.path(id);
        if (raw.isMissingNode() || raw.isNull()) return;
        if (("user_picker".equals(type) || "dept_picker".equals(type))) {
            String text = raw.isTextual() ? raw.asText() : raw.toString();
            tokens.addAll(FormImportSupport.split(text));
            return;
        }
        if (Set.of("select", "multi_select").contains(type)
            && OptionRuntimeService.isBound(node.path("props").path("optionSource"))) {
            long versionId = node.path("props").path("optionSource").path("versionId").asLong(0);
            if (versionId > 0) versionIds.add(versionId);
        }
    }

    // ---------------------------------------------------------------- 解码

    /**
     * 把一个单元格解成原始值。空串返回 {@code null}（调用方据此跳过该字段）。
     *
     * @throws BizException {@code IMPORT_VALUE_INVALID}（值不合法）或
     *                      {@code IMPORT_FIELD_UNSUPPORTED}（类型不支持导入）
     */
    public Object decode(JsonNode field, String text, Prefetch prefetch) {
        String type = field.path("type").asText("");
        String label = FormImportSupport.label(field);
        String value = text == null ? "" : text;
        if (value.isBlank()) return null;
        if (value.length() > MAX_CELL_LENGTH) {
            throw FormImportSupport.invalid(label, "超过 " + MAX_CELL_LENGTH + " 个字符");
        }
        return switch (type) {
            case "text", "textarea", "scan_code" -> value;
            case "number", "money" -> FormImportScalarCodec.number(label, value);
            case "date" -> scalar.parseDate(label, value, field).toString();
            case "time" -> scalar.parseTime(label, value, field);
            case "date_range" -> scalar.dateRange(label, value, field);
            case "select" -> option.singleOption(field, label, value, prefetch);
            case "multi_select" -> option.multiOption(field, label, value, prefetch);
            case "user_picker" -> org.users(field, label, value, prefetch);
            case "dept_picker" -> org.departments(field, label, value, prefetch);
            case "location" -> org.location(label, value);
            case "table_list" -> structured.tableList(field, label, value, prefetch, this::decode);
            case "checklist" -> structured.checklist(field, label, value);
            case "matrix_fill" -> structured.matrix(field, label, value);
            case "description", "span_layout" ->
                throw FormImportSupport.unsupported(label, "该类型不产生可导入的值");
            case "file_upload", "image_upload", "video_upload", "audio_upload" ->
                throw FormImportSupport.unsupported(label, "媒体字段无法通过 Excel 导入，请改用表单填写");
            default -> throw FormImportSupport.unsupported(label, "未知字段类型：" + type);
        };
    }
}
