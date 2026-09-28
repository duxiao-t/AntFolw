package com.antflow.authz;

import java.util.List;
import java.util.Set;

/**
 * 行级权限的映射规则：**哪张表、哪个查询、按能力范围或资源成员关系过滤**。
 *
 * <p>刻意做成"按 mapper 语句显式开启"而不是"某张表一律过滤"：同一张表在不同业务入口
 * 的可见性语义并不相同（例如 t_process_instance 的可见性 = 参与者 ∪ 数据范围，
 * 而移动端自取列表只看本人），一律过滤会误伤自助场景。
 *
 * <p>新增受控查询只需在 {@link #RULES} 加一条：
 * <ul>
 *   <li>{@code statements}：受控的 mapper 语句 id（MyBatis 的 mappedStatementId）；</li>
 *   <li>{@code dataScoped}：是否应用能力的数据范围；</li>
 *   <li>{@code ownerColumn}：SELF 范围的归属列（在本表上）；</li>
 *   <li>{@code departmentExpression}：部门范围的 SQL 片段模板，{alias} 与 {departments}
 *       由处理器替换；为空表示该表无法表达部门范围（只支持 SELF/ALL）。</li>
 * </ul>
 */
public final class DataPermissionRules {

    public record Rule(String table, String capability, boolean dataScoped, Set<String> statements,
                       String ownerColumn, String departmentExpression,
                       String requiredCondition) {

        boolean appliesTo(String tableName, String mappedStatementId) {
            return table.equalsIgnoreCase(tableName)
                && statements.stream().anyMatch(mappedStatementId::startsWith);
        }
    }

    /** 表单数据列表：部门归属经实例快照（t_process_instance.started_dept_id）。 */
    public static final Rule FORM_DATA = new Rule(
        "t_form_data",
        PermissionCodes.FORM_DATA_READ,
        true,
        // MyBatis-Plus 的 selectPage 内部执行的是 selectList（本人提交已用独立方法 +
        // @InterceptorIgnore 排除，见 FormDataMapper.selectMySubmissions）
        Set.of("com.antflow.form.runtime.FormDataMapper.selectList"),
        "created_by",
        "(EXISTS (SELECT 1 FROM t_process_instance scope_pi"
            + " WHERE scope_pi.form_data_id = {alias}.id"
            + " AND scope_pi.started_dept_id IN ({departments}))"
            + " OR (NOT EXISTS (SELECT 1 FROM t_process_instance scope_pi_known"
            + " WHERE scope_pi_known.form_data_id = {alias}.id"
            + " AND scope_pi_known.started_dept_id IS NOT NULL)"
            + " AND EXISTS (SELECT 1 FROM t_user scope_submitter"
            + " WHERE scope_submitter.id = {alias}.created_by"
            + " AND scope_submitter.dept_id IN ({departments}))))",
        null
    );

    /**
     * 表单定义列表：原子能力由端点与拦截器校验，行级只认指定维护人。
     */
    public static final Rule FORM_DEFINITION = new Rule(
        "t_form_definition",
        PermissionCodes.FORM_DEFINITION_READ,
        false,
        Set.of("com.antflow.form.FormDefinitionMapper.selectSummaryPage"),
        null,
        null,
        "EXISTS (SELECT 1 FROM t_form_maintainer maintainer"
            + " WHERE maintainer.form_def_id = {alias}.id"
            + " AND maintainer.user_id = {userId})"
    );

    public static final List<Rule> RULES = List.of(FORM_DATA, FORM_DEFINITION);

    public static Rule match(String tableName, String mappedStatementId) {
        if (tableName == null || mappedStatementId == null) {
            return null;
        }
        for (Rule rule : RULES) {
            if (rule.appliesTo(tableName, mappedStatementId)) {
                return rule;
            }
        }
        return null;
    }

    private DataPermissionRules() {
    }
}
