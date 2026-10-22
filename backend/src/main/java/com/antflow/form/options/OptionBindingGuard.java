package com.antflow.form.options;

import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 选项源的**引用关系**查询：这个数据源/版本被哪些表单绑着、能不能删。
 *
 * <p>为什么从 {@link OptionSourceService} 拆出来：它读的是表单侧的表
 * （{@code t_form_definition}、{@code t_form_definition_version}、{@code t_form_option_source}），
 * 与配置侧的写入（{@code t_option_data_source*}）不是同一份数据所有权。混在一起时，
 * "改表单 schema 结构"和"改数据源配置"这两类需求都会动同一个千行文件。
 *
 * <p>**只读**：这里没有任何写操作。不要为了方便把"删数据源"或"解绑"写进来——
 * 写入属于 {@link OptionSourceService}，它持有事务边界。
 *
 * <p>两套"引用"不要混淆：
 * <ul>
 *   <li>{@link #bindingFormCount} —— schema 里**真的绑着**（草稿 + 已发布快照），删数据源只认这个；</li>
 *   <li>{@link #referencedForms} —— {@code t_form_option_source} 那份可在源侧撤销的引用清单，撤引用不解绑字段。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
class OptionBindingGuard {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    /**
     * 每个版本分别被哪些表单的字段绑着。表单在**发布那一刻**把 versionId 钉进快照，之后不重新
     * 发布那张表单就一直用旧版——所以同一个源的各个版本，在用表单可以是完全不同的两批。
     */
    List<OptionSourceService.VersionUsage> versionUsage(long sourceId) {
        Map<Long, List<OptionSourceService.FormRef>> byVersion = new LinkedHashMap<>();
        jdbc.query("""
            WITH binding AS (
              SELECT DISTINCT form.id AS form_id, form.code AS form_code, form.name AS form_name,
                     (opt->>'versionId')::bigint AS version_id
              FROM (
                SELECT id AS form_def_id, schema FROM t_form_definition WHERE deleted = 0
                UNION ALL
                SELECT form_definition_id, schema FROM t_form_definition_version
              ) s
              JOIN t_form_definition form ON form.id = s.form_def_id
              CROSS JOIN LATERAL jsonb_path_query(s.schema, '$.**.props.optionSource') opt
              WHERE opt->>'sourceId' ~ '^[0-9]+$' AND opt->>'versionId' ~ '^[0-9]+$'
                AND (opt->>'sourceId')::bigint = ?
            )
            SELECT version_id, form_id, form_code, form_name FROM binding
            ORDER BY form_name, form_id
            """, rs -> {
            long versionId = rs.getLong("version_id");
            byVersion.computeIfAbsent(versionId, key -> new ArrayList<>())
                .add(new OptionSourceService.FormRef(rs.getLong("form_id"), rs.getString("form_code"),
                    rs.getString("form_name")));
        }, sourceId);
        return byVersion.entrySet().stream()
            .map(entry -> new OptionSourceService.VersionUsage(entry.getKey(), entry.getValue())).toList();
    }

    /**
     * 删不掉的原因，按 {@code OptionSourceService#delete} 里闸门的先后顺序给。界面要能把这句话显示出来——
     * 今天不可删就整个不显示「删除」，用户会以为没这个功能。
     */
    static String deleteBlockedReason(long publishedVersions, int boundForms, int referencedForms) {
        if (publishedVersions > 0) {
            return "已发布过 " + publishedVersions + " 个版本，只能停用";
        }
        if (boundForms > 0) {
            // 用「绑定」而不是「引用」：这是 schema 里真的绑着，和下面那份可撤销的引用清单不是一回事。
            return "已被 " + boundForms + " 张表单的字段绑定，不能删除";
        }
        return "已被 " + referencedForms + " 张表单引用，不能删除";
    }

    /** 引用了这个数据源的表单。反向读 V46 的引用表。 */
    List<OptionSourceService.FormRef> referencedForms(long sourceId) {
        return jdbc.query("""
            SELECT form.id, form.code, form.name
            FROM t_form_option_source ref
            JOIN t_form_definition form ON form.id = ref.form_def_id
            WHERE ref.source_id = ? AND form.deleted = 0
            ORDER BY form.name, form.id
            """, (rs, row) -> new OptionSourceService.FormRef(rs.getLong("id"), rs.getString("code"),
            rs.getString("name")), sourceId);
    }

    void requireForms(Set<Long> formIds) {
        formIds.forEach(id -> {
            Long found = jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_form_definition WHERE id = ? AND deleted = 0
                """, Long.class, id);
            if (found == null || found == 0) {
                throw new BizException("OPTION_SOURCE_FORM_INVALID", "表单不存在：" + id);
            }
        });
    }

    /** 有几张表单（草稿或已发布快照）绑定了这个具体版本。 */
    int versionReferencingForms(long versionId) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(DISTINCT form_def_id) FROM (
              SELECT id AS form_def_id, schema FROM t_form_definition WHERE deleted = 0
              UNION ALL
              SELECT form_definition_id, schema FROM t_form_definition_version
            ) form_schema
            WHERE jsonb_path_exists(
              form_schema.schema,
              '$.**.props.optionSource ? (@.versionId == $versionId)',
              jsonb_build_object('versionId', to_jsonb(?::bigint))
            )
            """, Integer.class, versionId);
        return count == null ? 0 : count;
    }

    boolean isReferenced(long sourceId) {
        return bindingFormCount(sourceId) > 0;
    }

    /**
     * 有几张表单在 schema 里真的绑着这个数据源（草稿 + 所有已发布版本快照，含已软删表单的快照）。
     *
     * <p>注意这和 {@code t_form_option_source}（V46 那份可在源侧撤销的引用清单）是两件事：
     * 撤引用**不会**解绑字段。删除判定只认这个数。
     */
    int bindingFormCount(long sourceId) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(DISTINCT form_def_id) FROM (
              SELECT id AS form_def_id, schema FROM t_form_definition WHERE deleted = 0
              UNION ALL
              SELECT form_definition_id, schema FROM t_form_definition_version
            ) form_schema
            WHERE jsonb_path_exists(
              form_schema.schema,
              '$.**.props.optionSource.sourceId ? (@ == $sourceId)',
              jsonb_build_object('sourceId', to_jsonb(?::bigint))
            )
            """, Integer.class, sourceId);
        return count == null ? 0 : count;
    }

    /**
     * 这张表单的草稿 schema 里钉着的所有 optionSource.versionId。
     *
     * <p>用途只有一个：让被停用的版本在「这张表单正用着它」时仍然留在候选里。设计器编辑的是
     * 草稿，所以只扫草稿就够。
     */
    List<Long> boundVersionIds(long formId) {
        String schema = jdbc.query("SELECT schema::text FROM t_form_definition WHERE id = ?",
            rs -> rs.next() ? rs.getString(1) : null, formId);
        if (schema == null) return List.of();
        try {
            List<Long> ids = new ArrayList<>();
            collectVersionIds(json.readTree(schema), ids);
            return ids;
        } catch (Exception error) {
            return List.of();
        }
    }

    /** 只认 props.optionSource 里的 versionId——schema 里别处也可能有同名字段。 */
    private static void collectVersionIds(JsonNode node, List<Long> target) {
        if (node == null) return;
        if (node.isArray()) {
            node.forEach(child -> collectVersionIds(child, target));
            return;
        }
        if (!node.isObject()) return;
        JsonNode optionSource = node.path("optionSource");
        if (optionSource.isObject()) {
            JsonNode versionId = optionSource.path("versionId");
            if (versionId.isIntegralNumber() && versionId.asLong() > 0) target.add(versionId.asLong());
        }
        node.fields().forEachRemaining(entry -> collectVersionIds(entry.getValue(), target));
    }
}
