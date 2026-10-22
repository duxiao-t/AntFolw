package com.antflow.form.options;

import static com.antflow.form.options.OptionSchemaSupport.bad;
import static com.antflow.form.options.OptionSchemaSupport.depth;
import static com.antflow.form.options.OptionSchemaSupport.field;
import static com.antflow.form.options.OptionSchemaSupport.isBound;
import static com.antflow.form.options.OptionSchemaSupport.text;

import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.HiddenResourceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 选项源绑定：从字段 schema 解析出"要用哪个数据源版本"，并按该版本读行。
 *
 * <p>从 {@link OptionRuntimeService} 搬出（2026-10-22 类体积治理第一刀）。搬到一起的原因是
 * 这批方法改动的触发因素只有一个：**绑定语义与版本钉死**。查询、校验、导出都只是它的调用方。
 *
 * <p><b>三条不能动的语义（都是踩过的坑）：</b>
 * <ol>
 *   <li>版本以 schema 里**钉死的 {@code versionId}** 为准。发布时写进 schema 的值任何路径都不重算，
 *       否则已发布表单会在数据源升级后悄悄换一份数据。</li>
 *   <li>{@code forBinding} 才校验发布态与 {@code FOR SHARE} 锁；运行时读路径（false）**必须不校验**，
 *       否则钉着已停用版本的历史单据会 422。锁的用途见 {@link #requireVersion} 里的注释。</li>
 *   <li>{@code dataLinkage} 派生出来的 source 是**副本**（{@code deepCopy} 后改写列映射），
 *       不能直接改父字段的 optionSource——那会把父下拉的显示列也改掉。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
class OptionSourceBinding {
    /** 一次标签回查最多带多少个值：这是一页数据的显示需求，不该退化成全表扫描入口。 */
    private static final int MAX_LABEL_LOOKUP = 500;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuthorizationService authorization;

    void validateBinding(JsonNode field, JsonNode source) { requireVersion(field, source, true); }

    /**
     * 一批已选值 → 选项标签，供台账/导出把 `option_1` 显示成中文。
     *
     * <p>按 schema 里**钉死的 versionId** 查，且**不校验发布态**：历史单据钉着已停用的版本也得
     * 出得来标签（{@link #requireVersion} 的注释说明了读路径为什么不校验）。
     *
     * <p>查不到的值不进结果，由调用方回落原始值——表单删了选项之后，旧单据仍要看得见"当时选的是什么"。
     */
    Map<String, String> labelsForValues(long versionId, String valueColumn,
                                        String labelColumn, Collection<String> values) {
        if (versionId <= 0 || valueColumn.isBlank() || labelColumn.isBlank() || values == null
                || values.isEmpty()) {
            return Map.of();
        }
        // 只查用到的值：整版扫一遍在 2 万行的源上就是每次翻页都白读 2 万行。
        List<String> wanted = values.stream().filter(value -> value != null && !value.isBlank())
            .distinct().limit(MAX_LABEL_LOOKUP).toList();
        if (wanted.isEmpty()) return Map.of();
        String placeholders = String.join(", ", java.util.Collections.nCopies(wanted.size(), "?"));
        List<Object> args = new ArrayList<>(List.of(labelColumn, valueColumn, versionId, valueColumn));
        args.addAll(wanted);
        Map<String, String> labels = new LinkedHashMap<>();
        jdbc.query("SELECT data ->> CAST(? AS text) AS label, data ->> CAST(? AS text) AS value"
                + " FROM t_option_data_source_row"
                + " WHERE version_id = ? AND data ->> CAST(? AS text) IN (" + placeholders + ")",
            rs -> { labels.putIfAbsent(Objects.toString(rs.getString(2), ""),
                Objects.toString(rs.getString(1), "")); }, args.toArray());
        return labels;
    }

    JsonNode source(JsonNode field, List<JsonNode> scope) {
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

    void requireVersion(JsonNode field, JsonNode source, boolean forBinding) {
        // 报错必须带上**宿主的字段标题**：一张表可能有十几个下拉绑着不同数据源，只报
        // "数据源版本不存在"维护人得挨个点开找（表单里看到的是标题，不是 sourceId）。
        String subject = "字段「" + fieldLabel(field) + "」绑定的数据源版本";
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
        if (columns == null) bad(subject + "不存在、未发布或不可绑定");
        Set<String> allowed = new LinkedHashSet<>(); columns.forEach(c -> allowed.add(c.asText()));
        for (String column : requiredColumns(source)) if (column.isBlank() || !allowed.contains(column)) bad(subject + "缺少选项列映射：" + column);
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

    /** 字段标题，空则退回字段 id（同 {@code FormDefinitionService} 里 required 报错的取法）。 */
    private static String fieldLabel(JsonNode field) {
        if (field == null) return "";
        return field.path("label").asText(field.path("id").asText(""));
    }

    Set<String> requiredColumns(JsonNode source) {
        Set<String> columns = new LinkedHashSet<>(List.of(text(source, "valueColumn"), text(source, "labelColumn")));
        JsonNode cascade = source.path("cascade");
        if ("columns".equals(cascade.path("kind").asText())) cascade.path("levelColumns").forEach(c -> columns.add(c.asText()));
        if ("split".equals(cascade.path("kind").asText())) columns.add(text(cascade, "sourceColumn"));
        if (source.path("dependency").isObject()) columns.add(text(source.path("dependency"), "matchColumn"));
        return columns;
    }

    List<Map<String, String>> rows(JsonNode source, Map<String, String> filters) {
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

    private JsonNode parse(String raw) { try { return json.readTree(raw == null ? "{}" : raw); } catch (Exception e) { throw new IllegalStateException("invalid stored schema", e); } }
}
