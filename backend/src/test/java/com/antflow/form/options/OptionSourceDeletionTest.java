package com.antflow.form.options;

import com.antflow.audit.AuditService;
import com.antflow.authz.AuthorizationService;
import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OptionSourceDeletionTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditService audit = mock(AuditService.class);
    private final OptionSourceService service = new OptionSourceService(jdbc, new ObjectMapper(),
        mock(AuthorizationService.class), audit);

    @SuppressWarnings("unchecked")
    private void activeSource() throws Exception {
        when(jdbc.query(contains("FOR UPDATE"), any(ResultSetExtractor.class), eq(7L)))
            .thenAnswer(call -> {
                ResultSet rows = mock(ResultSet.class);
                when(rows.next()).thenReturn(true);
                when(rows.getString(1)).thenReturn("ACTIVE");
                return ((ResultSetExtractor<String>) call.getArgument(1)).extractData(rows);
            });
    }

    @Test
    void deletesOnlyAnUnreferencedNeverPublishedSource() throws Exception {
        activeSource();
        when(jdbc.queryForObject(contains("status = 'PUBLISHED'"), eq(Long.class), eq(7L))).thenReturn(0L);
        when(jdbc.queryForObject(contains("jsonb_path_exists"), eq(Boolean.class), eq(7L))).thenReturn(false);
        when(jdbc.update(contains("DELETE FROM t_option_data_source_version"), eq(7L))).thenReturn(1);

        service.delete(7L);

        verify(jdbc).update(contains("DELETE FROM t_option_data_source_version"), eq(7L));
        verify(jdbc).update(contains("DELETE FROM t_option_data_source WHERE id"), eq(7L));
        verify(audit).success(eq("form.option_source.delete"), eq("OPTION_SOURCE"), eq(7L),
            eq(AuditService.RiskLevel.HIGH), any(), any());
    }

    @Test
    void rejectsPublishedOrReferencedSourcesBeforeDeletingRows() throws Exception {
        activeSource();
        when(jdbc.queryForObject(contains("status = 'PUBLISHED'"), eq(Long.class), eq(7L))).thenReturn(1L);
        assertThatThrownBy(() -> service.delete(7L)).isInstanceOf(BizException.class)
            .hasMessageContaining("已发布");
        verify(jdbc, never()).update(contains("DELETE FROM t_option_data_source_version"), eq(7L));

        when(jdbc.queryForObject(contains("status = 'PUBLISHED'"), eq(Long.class), eq(7L))).thenReturn(0L);
        when(jdbc.queryForObject(contains("jsonb_path_exists"), eq(Boolean.class), eq(7L))).thenReturn(true);
        assertThatThrownBy(() -> service.delete(7L)).isInstanceOf(BizException.class)
            .hasMessageContaining("被表单引用");
        verify(jdbc, never()).update(contains("DELETE FROM t_option_data_source WHERE id"), eq(7L));
    }
}
