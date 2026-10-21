package com.antflow.attachment;

import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.Map;
import java.nio.file.Path;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MediaSubmissionValidatorTest {
    @Test
    @SuppressWarnings("unchecked")
    void durationLimitUsesStoredMeasurementInsteadOfClientClaims() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MobileFileMapper files = mock(MobileFileMapper.class);
        UUID id = UUID.randomUUID();
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getString(1)).thenReturn("""
            [{"id":"clip","type":"video_upload","props":{"maxDuration":10}}]
            """);
        when(rs.getString(2)).thenReturn("""
            {"clip":[{"id":"%s","durationSeconds":1}]}
            """.formatted(id));
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(11L)))
            .thenAnswer(call -> ((ResultSetExtractor<?>) call.getArgument(1)).extractData(rs));
        MobileFile file = new MobileFile();
        file.setId(id);
        file.setContentType("video/mp4");
        file.setStatus("READY");
        file.setDurationSeconds(20d);
        when(files.selectById(id)).thenReturn(file);
        MediaSubmissionValidator validator = new MediaSubmissionValidator(jdbc, files,
            mock(FileStorage.class), mock(MediaDurationProbe.class), new ObjectMapper(),
            new MobileMediaProperties());
        assertThatThrownBy(() -> validator.validate(11L)).isInstanceOf(BizException.class)
            .hasMessageContaining("不能超过");
        file.setDurationSeconds(5d);
        assertThatCode(() -> validator.validate(11L)).doesNotThrowAnyException();
    }

    /**
     * A5 阶段三：`validate` 是只读的——没有测量值时直接拒绝，**绝不**现场拉流探测。
     *
     * <p>这条替换了原来断言"validate 会顺手测量"的用例：那个行为正是"持锁事务里跑 ffprobe"
     * 的来源。现在测量只在事务外的 preload 里发生，漏接预热的入口会立刻暴露成用户可见的拒绝。
     */
    @Test
    @SuppressWarnings("unchecked")
    void legacyMediaWithoutMeasuredDurationIsRejectedWithoutProbing() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MobileFileMapper files = mock(MobileFileMapper.class);
        FileStorage storage = mock(FileStorage.class);
        MediaDurationProbe probe = mock(MediaDurationProbe.class);
        UUID id = UUID.randomUUID();
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getString(1)).thenReturn(
            "[{\"id\":\"clip\",\"type\":\"audio_upload\",\"rules\":{\"maxDuration\":2}}]");
        when(rs.getString(2)).thenReturn("{\"clip\":[{\"id\":\"" + id + "\"}]}");
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(12L)))
            .thenAnswer(call -> ((ResultSetExtractor<?>) call.getArgument(1)).extractData(rs));
        MobileFile file = new MobileFile();
        file.setId(id);
        file.setContentType("audio/wav");
        file.setStatus("READY");
        file.setStorageKey("audio/legacy");
        when(files.selectById(id)).thenReturn(file);
        MediaSubmissionValidator validator = new MediaSubmissionValidator(jdbc, files, storage,
            probe, new ObjectMapper(), new MobileMediaProperties());

        assertThatThrownBy(() -> validator.validate(12L)).isInstanceOfSatisfying(
            BizException.class, error -> org.assertj.core.api.Assertions.assertThat(error.getCode())
                .isEqualTo("MEDIA_DURATION_UNAVAILABLE"));
        // 关键：没有拉流、没有探测、没有回写
        verifyNoInteractions(probe);
        verify(storage, never()).get(anyString());
        verify(jdbc, never()).update(anyString(), any(), any());

        // 已有测量值的文件照常通过；只读模式仍然完全不碰文件
        file.setDurationSeconds(1d);
        validator.validate(12L);
        clearInvocations(files);
        validator.validate(12L, Map.of("clip", "READONLY"));
        verifyNoInteractions(files);
    }

    /**
     * 事务外预热：只探测"缺时长"的文件，已有测量值的不重复拉流；探测异常必须被吞掉
     * （真正的错误语义仍由事务内的 validate 决定，见 code-review A5）。
     */
    @Test
    void preloadOnlyProbesFilesWithoutMeasuredDurationAndNeverThrows() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MobileFileMapper files = mock(MobileFileMapper.class);
        FileStorage storage = mock(FileStorage.class);
        MediaDurationProbe probe = mock(MediaDurationProbe.class);
        UUID cached = UUID.randomUUID();
        UUID missing = UUID.randomUUID();

        MobileFile measured = new MobileFile();
        measured.setId(cached);
        measured.setContentType("video/mp4");
        measured.setStatus("READY");
        measured.setDurationSeconds(8d);

        MobileFile unmeasured = new MobileFile();
        unmeasured.setId(missing);
        unmeasured.setContentType("video/mp4");
        unmeasured.setStatus("READY");
        unmeasured.setStorageKey("video/new");

        when(files.selectMissingDuration(any())).thenReturn(java.util.List.of(measured, unmeasured));
        when(storage.get("video/new")).thenReturn(
            new org.springframework.core.io.ByteArrayResource(new byte[]{9}));
        when(probe.seconds(any())).thenReturn(12.5d);

        MediaSubmissionValidator validator = new MediaSubmissionValidator(jdbc, files, storage,
            probe, new ObjectMapper(), new MobileMediaProperties());
        validator.preloadMediaDurations(Map.of("clip", java.util.List.of(
            Map.of("id", missing.toString()),
            Map.of("id", cached.toString()),
            Map.of("id", "not-a-uuid"))));

        // 只探一个（缺时长的那个），并把它回写；非 UUID 的 id 被忽略
        verify(probe, times(1)).seconds(any());
        verify(jdbc).update(contains("duration_seconds IS NULL"), eq(12.5d), eq(missing));

        // 探测组件抛运行时异常时，预热不得影响请求（交给事务内 validate 报错）
        doThrow(new IllegalStateException("ffprobe exploded")).when(probe).seconds(any());
        assertThatCode(() -> validator.preloadMediaDurations(
            Map.of("clip", java.util.List.of(Map.of("id", missing.toString())))))
            .doesNotThrowAnyException();
    }

    /**
     * 体积上限：拉流超过 maxProbeBytes 立即中止（fail-closed），不落地整份文件、也不进 ffprobe。
     */
    @Test
    @SuppressWarnings("unchecked")
    void oversizedMediaAbortsBeforeProbing() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MobileFileMapper files = mock(MobileFileMapper.class);
        FileStorage storage = mock(FileStorage.class);
        MediaDurationProbe probe = mock(MediaDurationProbe.class);
        UUID id = UUID.randomUUID();
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getString(1)).thenReturn(
            "[{\"id\":\"clip\",\"type\":\"video_upload\",\"props\":{\"maxDuration\":10}}]");
        when(rs.getString(2)).thenReturn("{\"clip\":[{\"id\":\"" + id + "\"}]}");
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(13L)))
            .thenAnswer(call -> ((ResultSetExtractor<?>) call.getArgument(1)).extractData(rs));
        MobileFile file = new MobileFile();
        file.setId(id);
        file.setContentType("video/mp4");
        file.setStatus("READY");
        file.setStorageKey("video/huge");
        when(files.selectById(id)).thenReturn(file);
        when(storage.get("video/huge")).thenReturn(
            new org.springframework.core.io.ByteArrayResource(new byte[64]));
        MobileMediaProperties properties = new MobileMediaProperties();
        properties.setMaxProbeBytes(16);
        MediaSubmissionValidator validator = new MediaSubmissionValidator(jdbc, files, storage,
            probe, new ObjectMapper(), properties);

        assertThatThrownBy(() -> validator.validate(13L)).isInstanceOfSatisfying(
            BizException.class,
            error -> org.assertj.core.api.Assertions.assertThat(error.getCode())
                .isEqualTo("MEDIA_DURATION_UNAVAILABLE"));
        verifyNoInteractions(probe);
        verify(jdbc, never()).update(anyString(), any(), any());
    }

    /**
     * 重提无 payload：从 taskId → 实例 → 当前修订反查数据，再走同一套预热；
     * 实例/修订缺失时静默返回（真正的错误交给 service 在事务里报）。
     */
    @Test
    @SuppressWarnings("unchecked")
    void preloadForTaskResolvesCurrentRevisionData() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MobileFileMapper files = mock(MobileFileMapper.class);
        FileStorage storage = mock(FileStorage.class);
        MediaDurationProbe probe = mock(MediaDurationProbe.class);
        UUID id = UUID.randomUUID();

        ResultSet taskRow = mock(ResultSet.class);
        when(taskRow.next()).thenReturn(true);
        when(taskRow.getLong(1)).thenReturn(99L);
        when(jdbc.query(contains("FROM t_task"), any(ResultSetExtractor.class), eq(21L)))
            .thenAnswer(call -> ((ResultSetExtractor<?>) call.getArgument(1)).extractData(taskRow));

        ResultSet revisionRow = mock(ResultSet.class);
        when(revisionRow.next()).thenReturn(true);
        when(revisionRow.getString(1)).thenReturn("{\"clip\":[{\"id\":\"" + id + "\"}]}");
        when(jdbc.query(contains("t_form_data_revision"), any(ResultSetExtractor.class), eq(99L)))
            .thenAnswer(call -> ((ResultSetExtractor<?>) call.getArgument(1)).extractData(revisionRow));

        MobileFile unmeasured = new MobileFile();
        unmeasured.setId(id);
        unmeasured.setContentType("video/mp4");
        unmeasured.setStatus("READY");
        unmeasured.setStorageKey("video/rework");
        when(files.selectMissingDuration(any())).thenReturn(java.util.List.of(unmeasured));
        when(storage.get("video/rework")).thenReturn(
            new org.springframework.core.io.ByteArrayResource(new byte[]{7}));
        when(probe.seconds(any())).thenReturn(3.5d);

        MediaSubmissionValidator validator = new MediaSubmissionValidator(jdbc, files, storage,
            probe, new ObjectMapper(), new MobileMediaProperties());
        validator.preloadForTask(21L);
        verify(probe).seconds(any());
        verify(jdbc).update(contains("duration_seconds IS NULL"), eq(3.5d), eq(id));

        // 任务不存在 / 修订已清空：静默跳过，不抛异常
        when(jdbc.query(contains("FROM t_task"), any(ResultSetExtractor.class), eq(22L)))
            .thenAnswer(call -> ((ResultSetExtractor<?>) call.getArgument(1)).extractData(taskRow));
        when(taskRow.getLong(1)).thenReturn(0L);
        when(jdbc.query(contains("t_form_data_revision"), any(ResultSetExtractor.class), eq(0L)))
            .thenAnswer(call -> ((ResultSetExtractor<?>) call.getArgument(1)).extractData(revisionRow));
        when(revisionRow.getString(1)).thenReturn(null);
        assertThatCode(() -> validator.preloadForTask(22L)).doesNotThrowAnyException();
    }
}
