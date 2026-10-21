package com.antflow.attachment;

import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class MediaSubmissionValidator {
    /** 预热最多检查的文件数：请求里正常只有个位数媒体字段，上限用于防放大。 */
    private static final int PRELOAD_MAX_FILES = 50;
    private final JdbcTemplate jdbc;
    private final MobileFileMapper files;
    private final FileStorage storage;
    private final MediaDurationProbe probe;
    private final ObjectMapper json;
    private final MobileMediaProperties properties;

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

    /**
     * 提交前的**事务外**预热：把请求里出现的、还没有时长的音视频文件先探测并回写
     * `t_mobile_file.duration_seconds`。
     *
     * <p>为什么需要（见 code-review A5）：{@link #validate} 是懒探测——缺时长就现场拉 MinIO
     * 再跑 ffprobe（最长 15s），而它被调用时调用方正持着实例/任务的行锁。把这一步提前到
     * controller（无事务）里做，绝大多数请求进事务时已经命中缓存，锁只持毫秒级。
     *
     * <p>三个约束：
     * <ul>
     *   <li><b>尽力而为</b>：任何异常都只记日志，不改变错误语义——真正的校验仍由
     *       {@link #validate} 在事务内完成（探测失败它会抛同样的 MEDIA_DURATION_UNAVAILABLE）；</li>
     *   <li><b>不依赖 schema</b>：从 data 里收集 UUID 形态的 id，再用 content_type 过滤，
     *       避免为了预热去加载流程快照；</li>
     *   <li><b>有上限</b>：最多看 {@link #PRELOAD_MAX_FILES} 个候选，防止恶意超大数据包放大 IO。</li>
     * </ul>
     */
    public void preloadMediaDurations(Object data) {
        if (data == null) return;
        try {
            preloadNode(json.valueToTree(data));
        } catch (RuntimeException error) {
            log.warn("media duration preload skipped: {}", error.toString());
        }
    }

    /**
     * 重提场景的预热：请求体里没有 data（只改状态、沿用上一版填报）时，从实例当前修订反查数据。
     *
     * <p>只查"当前修订"而不是 t_form_data：重提校验用的就是 engine 里的这一版快照，
     * 拿最新提交值预热才和事务内的校验对象一致。实例或修订不存在时静默返回——
     * 真正的错误（无权限、任务不存在）由 service 在事务里报，预热不该抢先改变语义。
     */
    public void preloadForInstance(long instanceId) {
        try {
            String data = jdbc.query("""
                SELECT revision.data::text
                FROM t_process_instance instance
                JOIN t_form_data_revision revision ON revision.id = instance.current_form_revision_id
                WHERE instance.id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, instanceId);
            if (data == null || data.isBlank()) return;
            preloadNode(json.readTree(data));
        } catch (IOException | RuntimeException error) {
            log.warn("media duration preload skipped for instance {}: {}", instanceId, error.toString());
        }
    }

    /**
     * 只有 taskId 时的入口：控制器拿不到实例 id，又不该为此注入 TaskMapper（分层）。
     * 两次主键查询换来"重提无 payload 也能预热"，代价可忽略。
     */
    public void preloadForTask(long taskId) {
        try {
            Long instanceId = jdbc.query("SELECT proc_inst_id FROM t_task WHERE id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, taskId);
            if (instanceId != null) preloadForInstance(instanceId);
        } catch (RuntimeException error) {
            log.warn("media duration preload skipped for task {}: {}", taskId, error.toString());
        }
    }

    private void preloadNode(JsonNode root) {
        java.util.LinkedHashSet<UUID> candidates = new java.util.LinkedHashSet<>();
        collectIds(root, candidates);
        if (candidates.isEmpty()) return;
        // 过滤条件全部下推到 SQL（只取缺时长的音视频），不把整批文件捞回来再判断。
        for (MobileFile file : files.selectMissingDuration(java.util.List.copyOf(candidates))) {
            try {
                duration(file);
            } catch (BizException ignored) {
                // 预热失败留给事务内的 validate 报错，这里不吞掉语义、只跳过
            }
        }
    }

    private void collectIds(JsonNode node, java.util.Set<UUID> collected) {
        if (node == null || collected.size() >= PRELOAD_MAX_FILES) return;
        if (node.isArray()) {
            node.forEach(child -> collectIds(child, collected));
            return;
        }
        if (!node.isObject()) return;
        JsonNode id = node.path("id");
        if (id.isTextual()) {
            try {
                collected.add(UUID.fromString(id.asText()));
            } catch (IllegalArgumentException ignored) {
                // 不是文件引用（普通字段 id、业务编号等），跳过
            }
        }
        node.forEach(child -> collectIds(child, collected));
    }

    private double duration(MobileFile file) {
        if (file.getDurationSeconds() != null) {
            if (!Double.isFinite(file.getDurationSeconds()) || file.getDurationSeconds() < 0) {
                throw new BizException("MEDIA_DURATION_UNAVAILABLE", "媒体时长无效，请重新上传");
            }
            return file.getDurationSeconds();
        }
        // 走到这里说明入口预热没命中：要么调用方没接 preload，要么文件是刚上传还没预热过。
        // 合法但要警惕——它会在持锁事务里拉流 + ffprobe（A5）。日志用于观察漏接的入口。
        log.warn("media duration probed inside transaction: file={} key={}",
            file.getId(), file.getStorageKey());
        Path temporary = null;
        try {
            temporary = Files.createTempFile("antflow-media-check-", ".bin");
            try (InputStream input = storage.get(file.getStorageKey()).getInputStream()) {
                copyBounded(input, temporary);
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

    /**
     * 有上限的流式落盘：{@code Files.copy} 不限大小，一个几百 MB 的视频就能占满临时盘并长时间
     * 占着校验线程（这段代码在实例行锁里跑）。超限立刻中止并删除临时文件（由调用方 finally 负责），
     * fail-closed——宁可拒绝提交，也不让校验拖着不返回。
     */
    private void copyBounded(InputStream input, Path target) throws IOException {
        long limit = properties.getMaxProbeBytes();
        byte[] buffer = new byte[8192];
        long total = 0;
        try (OutputStream output = Files.newOutputStream(target)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > limit) throw new BizException("MEDIA_DURATION_UNAVAILABLE",
                    "媒体文件过大，无法验证时长，请压缩后重新上传");
                output.write(buffer, 0, read);
            }
        }
    }

    private record Context(String schema, String data) { }
}
