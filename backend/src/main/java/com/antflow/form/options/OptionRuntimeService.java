package com.antflow.form.options;

import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.HiddenResourceException;
import com.antflow.authz.PermissionCodes;
import com.antflow.engine.BizException;
import com.antflow.engine.tree.ProcessTreeNav;
import com.antflow.process.DefinitionVersionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OptionRuntimeService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuthorizationService authorization;
    private final DefinitionVersionRepository versions;

    public OptionPage query(OptionQuery request) { return querySchema(schema(request), request); }

    public OptionPage preview(long formId, PreviewQuery request) {
        authorization.requireFormMaintenance(formId, PermissionCodes.FORM_DEFINITION_MANAGE);
        if (request == null || request.schema() == null || !request.schema().isArray()) bad("预览表单无效");
        validateSchema(request.schema());
        return querySchema(request.schema(), request.query());
    }

    private OptionPage querySchema(JsonNode schema, OptionQuery request) {
        if (request == null || request.fieldId() == null || request.fieldId().isBlank()) bad("fieldId 不能为空");
        if (request.page() < 0 || request.page() > 1_000_000 || request.size() < 0 || request.size() > 100) bad("分页参数无效");
        if (request.keyword() != null && request.keyword().length() > 500) bad("搜索内容过长");
        List<JsonNode> scope = scopeFor(schema, request.fieldId());
        JsonNode field = field(scope, request.fieldId());
        if (field == null) throw new HiddenResourceException("option field not found");
        JsonNode source = source(field, scope);
        requireVersion(source, false);
        List<String> path = request.path() == null ? List.of() : request.path();
        if (path.size() > 10 || path.stream().anyMatch(v -> v == null || v.length() > 500)) bad("分步路径无效");
        List<String> selected = request.selectedValues();
        if (selected != null && (selected.size() > 100 || selected.stream().anyMatch(v -> v == null || v.length() > 500))) bad("回查值无效");
        Map<String, Object> values = request.values() == null ? Map.of() : request.values();
        Map<String, String> filters = new LinkedHashMap<>();
        // Label lookup ignores editing paths, but only uses the field's immutable version.
        if (selected == null && !dependencyFilters(field, scope, values, filters, new LinkedHashSet<>())) {
            return new OptionPage("OPTIONS", 0, 0, List.of(), 0, 1, size(request));
        }
        List<Map<String, String>> rows = rows(source, filters);
        String valueColumn = text(source, "valueColumn"), labelColumn = text(source, "labelColumn");
        JsonNode cascade = source.path("cascade");
        int depth = depth(cascade);
        if (path.size() > depth) bad("分步路径无效");
        boolean level = selected == null && path.size() < depth;
        List<OptionValue> items = new ArrayList<>();
        for (Map<String, String> row : rows) {
            if (selected != null) {
                if (selected.contains(row.get(valueColumn))) items.add(new OptionValue(row.get(valueColumn), row.get(labelColumn)));
                continue;
            }
            List<String> parts = parts(row, cascade);
            if (parts.size() != depth || !parts.subList(0, path.size()).equals(path)) continue;
            items.add(level ? new OptionValue(parts.get(path.size()), parts.get(path.size()))
                : new OptionValue(row.get(valueColumn), row.get(labelColumn)));
        }
        String keyword = request.keyword() == null ? "" : request.keyword().trim().toLowerCase(Locale.ROOT);
        items = items.stream().filter(item -> item.value() != null && !item.value().isBlank())
            .distinct().filter(item -> keyword.isEmpty() || item.value().toLowerCase(Locale.ROOT).contains(keyword)
                || item.label().toLowerCase(Locale.ROOT).contains(keyword))
            .sorted(Comparator.comparing(OptionValue::label).thenComparing(OptionValue::value)).toList();
        int page = Math.max(1, request.page()), size = selected == null ? size(request) : 100;
        int from = (int) Math.min(items.size(), (long) (page - 1) * size);
        return new OptionPage(level ? "LEVEL" : "OPTIONS", path.size(), depth,
            items.subList(from, Math.min(items.size(), from + size)), items.size(), page, size);
    }

    public void validateBinding(JsonNode source) { requireVersion(source, true); }

    public void validateValue(JsonNode field, Object value, Map<?, ?> values) {
        if (empty(value)) return;
        JsonNode source = field.path("props").path("optionSource");
        // 未真正绑定（`{}`）时按静态选项处理，不该走到外部值校验——调用方也会用 isBound 过滤，
        // 这里是第二道，避免别的入口把 `{}` 送进来直接报 BAD_SCHEMA。
        if (!isBound(source)) return;
        requireVersion(source, false);
        boolean multiple = "multi_select".equals(field.path("type").asText());
        if (multiple != (value instanceof List<?>)) invalid("下拉值类型不正确");
        List<?> selected = value instanceof List<?> list ? list : List.of(value);
        Map<String, String> filters = new LinkedHashMap<>();
        JsonNode dependency = source.path("dependency");
        if (dependency.isObject()) {
            Object parent = values.get(text(dependency, "fieldId"));
            if (!(parent instanceof String) || empty(parent)) invalid("请先选择上游字段");
            filters.put(text(dependency, "matchColumn"), String.valueOf(parent));
        }
        Set<String> allowed = new LinkedHashSet<>();
        rows(source, filters).forEach(row -> allowed.add(row.get(text(source, "valueColumn"))));
        if (selected.size() > 100 || new LinkedHashSet<>(selected).size() != selected.size()
            || selected.stream().anyMatch(v -> !(v instanceof String) || !allowed.contains(v))) invalid("包含不存在或与上游不匹配的选项");
    }

    /**
     * 新增绑定必须在源侧的「可引用表单」清单（{@code t_form_option_source}）里。
     *
     * <p>口径是**差集**，不是"新 schema 里每个绑定都要有引用行"：引用可以被撤销，而撤销的语义是
     * "只影响新绑定，已经绑着它的表单不受影响"（同 bindable 里第二条 EXISTS、前端 OptionSources
     * 的提示文案）。拿全量去卡，会让"引用被撤销但字段仍绑定"的表单再也保存/发布不了。
     *
     * <p>差集的单位是**「字段 + 数据源」这个绑定对**，不是单独的数据源 id：表单原本只有字段 A 绑
     * 着 S、S 的引用又被撤销时，若只比 sourceId 集合，新加字段 B 也绑 S 会被当成"旧绑定"放行。
     *
     * @param previousSchemaJson 这张表单**改动前**的 schema（新建表单传 null）
     * @param newSchemaJson      本次要写入的 schema
     */
    public void requireNewBindingsAreReferenced(long formId, String previousSchemaJson,
                                                String newSchemaJson) {
        Set<String> before = new LinkedHashSet<>();
        collectBoundPairs(parse(previousSchemaJson), before);
        Set<String> after = new LinkedHashSet<>();
        collectBoundPairs(parse(newSchemaJson), after);
        after.removeAll(before);
        Set<Long> required = new LinkedHashSet<>();
        for (String pair : after) {
            required.add(Long.parseLong(pair.substring(pair.indexOf('\u0000') + 1)));
        }
        for (Long sourceId : required) {
            Boolean referenced = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM t_form_option_source
                               WHERE form_def_id = ? AND source_id = ?)
                """, Boolean.class, formId, sourceId);
            if (!Boolean.TRUE.equals(referenced)) {
                throw new BizException("OPTION_SOURCE_NOT_REFERENCED",
                    "这张表单还没被授权引用该数据源，请先在数据源页把它加回"
                        + "「可引用表单」：sourceId=" + sourceId);
            }
        }
    }

    /**
     * 收集 schema 里所有已绑定的**绑定对**（`字段 id + \0 + sourceId`，含 span_layout 与
     * table_list 内部的字段）。用字段 id 而不是只记 sourceId，才能区分"同一个源被绑到新字段上"。
     */
    private void collectBoundPairs(JsonNode schema, Set<String> target) {
        if (schema == null || !schema.isArray()) return;
        for (JsonNode node : flatten(schema)) {
            if ("table_list".equals(node.path("type").asText())) {
                collectBoundPairs(node.path("children"), target);
            }
            JsonNode source = node.path("props").path("optionSource");
            if (isBound(source)) {
                target.add(node.path("id").asText() + '\u0000' + source.path("sourceId").asLong());
            }
        }
    }


    /** A detail row is a separate value scope; columns share their parent's scope. */
    public void validateSchema(JsonNode schema) {
        if (!schema.isArray()) bad("表单结构无效");
        List<JsonNode> scope = flatten(schema);
        for (JsonNode node : scope) {
            if ("table_list".equals(node.path("type").asText())) validateSchema(node.path("children"));
            if (!dynamic(node)) continue;
            JsonNode source = source(node, scope);
            requireVersion(source, true);
            validateChain(node, scope, new LinkedHashSet<>());
            Map<String, String> labels = new LinkedHashMap<>();
            for (Map<String, String> row : rows(source, Map.of())) {
                String value = row.get(text(source, "valueColumn")), label = row.get(text(source, "labelColumn"));
                if (value == null || value.isBlank() || label == null || label.isBlank()) bad(node.path("label").asText() + "：值列和显示列不能有空值");
                String previous = labels.putIfAbsent(value, label);
                if (previous != null && !previous.equals(label)) bad("同一个选项值对应多个显示名称：" + value);
                if (parts(row, source.path("cascade")).size() != depth(source.path("cascade"))) bad("分步分类列有空值或编码长度不足：" + value);
            }
        }
    }

    private void validateChain(JsonNode node, List<JsonNode> scope, Set<String> visited) {
        if (!visited.add(node.path("id").asText())) bad("选项联动不能循环引用");
        String parentId = parentId(node);
        if (parentId.isEmpty()) return;
        JsonNode parent = field(scope, parentId);
        if (parent == null || !"select".equals(parent.path("type").asText()) || !isBound(parent.path("props").path("optionSource"))) bad("联动来源必须是同一表单或明细行内的外部单选下拉");
        JsonNode source = source(node, scope), parentSource = source(parent, scope);
        if (!Objects.equals(source.get("versionId"), parentSource.get("versionId"))
            || !Objects.equals(source.get("sourceId"), parentSource.get("sourceId"))) bad("联动字段必须使用相同数据源版本");
        if (isBound(node.path("props").path("optionSource"))
            && !text(source.path("dependency"), "matchColumn").equals(text(parentSource, "valueColumn"))) bad("联动匹配列必须等于上游下拉的值列");
        validateChain(parent, scope, visited);
    }

    public void validateSubmission(JsonNode schema, Map<?, ?> values) {
        List<JsonNode> scope = flatten(schema);
        for (JsonNode node : scope) {
            Object value = values.get(node.path("id").asText());
            if ("table_list".equals(node.path("type").asText()) && value instanceof List<?> rows) {
                for (Object row : rows) if (row instanceof Map<?, ?> map) validateSubmission(node.path("children"), map);
            }
            if (!isBound(node.path("props").path("optionSource")) || empty(value)) continue;
            JsonNode source = source(node, scope);
            requireVersion(source, false);
            boolean multiple = "multi_select".equals(node.path("type").asText());
            if (multiple != (value instanceof List<?>)) invalid("下拉值类型不正确");
            List<?> selected = value instanceof List<?> list ? list : List.of(value);
            if (selected.size() > 100 || new LinkedHashSet<>(selected).size() != selected.size()) invalid("下拉选择数量无效或重复");
            Map<String, String> filters = new LinkedHashMap<>();
            if (!dependencyFilters(node, scope, values, filters, new LinkedHashSet<>())) invalid("请先选择上游字段");
            Set<String> allowed = new LinkedHashSet<>();
            rows(source, filters).forEach(row -> allowed.add(row.get(text(source, "valueColumn"))));
            for (Object item : selected) if (!(item instanceof String) || !allowed.contains(item)) invalid("包含不存在或与上游不匹配的选项");
        }
    }

    private boolean dependencyFilters(JsonNode node, List<JsonNode> scope, Map<?, ?> values,
                                      Map<String, String> filters, Set<String> visited) {
        if (!visited.add(node.path("id").asText())) bad("选项联动不能循环引用");
        String parentId = parentId(node);
        if (parentId.isEmpty()) return true;
        JsonNode parent = field(scope, parentId);
        if (parent == null) bad("联动来源字段不存在");
        Object value = values.get(parentId);
        if (!(value instanceof String) || empty(value)) return false;
        JsonNode parentSource = source(parent, scope);
        String column = node.path("props").path("dataLinkage").isObject() ? text(parentSource, "valueColumn")
            : text(source(node, scope).path("dependency"), "matchColumn");
        String old = filters.put(column, String.valueOf(value));
        if (old != null && !old.equals(value)) return false;
        return dependencyFilters(parent, scope, values, filters, visited);
    }

    private JsonNode source(JsonNode field, List<JsonNode> scope) {
        JsonNode props = field.path("props");
        if (isBound(props.path("optionSource"))) {
            if (!Set.of("select", "multi_select").contains(field.path("type").asText())) bad("外部选项仅支持下拉组件");
            return props.path("optionSource");
        }
        JsonNode link = props.path("dataLinkage");
        if (!link.isObject() || !Set.of("text", "textarea", "number").contains(field.path("type").asText())) bad("字段没有外部数据配置");
        JsonNode parent = field(scope, text(link, "fieldId"));
        if (parent == null || !isBound(parent.path("props").path("optionSource"))) bad("联动来源字段无效");
        ObjectNode source = parent.path("props").path("optionSource").deepCopy();
        source.remove(List.of("cascade", "dependency"));
        source.put("valueColumn", text(link, "valueColumn"));
        source.put("labelColumn", text(link, "valueColumn"));
        return source;
    }

    private void requireVersion(JsonNode source, boolean forBinding) {
        if (!source.path("sourceId").isIntegralNumber() || source.path("sourceId").asLong() <= 0
            || !source.path("versionId").isIntegralNumber() || source.path("versionId").asLong() <= 0) bad("数据源和版本不能为空");
        // forBinding 时额外拒绝"已停用（下架）"的版本：否则维护人直接提交带该 versionId 的 schema
        // 再发布，就绕过了"停用后不出现在候选里"的约束。
        // 运行时读路径（forBinding=false）**必须保持不校验**，否则钉着停用版的历史表单会 422。
        // forBinding 时还顺手把数据源行按共享模式（FOR SHARE）锁住：取消发布（unpublish）走的是
        // `SELECT ... FOR UPDATE`，两边不上同一把锁就会漏出"扫描时还没有引用、随后被这次发布写进
        // 快照"的窗口——结果是刚发布的表单钉着一个已退回待发布的版本，下拉直接 422。
        // 读路径不加锁（也不能加），否则热路径全被串行化。
        JsonNode columns = jdbc.query("""
            SELECT v.columns_json::text FROM t_option_data_source_version v
            JOIN t_option_data_source s ON s.id = v.source_id
            WHERE s.id = ? AND v.id = ? AND v.status = 'PUBLISHED'
            """ + (forBinding
                ? " AND s.status = 'ACTIVE' AND v.disabled_at IS NULL FOR SHARE OF s"
                : ""),
            rs -> rs.next() ? parse(rs.getString(1)) : null, source.path("sourceId").asLong(), source.path("versionId").asLong());
        if (columns == null) bad("数据源版本不存在、未发布或不可绑定");
        Set<String> allowed = new LinkedHashSet<>(); columns.forEach(c -> allowed.add(c.asText()));
        for (String column : requiredColumns(source)) if (column.isBlank() || !allowed.contains(column)) bad("选项列映射不存在：" + column);
        depth(source.path("cascade"));
        if (forBinding && !PrincipalHolder.current().orElseThrow().isAdmin()) {
            Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_option_data_source_grant g WHERE g.source_id = ? AND (
                  (g.subject_type = 'USER' AND g.subject_id = ?) OR
                  (g.subject_type = 'ROLE' AND g.subject_id IN (
                    SELECT ur.role_id FROM t_user_role ur JOIN t_role r ON r.id = ur.role_id AND r.enabled = true WHERE ur.user_id = ?)))
                """, Long.class, source.path("sourceId").asLong(), authorization.currentUserId(), authorization.currentUserId());
            if (count == null || count == 0) throw new HiddenResourceException("option source not found");
        }
    }

    private Set<String> requiredColumns(JsonNode source) {
        Set<String> columns = new LinkedHashSet<>(List.of(text(source, "valueColumn"), text(source, "labelColumn")));
        JsonNode cascade = source.path("cascade");
        if ("columns".equals(cascade.path("kind").asText())) cascade.path("levelColumns").forEach(c -> columns.add(c.asText()));
        if ("split".equals(cascade.path("kind").asText())) columns.add(text(cascade, "sourceColumn"));
        if (source.path("dependency").isObject()) columns.add(text(source.path("dependency"), "matchColumn"));
        return columns;
    }

    private List<Map<String, String>> rows(JsonNode source, Map<String, String> filters) {
        List<String> columns = new ArrayList<>(requiredColumns(source));
        List<Object> args = new ArrayList<>();
        String select = String.join(", ", columns.stream().map(c -> { args.add(c); return "data ->> CAST(? AS text)"; }).toList());
        args.add(source.path("versionId").asLong());
        StringBuilder where = new StringBuilder(" WHERE version_id = ?");
        filters.forEach((column, value) -> { where.append(" AND data ->> CAST(? AS text) = ?"); args.add(column); args.add(value); });
        // ponytail: imports cap each version at 20k rows; project only used columns. Move DISTINCT/paging to SQL if profiling warrants it.
        return jdbc.query("SELECT " + select + " FROM t_option_data_source_row" + where + " ORDER BY row_no",
            (rs, index) -> { Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < columns.size(); i++) row.put(columns.get(i), Objects.toString(rs.getString(i + 1), ""));
                return row;
            }, args.toArray());
    }

    private JsonNode schema(OptionQuery request) {
        if (request == null) bad("查询不能为空");
        boolean form = request.formCode() != null && !request.formCode().isBlank();
        if ((form ? 1 : 0) + (request.instanceId() != null ? 1 : 0) + (request.dataId() != null ? 1 : 0) != 1) bad("查询必须指定一个表单、实例或填报记录");
        if (form) {
            var definition = versions.runtimeFormByCode(request.formCode());
            if (definition == null) throw new HiddenResourceException("form not found");
            authorization.requireFormUse(definition.getId());
            if (request.formVersion() == null || !request.formVersion().equals(definition.getVersion())) throw new BizException("FORM_VERSION_CONFLICT", "表单已更新，请刷新后重新填写");
            var process = versions.runtimeProcessForForm(definition.getId());
            if (process != null) requireVisible(parse(process.getProcess()), request.fieldId());
            return parse(definition.getSchema());
        }
        long user = authorization.currentUserId();
        if (request.instanceId() != null) {
            if (!authorization.canReadFullInstance(request.instanceId(), user)) throw new HiddenResourceException("instance not found");
            var context = jdbc.query("""
                SELECT COALESCE(v.schema, legacy.schema, f.schema)::text AS schema,
                       i.process_snapshot::text AS process, i.started_by
                FROM t_process_instance i JOIN t_form_data d ON d.id = i.form_data_id
                JOIN t_form_definition f ON f.id = d.form_def_id
                LEFT JOIN t_form_data_revision r ON r.id = i.current_form_revision_id
                LEFT JOIN t_form_definition_version v ON v.id = r.form_definition_version_id
                LEFT JOIN t_form_definition_version legacy ON legacy.form_definition_id = d.form_def_id AND legacy.version_no = d.form_def_version
                WHERE i.id = ?
                """, rs -> rs.next() ? new InstanceSchema(parse(rs.getString(1)), parse(rs.getString(2)), rs.getLong(3)) : null, request.instanceId());
            if (context == null) throw new HiddenResourceException("instance not found");
            requireViewerVisible(context.process(), request.fieldId(), request.instanceId(),
                user, context.owner());
            return context.schema();
        }
        authorization.requireReadableFormData(request.dataId());
        // 版本口径必须和 instanceId 路径一致：那张表优先用实例的"当前修订版"，
        // 只在没有修订版时才退回该行的 form_def_version。否则表单修订升级后，同一条记录
        // 从两个入口会解析出不同的字段与数据源版本（选项显示/查询结果对不上）。
        // 这条 LEFT JOIN + ORDER BY 在有实例时取最新实例、没有实例时 i.* 为 null 自动兜底。
        JsonNode schema = jdbc.query("""
            SELECT COALESCE(v.schema, legacy.schema, f.schema)::text
            FROM t_form_data d
            JOIN t_form_definition f ON f.id = d.form_def_id
            LEFT JOIN t_process_instance i ON i.form_data_id = d.id
            LEFT JOIN t_form_data_revision r ON r.id = i.current_form_revision_id
            LEFT JOIN t_form_definition_version v ON v.id = r.form_definition_version_id
            LEFT JOIN t_form_definition_version legacy
              ON legacy.form_definition_id = d.form_def_id AND legacy.version_no = d.form_def_version
            WHERE d.id = ?
            ORDER BY i.id DESC NULLS LAST LIMIT 1
            """, rs -> rs.next() ? parse(rs.getString(1)) : null, request.dataId());
        if (schema == null) throw new HiddenResourceException("form data not found");
        // 这条填报记录如果属于某个实例，就必须按实例那套可见性判——否则可以拿 dataId
        // 绕开 instanceId 路径的 HIDDEN 检查（两条路径返回的是同一批选项）。没有实例
        // （无流程表单的直接提交）时不存在任何节点权限配置，放行。
        AuthorizedData owner = jdbc.query("""
            SELECT i.id, i.process_snapshot::text AS process, i.started_by
            FROM t_process_instance i
            WHERE i.form_data_id = ?
            ORDER BY i.id DESC LIMIT 1
            """, rs -> rs.next() ? new AuthorizedData(rs.getLong("id"),
                parse(rs.getString("process")), rs.getLong("started_by")) : null, request.dataId());
        if (owner != null) {
            requireViewerVisible(owner.process(), request.fieldId(), owner.instanceId(),
                user, owner.owner());
        }
        return schema;
    }

    /**
     * 按"调用者在这次流程里所处的节点"判隐藏字段。节点级 formPerms 长在审批节点上，
     * 所以非发起人必须先把"我是哪个节点"算出来，不能拿发起人视角（ROOT 的 props.formPerms）糊弄；
     * 而发起人自己填的字段本来就不受审批节点的可见性约束，仍按 ROOT 的 formPerms 判。
     */
    private void requireViewerVisible(JsonNode process, String fieldId, long instanceId,
                                      long user, long owner) {
        if (fieldId == null) return;
        if (user == owner) {
            requireVisible(process, fieldId);
            return;
        }
        String nodeId = viewerNodeId(instanceId, user);
        if (nodeId != null) {
            // 精确：只看我所在节点对该字段的三态配置。
            requireVisible(ProcessTreeNav.findById(process, nodeId), fieldId);
            return;
        }
        // 不是这次流程的处理人（例如只有实例读权限的管理员/监控）。不能一律拒绝——实例详情页
        // 解析"已选值"的显示名也走这个接口，全拒会让管理员的详情页没有选项；但也不能什么都不查，
        // 否则非发起人就能拿到隐藏字段的选项。折中：任一节点把它标成 HIDDEN 就拒。
        requireNotHiddenAnywhere(process, fieldId);
    }

    /** 调用者在这个实例上的节点：优先当前待办，其次最近一次已审批。都没有返回 null。 */
    private String viewerNodeId(long instanceId, long user) {
        String pending = jdbc.query("""
            SELECT node_id FROM t_task
            WHERE proc_inst_id = ? AND assignee_id = ? AND status = 'PENDING'
              AND task_type = 'APPROVAL'
            ORDER BY id DESC LIMIT 1
            """, rs -> rs.next() ? rs.getString(1) : null, instanceId, user);
        if (pending != null) return pending;
        return jdbc.query("""
            SELECT node_id FROM t_task
            WHERE proc_inst_id = ? AND approved_by = ? AND status IN ('APPROVED', 'REJECTED')
            ORDER BY approved_at DESC NULLS LAST, id DESC LIMIT 1
            """, rs -> rs.next() ? rs.getString(1) : null, instanceId, user);
    }

    private void requireVisible(JsonNode node, String fieldId) {
        if (node == null) return;
        for (JsonNode permission : node.path("props").path("formPerms")) {
            if (fieldId != null && fieldId.equals(permission.path("fieldId").asText()) && "HIDDEN".equals(permission.path("mode").asText())) throw new HiddenResourceException("field not found");
        }
    }

    /** 整棵树里任一节点把该字段标成 HIDDEN 就拒（含 branchs 分支）。 */
    private void requireNotHiddenAnywhere(JsonNode node, String fieldId) {
        if (node == null || node.isNull() || !node.has("id")) return;
        requireVisible(node, fieldId);
        if (ProcessTreeNav.isBranch(node)) {
            for (JsonNode branch : node.withArray("branchs")) {
                requireNotHiddenAnywhere(branch, fieldId);
            }
        }
        requireNotHiddenAnywhere(node.get("children"), fieldId);
    }

    static int depth(JsonNode cascade) {
        if (cascade.isMissingNode() || cascade.isNull()) return 0;
        if ("columns".equals(cascade.path("kind").asText())) {
            JsonNode levels = cascade.path("levelColumns");
            if (!levels.isArray() || levels.isEmpty() || levels.size() > 10) bad("分类步骤必须为 1 到 10 级");
            Set<String> seen = new LinkedHashSet<>();
            for (JsonNode c : levels) if (!c.isTextual() || c.asText().isBlank() || !seen.add(c.asText())) bad("分类列不能为空或重复");
            return levels.size();
        }
        JsonNode split = cascade.path("split"), lengths = split.path("lengths");
        if (!"split".equals(cascade.path("kind").asText()) || !"fixed".equals(split.path("kind").asText())
            || !lengths.isArray() || lengths.isEmpty() || lengths.size() > 10) bad("编码前缀步骤必须为 1 到 10 级");
        for (JsonNode length : lengths) if (!length.isIntegralNumber() || length.asInt() < 1 || length.asInt() > 50) bad("每步编码长度必须为 1 到 50");
        return lengths.size();
    }

    static List<String> parts(Map<String, String> row, JsonNode cascade) {
        int depth = depth(cascade);
        List<String> parts = new ArrayList<>();
        if (depth == 0) return parts;
        if ("columns".equals(cascade.path("kind").asText())) {
            for (JsonNode column : cascade.path("levelColumns")) {
                String part = row.get(column.asText()); if (part == null || part.isBlank()) return List.of();
                parts.add(part);
            }
        } else {
            String code = row.get(text(cascade, "sourceColumn"));
            if (code == null) return List.of();
            int offset = 0;
            for (JsonNode length : cascade.path("split").path("lengths")) {
                if (code.codePointCount(offset, code.length()) < length.asInt()) return List.of();
                int end = code.offsetByCodePoints(offset, length.asInt());
                parts.add(code.substring(offset, end)); offset = end;
            }
        }
        return parts;
    }

    private List<JsonNode> flatten(JsonNode nodes) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode node : nodes) {
            result.add(node);
            if ("span_layout".equals(node.path("type").asText())) result.addAll(flatten(node.path("children")));
        }
        return result;
    }
    private List<JsonNode> scopeFor(JsonNode schema, String id) {
        List<JsonNode> scope = flatten(schema);
        if (field(scope, id) != null) return scope;
        for (JsonNode node : scope) if ("table_list".equals(node.path("type").asText())) {
            List<JsonNode> nested = scopeFor(node.path("children"), id); if (!nested.isEmpty()) return nested;
        }
        return List.of();
    }
    private JsonNode field(List<JsonNode> scope, String id) { return scope.stream().filter(n -> n.path("id").asText().equals(id)).findFirst().orElse(null); }
    private static String parentId(JsonNode node) { return node.path("props").path("dataLinkage").isObject()
        ? text(node.path("props").path("dataLinkage"), "fieldId") : text(node.path("props").path("optionSource").path("dependency"), "fieldId"); }
    /**
     * 只有带正整数 sourceId 的 optionSource 才算"已绑定"。
     * 设计器在"选择数据源版本"之前会写入空对象 {@code {}}，那不是绑定，必须按未绑定处理：
     * 否则它会被当成外部数据源走进 requireVersion，报"数据源和版本不能为空"，导致表单无法发布。
     */
    public static boolean isBound(JsonNode source) {
        return source.isObject() && source.path("sourceId").isIntegralNumber()
            && source.path("sourceId").asLong() > 0;
    }
    private static boolean dynamic(JsonNode node) { return isBound(node.path("props").path("optionSource")) || node.path("props").path("dataLinkage").isObject(); }
    private JsonNode parse(String raw) { try { return json.readTree(raw == null ? "{}" : raw); } catch (Exception e) { throw new IllegalStateException("invalid stored schema", e); } }
    private static String text(JsonNode node, String key) { return node.path(key).asText("").trim(); }
    private static boolean empty(Object v) { return v == null || "".equals(v) || v instanceof List<?> list && list.isEmpty(); }
    private static void bad(String message) { throw new BizException("BAD_SCHEMA", message); }
    private static void invalid(String message) { throw new BizException("FORM_DATA_INVALID", message); }
    private static int size(OptionQuery r) { return r.size() == 0 ? 20 : r.size(); }
    private record InstanceSchema(JsonNode schema, JsonNode process, long owner) { }
    /** 按 dataId 反查到的实例可见性上下文。 */
    private record AuthorizedData(long instanceId, JsonNode process, long owner) { }
    public record OptionQuery(String formCode, Integer formVersion, Long instanceId, Long dataId, String fieldId,
        String keyword, int page, int size, Map<String, Object> values, List<String> path, List<String> selectedValues) { }
    public record PreviewQuery(JsonNode schema, OptionQuery query) { }
    public record OptionValue(String value, String label) { }
    public record OptionPage(String stage, int level, int totalLevels, List<OptionValue> items, long total, int page, int size) { }
}
