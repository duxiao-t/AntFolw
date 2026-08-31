package com.antflow.process;

import com.antflow.engine.BizException;
import com.antflow.form.FormDefinition;
import com.antflow.form.runtime.FormData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Small JDBC boundary for immutable published definitions and form revisions. */
@Repository
@RequiredArgsConstructor
public class DefinitionVersionRepository {
    private final JdbcTemplate jdbc;

    public long publishForm(FormDefinition form) {
        Long id = jdbc.query("""
            INSERT INTO t_form_definition_version(
                form_definition_id, version_no, schema, settings, checksum,
                published_by, published_at)
            VALUES (?, ?, ?::jsonb, ?::jsonb,
                    encode(digest((?::jsonb::text || ?::jsonb::text)::bytea, 'sha256'), 'hex'),
                    ?, now())
            ON CONFLICT (form_definition_id, version_no) DO NOTHING
            RETURNING id
            """, rs -> rs.next() ? rs.getLong(1) : null,
            form.getId(), form.getVersion(), form.getSchema(), form.getSettings(),
            form.getSchema(), form.getSettings(), form.getCreatedBy());
        if (id != null) return id;
        return requiredLong("""
            SELECT id FROM t_form_definition_version
            WHERE form_definition_id = ? AND version_no = ?
            """, form.getId(), form.getVersion());
    }

    public long publishProcess(ProcessDefinition process, FormDefinition form) {
        long formVersionId = publishForm(form);
        Long id = jdbc.query("""
            INSERT INTO t_process_definition_version(
                process_definition_id, form_definition_version_id, version_no,
                process, settings, checksum, published_by, published_at)
            VALUES (?, ?, ?, ?::jsonb, '{}'::jsonb,
                    encode(digest(?::jsonb::text::bytea, 'sha256'), 'hex'), ?, now())
            ON CONFLICT (process_definition_id, version_no, checksum) DO NOTHING
            RETURNING id
            """, rs -> rs.next() ? rs.getLong(1) : null,
            process.getId(), formVersionId, process.getVersion(), process.getProcess(),
            process.getProcess(), process.getCreatedBy());
        if (id != null) return id;
        return requiredLong("""
            SELECT id FROM t_process_definition_version
            WHERE process_definition_id = ? AND version_no = ?
              AND checksum = encode(digest(?::jsonb::text::bytea, 'sha256'), 'hex')
            """, process.getId(), process.getVersion(), process.getProcess());
    }

    public FormDefinition runtimeFormByCode(String code) {
        return jdbc.query("""
            SELECT form.id, form.code, form.name, form.description,
                   version.version_no, version.schema::text, version.settings::text,
                   form.created_by, form.created_at, form.updated_at
            FROM t_form_definition form
            JOIN LATERAL (
                SELECT * FROM t_form_definition_version candidate
                WHERE candidate.form_definition_id = form.id
                ORDER BY candidate.version_no DESC, candidate.id DESC LIMIT 1
            ) version ON true
            WHERE form.code = ? AND form.deleted = 0 AND form.status <> 'DEPRECATED'
            """, rs -> rs.next() ? form(rs) : null, code);
    }

    public ProcessDefinition runtimeProcessForForm(long formDefinitionId) {
        return jdbc.query("""
            SELECT process.id, process.form_def_id, version.version_no,
                   version.process::text, process.created_by, process.created_at
            FROM t_process_definition process
            JOIN LATERAL (
                SELECT * FROM t_process_definition_version candidate
                WHERE candidate.process_definition_id = process.id
                ORDER BY candidate.version_no DESC, candidate.id DESC LIMIT 1
            ) version ON true
            WHERE process.form_def_id = ?
            """, rs -> rs.next() ? process(rs) : null, formDefinitionId);
    }

    public long processVersionId(long processDefinitionId, int versionNo) {
        return requiredLong("""
            SELECT id FROM t_process_definition_version
            WHERE process_definition_id = ? AND version_no = ?
            ORDER BY legacy_approximate, id DESC LIMIT 1
            """, processDefinitionId, versionNo);
    }

    public long formVersionId(long formDefinitionId, int versionNo) {
        return requiredLong("""
            SELECT id FROM t_form_definition_version
            WHERE form_definition_id = ? AND version_no = ?
            """, formDefinitionId, versionNo);
    }

    public long createRevision(FormData data, String status, String reason, long actorId) {
        long formVersionId = formVersionId(data.getFormDefId(), data.getFormDefVersion());
        Long id = jdbc.query("""
            INSERT INTO t_form_data_revision(
                form_data_id, revision_no, form_definition_version_id, data,
                status, reason, checksum, created_by)
            SELECT ?, COALESCE(MAX(revision_no), 0) + 1, ?, ?::jsonb, ?, ?,
                   encode(digest(?::jsonb::text::bytea, 'sha256'), 'hex'), ?
            FROM t_form_data_revision WHERE form_data_id = ?
            RETURNING id
            """, rs -> rs.next() ? rs.getLong(1) : null,
            data.getId(), formVersionId, data.getData(), status, reason,
            data.getData(), actorId, data.getId());
        if (id == null) throw new BizException("REVISION_FAILED", "form revision was not created");
        syncRevisionFiles(id, data.getId());
        jdbc.update("UPDATE t_form_data SET current_revision_id = ? WHERE id = ?", id, data.getId());
        data.setCurrentRevisionId(id);
        return id;
    }

    public String revisionData(long revisionId) {
        return jdbc.query("SELECT data::text FROM t_form_data_revision WHERE id = ?",
            rs -> rs.next() ? rs.getString(1) : null, revisionId);
    }

    public String revisionSchema(long revisionId) {
        return jdbc.query("""
            SELECT version.schema::text
            FROM t_form_data_revision revision
            JOIN t_form_definition_version version
              ON version.id = revision.form_definition_version_id
            WHERE revision.id = ?
            """, rs -> rs.next() ? rs.getString(1) : null, revisionId);
    }

    public void syncRevisionFiles(long revisionId, long formDataId) {
        jdbc.update("""
            INSERT INTO t_form_data_revision_file(revision_id, file_id, field_id, sort_order)
            SELECT ?, file_id, field_id, sort_order FROM t_form_data_file
            WHERE form_data_id = ? ON CONFLICT DO NOTHING
            """, revisionId, formDataId);
    }

    public List<FormRevisionView> revisions(long instanceId) {
        return jdbc.query("""
            SELECT revision.id, revision.revision_no, revision.status, revision.reason,
                   revision.data::text, revision.created_by, revision.created_at
            FROM t_process_instance instance
            JOIN t_form_data_revision revision
              ON revision.form_data_id = instance.form_data_id
            WHERE instance.id = ? ORDER BY revision.revision_no
            """, (rs, rowNum) -> new FormRevisionView(rs.getLong("id"),
                rs.getInt("revision_no"), rs.getString("status"), rs.getString("reason"),
                rs.getString("data"), nullableLong(rs, "created_by"),
                rs.getObject("created_at", OffsetDateTime.class)), instanceId);
    }

    public List<NodeInstanceView> nodeInstances(long instanceId) {
        return jdbc.query("""
            SELECT id, node_id, node_type, round_no, attempt_no,
                   gateway_node_instance_id, branch_id, status,
                   form_revision_id_at_enter, started_at, completed_at
            FROM t_process_node_instance WHERE proc_inst_id = ?
            ORDER BY round_no, started_at, id
            """, (rs, rowNum) -> new NodeInstanceView(rs.getLong("id"),
                rs.getString("node_id"), rs.getString("node_type"), rs.getInt("round_no"),
                rs.getInt("attempt_no"), nullableLong(rs, "gateway_node_instance_id"),
                rs.getString("branch_id"), rs.getString("status"),
                nullableLong(rs, "form_revision_id_at_enter"),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class)), instanceId);
    }

    private long requiredLong(String sql, Object... args) {
        Long value = jdbc.query(sql, rs -> rs.next() ? rs.getLong(1) : null, args);
        if (value == null) throw new BizException("VERSION_NOT_FOUND", "published definition version not found");
        return value;
    }

    private static FormDefinition form(ResultSet rs) throws SQLException {
        FormDefinition value = new FormDefinition();
        value.setId(rs.getLong("id"));
        value.setCode(rs.getString("code"));
        value.setName(rs.getString("name"));
        value.setDescription(rs.getString("description"));
        value.setVersion(rs.getInt("version_no"));
        value.setSchema(rs.getString("schema"));
        value.setSettings(rs.getString("settings"));
        value.setStatus("PUBLISHED");
        value.setCreatedBy(nullableLong(rs, "created_by"));
        value.setCreatedAt(rs.getObject("created_at", OffsetDateTime.class));
        value.setUpdatedAt(rs.getObject("updated_at", OffsetDateTime.class));
        value.setDeleted(0);
        return value;
    }

    private static ProcessDefinition process(ResultSet rs) throws SQLException {
        ProcessDefinition value = new ProcessDefinition();
        value.setId(rs.getLong("id"));
        value.setFormDefId(rs.getLong("form_def_id"));
        value.setVersion(rs.getInt("version_no"));
        value.setProcess(rs.getString("process"));
        value.setStatus("PUBLISHED");
        value.setCreatedBy(nullableLong(rs, "created_by"));
        value.setCreatedAt(rs.getObject("created_at", OffsetDateTime.class));
        return value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public record FormRevisionView(long id, int revisionNo, String status, String reason,
                                   String data, Long createdBy, OffsetDateTime createdAt) { }
    public record NodeInstanceView(long id, String nodeId, String nodeType, int roundNo,
                                   int attemptNo, Long gatewayNodeInstanceId, String branchId,
                                   String status, Long formRevisionIdAtEnter,
                                   OffsetDateTime startedAt, OffsetDateTime completedAt) { }
}
