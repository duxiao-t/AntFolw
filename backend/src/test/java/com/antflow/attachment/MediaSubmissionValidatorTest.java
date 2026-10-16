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
            mock(FileStorage.class), mock(MediaDurationProbe.class), new ObjectMapper());
        assertThatThrownBy(() -> validator.validate(11L)).isInstanceOf(BizException.class)
            .hasMessageContaining("不能超过");
        file.setDurationSeconds(5d);
        assertThatCode(() -> validator.validate(11L)).doesNotThrowAnyException();
    }

    @Test
    @SuppressWarnings("unchecked")
    void legacyMediaIsMeasuredAndMissingProbeFailsClosedWhileReadonlyIsPreserved() throws Exception {
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
        when(storage.get("audio/legacy")).thenReturn(
            new org.springframework.core.io.ByteArrayResource(new byte[]{1, 2, 3}));
        when(probe.seconds(any())).thenAnswer(call -> {
            assertThatCode(() -> org.assertj.core.api.Assertions.assertThat(
                Files.readAllBytes(call.getArgument(0, Path.class))).containsExactly(1, 2, 3))
                .doesNotThrowAnyException();
            return 1d;
        });
        MediaSubmissionValidator validator = new MediaSubmissionValidator(jdbc, files, storage,
            probe, new ObjectMapper());
        validator.validate(12L);
        verify(jdbc).update(contains("duration_seconds IS NULL"), eq(1d), eq(id));
        doReturn(null).when(probe).seconds(any());
        assertThatThrownBy(() -> validator.validate(12L)).isInstanceOfSatisfying(
            BizException.class, error -> org.assertj.core.api.Assertions.assertThat(error.getCode())
                .isEqualTo("MEDIA_DURATION_UNAVAILABLE"));
        clearInvocations(files);
        validator.validate(12L, Map.of("clip", "READONLY"));
        verifyNoInteractions(files);
    }
}
