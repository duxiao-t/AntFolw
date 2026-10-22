package com.antflow.form.options;

import static com.antflow.form.options.OptionSchemaSupport.flatten;
import static com.antflow.form.options.OptionSchemaSupport.isBound;

import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 引用统计：新增的「字段 + 数据源」绑定对，必须在数据源侧的「可引用表单」清单里。
 *
 * <p>从 {@link OptionRuntimeService} 搬出（2026-10-22 类体积治理第一刀）。单独成类是因为它的
 * 触发因素与运行时解析完全不同：它只在**保存/发布**时跑一次，读的是 {@code t_form_option_source}。
 *
 * <p><b>口径三连（改之前必读）：</b>
 * <ol>
 *   <li>比的是**差集**，不是"新 schema 里每个绑定都要有引用行"：引用可以被撤销，撤销的语义是
 *       "只影响新绑定，已经绑着它的表单不受影响"。拿全量去卡，会让"引用被撤销但字段仍绑定"的
 *       表单再也保存/发布不了。</li>
 *   <li>差集的单位是**绑定对**（{@code 字段 id + \0 + sourceId}），不是单独的 sourceId：
 *       否则表单原本只有字段 A 绑着 S、S 的引用被撤销时，新加字段 B 也绑 S 会被当成"旧绑定"放行。</li>
 *   <li>失败抛 {@code OPTION_SOURCE_NOT_REFERENCED}（含 sourceId 与恢复指引），错误码是前端文案的锚点。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
class OptionReferenceGuard {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    /**
     * 新增绑定必须在源侧的「可引用表单」清单（{@code t_form_option_source}）里。
     *
     * @param previousSchemaJson 这张表单**改动前**的 schema（新建表单传 null）
     * @param newSchemaJson      本次要写入的 schema
     */
    void requireNewBindingsAreReferenced(long formId, String previousSchemaJson,
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

    private JsonNode parse(String raw) { try { return json.readTree(raw == null ? "{}" : raw); } catch (Exception e) { throw new IllegalStateException("invalid stored schema", e); } }
}
