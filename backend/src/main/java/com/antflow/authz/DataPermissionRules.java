package com.antflow.authz;

import java.util.List;
import java.util.Set;

/**
 * 行级数据权限的映射规则：**哪张表、哪个查询、按哪个能力与归属列过滤**。
 *
 * <p>刻意做成"按 mapper 语句显式开启"而不是"某张表一律过滤"：同一张表在不同业务入口
 * 的可见性语义并不相同（例如 t_process_instance 的可见性 = 参与者 ∪ 数据范围，
 * 而移动端自取列表只看本人），一律过滤会误伤自助场景。
 *
 * <p>新增受控查询只需在 {@link #RULES} 加一条：
 * <ul>
 *   <li>{@code statements}：受控的 mapper 语句 id（MyBatis 的 mappedStatementId）；</li>
 *   <li>{@code ownerColumn}：SELF 范围的归属列（在本表上）；</li>
 *   <li>{@code departmentExpression}：部门范围的 SQL 片段模板，{alias} 与 {departments}
 *       由处理器替换；为空表示该表无法表达部门范围（只支持 SELF/ALL）。</li>
 * </ul>
 */
public final class DataPermissionRules {

    public record Rule(String table, String capability, Set<String> statements,
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
        // 表单数据：既要"该表单的使用授权"，又要落在数据范围内（与 canReadFormData 一致）
        "EXISTS (SELECT 1 FROM t_form_resource_grant scope_grant"
            + " WHERE scope_grant.form_def_id = {alias}.form_def_id"
            + " AND ((scope_grant.subject_type = 'USER' AND scope_grant.subject_id = {userId})"
            + " OR (scope_grant.subject_type = 'ROLE' AND scope_grant.subject_id IN ("
            + "   SELECT scope_ur.role_id FROM t_user_role scope_ur"
            + "   JOIN t_role scope_role ON scope_role.id = scope_ur.role_id"
            + "     AND scope_role.enabled = true"
            + "   WHERE scope_ur.user_id = {userId}))"
            + " OR (scope_grant.subject_type = 'DEPARTMENT' AND EXISTS ("
            + "   SELECT 1 FROM t_user scope_viewer"
            + "   JOIN t_department scope_viewer_dept ON scope_viewer_dept.id = scope_viewer.dept_id"
            + "   JOIN t_department scope_grant_dept ON scope_grant_dept.id = scope_grant.subject_id"
            + "   WHERE scope_viewer.id = {userId}"
            + "     AND scope_viewer_dept.path <@ scope_grant_dept.path))))"
    );

    /**
     * 表单定义列表：表单授权与能力数据范围必须同时满足。
     */
    public static final Rule FORM_DEFINITION = new Rule(
        "t_form_definition",
        PermissionCodes.FORM_DEFINITION_READ,
        Set.of("com.antflow.form.FormDefinitionMapper.selectSummaryPage"),
        "created_by",
        "EXISTS (SELECT 1 FROM t_user scope_owner"
            + " JOIN t_department scope_dept ON scope_dept.id = scope_owner.dept_id"
            + " WHERE scope_owner.id = {alias}.created_by"
            + " AND scope_dept.id IN ({departments}))",
        "EXISTS (SELECT 1 FROM t_form_resource_grant scope_grant"
            + " WHERE scope_grant.form_def_id = {alias}.id"
            + " AND ((scope_grant.subject_type = 'USER' AND scope_grant.subject_id = {userId})"
            + " OR (scope_grant.subject_type = 'ROLE' AND scope_grant.subject_id IN ("
            + "   SELECT scope_ur.role_id FROM t_user_role scope_ur"
            + "   JOIN t_role scope_role ON scope_role.id = scope_ur.role_id"
            + "     AND scope_role.enabled = true"
            + "   WHERE scope_ur.user_id = {userId}))"
            + " OR (scope_grant.subject_type = 'DEPARTMENT' AND EXISTS ("
            + "   SELECT 1 FROM t_user scope_viewer"
            + "   JOIN t_department scope_viewer_dept ON scope_viewer_dept.id = scope_viewer.dept_id"
            + "   JOIN t_department scope_grant_dept ON scope_grant_dept.id = scope_grant.subject_id"
            + "   WHERE scope_viewer.id = {userId}"
            + "     AND scope_viewer_dept.path <@ scope_grant_dept.path))))"
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
