package com.antflow.form.correction;

import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 决定「哪些字段允许事后修正」。
 *
 * <p>为什么需要它：修正改的是**已经生效**的业务数据。有些字段看着普通，改了却会自相矛盾：
 * <ul>
 *   <li><b>参与条件分支判定的字段</b>——它决定了这份单据当初走了哪条审批路径。
 *       改掉之后，"为什么当时走了 A 分支"就无从解释，历史审批记录与数据对不上。</li>
 *   <li><b>被单号规则引用的字段</b>——业务单号由这些字段拼出来（{@code BUSINESS_NUMBER} 的
 *       FIELD 部件），而单号在提交时就固定了、不允许变。改了字段就会出现
 *       "单号里写着 2024，内容是 2025"这种自相矛盾。</li>
 * </ul>
 *
 * <p>因此这里产出一份白名单（而不是黑名单）：调用方只接受白名单内的字段，
 * 其余一律拒绝。默认拒绝的语义比默认放行更安全——将来新增了某类"会参与判定"的字段，
 * 忘记登记也只会变成"暂不支持修正"，不会变成"可以随意改"。
 *
 * <p>fail-closed：配置解析失败时抛 {@link BizException}，而不是退回"全部允许"。
 * 宁可这一次修正失败，也不能在无法判断风险的情况下改写已生效数据。
 */
@Component
@RequiredArgsConstructor
public class CorrectionFieldPolicy {

    /** 阻塞原因，回给前端用于解释"为什么这个字段不能改"。 */
    public static final String REASON_CONDITION = "CONDITION_FIELD";
    public static final String REASON_BUSINESS_NUMBER = "BUSINESS_NUMBER_FIELD";

    /** @param allowed 可修正字段 id；@param blocked 不可修正字段 id → 原因码 */
    public record Policy(Set<String> allowed, Map<String, String> blocked) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    /**
     * 计算某张表单在指定版本 schema 下的可修正字段。
     *
     * @param formDefId   表单 id（用于取流程定义里的条件字段）
     * @param settingsJson 表单 settings 文本（取单号规则）；可为空
     * @param schemaJson  **该单据提交时那一版**的 schema 文本，不是表单当前 schema
     */
    public Policy compute(long formDefId, String settingsJson, String schemaJson) {
        Set<String> allFields = schemaFieldIds(schemaJson);
        Map<String, String> blocked = new LinkedHashMap<>();

        // 条件分支引用的字段：从流程定义里静态抽取。
        // 用"当前发布的流程"而不是单据当时那一版，是刻意偏保守——
        // 判定规则只会在升级后变多，按最新的挡能覆盖将来可能承载判定的字段。
        for (String fieldId : conditionFieldIds(formDefId)) {
            blocked.putIfAbsent(fieldId, REASON_CONDITION);
        }
        // 单号规则引用的字段
        for (String fieldId : businessNumberFieldIds(settingsJson)) {
            blocked.putIfAbsent(fieldId, REASON_BUSINESS_NUMBER);
        }
        // schema 里不存在的 id 不进 allowed（会自动被拒），但也不报 blocked 原因，
        // 因为那属于"请求里带了不存在的字段"，由上层按非法输入处理。
        blocked.keySet().retainAll(allFields);

        Set<String> allowed = new LinkedHashSet<>(allFields);
        allowed.removeAll(blocked.keySet());
        return new Policy(allowed, blocked);
    }

    /** schema 是字段节点数组，每项有 id；解析失败即 fail-closed。 */
    private Set<String> schemaFieldIds(String schemaJson) {
        Set<String> ids = new LinkedHashSet<>();
        JsonNode root = read(schemaJson, "CORRECTION_SCHEMA_INVALID");
        if (root == null || !root.isArray()) {
            throw new BizException("CORRECTION_SCHEMA_INVALID", "表单 schema 不是字段数组");
        }
        for (JsonNode node : root) {
            String id = node.path("id").asText("");
            if (!id.isEmpty()) ids.add(id);
        }
        return ids;
    }

    /**
     * 单号规则里 {@code type=FIELD} 的部件引用的字段。
     * 规则形状见 {@code BusinessNumberService}：settings.businessNumber.parts[]。
     * 没有配置单号规则（或 parts 为空）时返回空集合，不是错误。
     */
    private Set<String> businessNumberFieldIds(String settingsJson) {
        Set<String> ids = new LinkedHashSet<>();
        if (settingsJson == null || settingsJson.isBlank()) return ids;
        JsonNode settings = read(settingsJson, "CORRECTION_SETTINGS_INVALID");
        JsonNode parts = settings == null ? null : settings.path("businessNumber").path("parts");
        if (parts == null || !parts.isArray()) return ids;
        for (JsonNode part : parts) {
            if (!"FIELD".equals(part.path("type").asText())) continue;
            String fieldId = part.path("fieldId").asText("");
            if (!fieldId.isEmpty()) ids.add(fieldId);
        }
        return ids;
    }

    /**
     * 从最新流程定义里抽取所有参与条件判定的字段。
     *
     * <p>流程是**树形**结构（节点在 {@code children} 下递归），条件形如
     * {@code props.groups[].conditions[].field}。这里用"遇到 conditions 数组就收集 field"
     * 的通用遍历，而不是钉死层级：条件结构的层级将来可能调整，钉死路径会在升级后静默漏抽取。
     * 漏抽取 = 该挡的没挡住，所以宁可写得宽一点、多挡几个。
     *
     * <p>表单没有流程定义（如导入数据）时返回空集合；流程 JSON 解析失败则 fail-closed。
     */
    private Set<String> conditionFieldIds(long formDefId) {
        Set<String> ids = new LinkedHashSet<>();
        String processJson = jdbc.query("""
            SELECT version.process::text
            FROM t_process_definition process
            JOIN LATERAL (
                SELECT * FROM t_process_definition_version candidate
                WHERE candidate.process_definition_id = process.id
                ORDER BY candidate.version_no DESC, candidate.id DESC LIMIT 1
            ) version ON true
            WHERE process.form_def_id = ?
            """, rs -> rs.next() ? rs.getString(1) : null, formDefId);
        if (processJson == null || processJson.isBlank()) return ids;
        collectConditionFields(read(processJson, "CORRECTION_PROCESS_INVALID"), ids);
        return ids;
    }

    private void collectConditionFields(JsonNode node, Set<String> sink) {
        if (node == null) return;
        if (node.isObject()) {
            JsonNode conditions = node.get("conditions");
            if (conditions != null && conditions.isArray()) {
                for (JsonNode condition : conditions) {
                    String field = condition.path("field").asText("");
                    if (!field.isEmpty()) sink.add(field);
                }
            }
            node.forEach(child -> collectConditionFields(child, sink));
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectConditionFields(child, sink));
        }
    }

    private JsonNode read(String text, String errorCode) {
        try {
            return json.readTree(text);
        } catch (Exception ex) {
            throw new BizException(errorCode, "无法解析表单/流程配置，已拒绝修正");
        }
    }
}
