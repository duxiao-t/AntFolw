package com.antflow.form.options;

import static com.antflow.form.options.OptionSchemaSupport.bad;

import com.antflow.authz.AuthorizationService;
import com.antflow.authz.HiddenResourceException;
import com.antflow.engine.BizException;
import com.antflow.engine.tree.ProcessTreeNav;
import com.antflow.process.DefinitionVersionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 查询入口的 schema 解析：把「表单 / 实例 / 填报记录」三种入口解析成同一份字段定义，并做节点级可见性判定。
 *
 * <p>从 {@link OptionRuntimeService} 搬出（2026-10-22 类体积治理第一刀）。单独成类是因为它只
 * **读**（不写、不校验值），且三条入口的版本口径必须锁在一起，见 {@link #schema} 里的注释。
 *
 * <p><b>两条不能动的语义：</b>
 * <ol>
 *   <li>{@code formCode} 入口要求客户端带上 {@code formVersion} 并与之相等，否则抛
 *       {@code FORM_VERSION_CONFLICT}——这是"表单已更新，请刷新"的唯一来源。</li>
 *   <li>{@code dataId} 入口必须在解析后再查一次"这条数据是否属于某个实例"，否则可以拿 dataId
 *       绕开 instanceId 路径的 HIDDEN 检查（两条路径返回的是同一批选项）。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
class OptionSchemaResolver {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuthorizationService authorization;
    private final DefinitionVersionRepository versions;

    JsonNode schema(OptionRuntimeService.OptionQuery request) {
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
        jdbc.query("""
            SELECT form_def_id, created_by, company_id FROM t_form_data
            WHERE id = ? AND status = 'DRAFT'
            """, rs -> {
                if (!rs.next()) return null;
                if (rs.getLong("created_by") != user) {
                    throw new HiddenResourceException("draft not found");
                }
                authorization.requireFormUse(rs.getLong("form_def_id"));
                return rs.getLong("form_def_id");
            }, request.dataId());
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

    private JsonNode parse(String raw) { try { return json.readTree(raw == null ? "{}" : raw); } catch (Exception e) { throw new IllegalStateException("invalid stored schema", e); } }

    private record InstanceSchema(JsonNode schema, JsonNode process, long owner) { }
    /** 按 dataId 反查到的实例可见性上下文。 */
    private record AuthorizedData(long instanceId, JsonNode process, long owner) { }
}
