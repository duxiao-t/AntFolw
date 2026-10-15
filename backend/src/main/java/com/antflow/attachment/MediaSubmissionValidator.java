package com.antflow.attachment;

import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MediaSubmissionValidator {
    private final JdbcTemplate jdbc;
    private final MobileFileMapper files;
    private final FileStorage storage;
    private final MediaDurationProbe probe;
    private final ObjectMapper json;

    public void validate(long dataId) {
        validate(dataId, Map.of());
    }

    public void validate(long dataId, Map<String, String> modes) {
        Context context = jdbc.query("""
            SELECT COALESCE(version.schema, snapshot.schema, form.schema)::text, data.data::text
            FROM t_form_data data JOIN t_form_definition form ON form.id = data.form_def_id
            LEFT JOIN t_process_instance instance ON instance.form_data_id = data.id
            LEFT JOIN t_form_data_revision revision ON revision.id = instance.current_form_revision_id
            LEFT JOIN t_form_definition_version version ON version.id = revision.form_definition_version_id
            LEFT JOIN t_form_definition_version snapshot
              ON snapshot.form_definition_id = data.form_def_id AND snapshot.version_no = data.form_def_version
            WHERE data.id = ? ORDER BY instance.id DESC NULLS LAST LIMIT 1
            """, rs -> rs.next() ? new Context(rs.getString(1), rs.getString(2)) : null, dataId);
        if (context == null) throw new BizException("FORM_DATA_NOT_FOUND", "填报记录不存在");
        try {
            validateNodes(json.readTree(context.schema()), json.readTree(context.data()), modes);
        } catch (IOException exception) {
            throw new BizException("BAD_JSON", "媒体字段无法读取");
        }
    }

    private void validateNodes(JsonNode nodes, JsonNode values, Map<String, String> modes) {
        for (JsonNode node : nodes) {
            if (!"EDITABLE".equals(modes.getOrDefault(node.path("id").asText(), "EDITABLE"))) continue;
            String type = node.path("type").asText();
            JsonNode value = values.path(node.path("id").asText());
            if ("table_list".equals(type)) {
                for (JsonNode row : value) validateNodes(node.path("children"), row, modes);
            } else if (node.path("children").isArray()) {
                validateNodes(node.path("children"), values, modes);
            } else if (("video_upload".equals(type) || "audio_upload".equals(type)) && value.isArray()) {
                double max = node.path("rules").path("maxDuration").asDouble(
                    node.path("props").path("maxDuration").asDouble(60));
                for (JsonNode item : value) {
                    MobileFile file;
                    try {
                        file = files.selectById(UUID.fromString(item.path("id").asText()));
                    } catch (IllegalArgumentException exception) {
                        throw new BizException("BAD_FILE_REF", "媒体文件标识无效");
                    }
                    if (file == null || !"READY".equals(file.getStatus()) || file.getDeletedAt() != null) {
                        throw new BizException("FILE_NOT_FOUND", "媒体文件尚未就绪");
                    }
                    String prefix = "video_upload".equals(type) ? "video/" : "audio/";
                    if (file.getContentType() == null || !file.getContentType().startsWith(prefix)) {
                        throw new BizException("BAD_FILE_REF", "媒体文件类型不匹配");
                    }
                    double seconds = duration(file);
                    if (!Double.isFinite(max) || max < 0 || seconds > max) {
                        throw new BizException("FORM_DATA_INVALID",
                            node.path("label").asText("媒体") + "不能超过" + max + "秒");
                    }
                }
            }
        }
    }

    private double duration(MobileFile file) {
        if (file.getDurationSeconds() != null) {
            if (!Double.isFinite(file.getDurationSeconds()) || file.getDurationSeconds() < 0) {
                throw new BizException("MEDIA_DURATION_UNAVAILABLE", "媒体时长无效，请重新上传");
            }
            return file.getDurationSeconds();
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile("antflow-media-check-", ".bin");
            try (InputStream input = storage.get(file.getStorageKey()).getInputStream()) {
                Files.copy(input, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Double measured = probe.seconds(temporary);
            if (measured == null) throw new BizException("MEDIA_DURATION_UNAVAILABLE",
                "无法验证媒体时长，请重新上传或联系管理员");
            jdbc.update("UPDATE t_mobile_file SET duration_seconds = ? WHERE id = ? AND duration_seconds IS NULL",
                measured, file.getId());
            return measured;
        } catch (IOException exception) {
            throw new BizException("MEDIA_DURATION_UNAVAILABLE", "无法读取媒体时长，请重试");
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    private record Context(String schema, String data) { }
}
