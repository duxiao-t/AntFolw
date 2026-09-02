package com.antflow.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.antflow.engine.BizException;
import com.antflow.form.FormDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class BusinessNumberServiceTest {
    private JdbcTemplate jdbc;
    private FormalNumberService legacy;
    private BusinessNumberService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        legacy = mock(FormalNumberService.class);
        service = new BusinessNumberService(jdbc, new ObjectMapper(), legacy);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(0L);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class),
            any(), any())).thenAnswer(invocation -> {
                var extractor = (org.springframework.jdbc.core.ResultSetExtractor<Long>) invocation.getArgument(1);
                ResultSet result = mock(ResultSet.class);
                when(result.next()).thenReturn(true);
                when(result.getLong(1)).thenReturn(7L);
                return extractor.extractData(result);
            });
    }

    @Test
    void usesLegacyNumberWhenNoCustomRuleExists() {
        FormDefinition form = form("{}");
        when(legacy.businessNo()).thenReturn("000000000073");
        assertThat(service.next(form, Map.of())).isEqualTo("000000000073");
    }

    @Test
    void validatesAndBuildsConfiguredNumber() {
        FormDefinition form = form("""
            {"businessNumber":{"enabled":true,"namespace":"BX","reset":"DAILY","parts":[
              {"type":"LITERAL","value":"-"},
              {"type":"FIELD","fieldId":"dept"},
              {"type":"LITERAL","value":"-"},
              {"type":"DATE","pattern":"yyyyMMdd"},
              {"type":"LITERAL","value":"-"},
              {"type":"SEQUENCE","width":4}]}}
            """);
        service.validate(form);
        assertThat(service.next(form, Map.of("dept", "研发 一部")))
            .matches("BX-研发-一部-[0-9]{8}-0007");
    }

    @Test
    void rejectsAnEmptyReferencedField() {
        FormDefinition form = form("""
            {"businessNumber":{"enabled":true,"namespace":"BX","reset":"NONE","parts":[
              {"type":"FIELD","fieldId":"dept"},{"type":"SEQUENCE","width":4}]}}
            """);
        assertThatThrownBy(() -> service.next(form, Map.of()))
            .isInstanceOfSatisfying(BizException.class, error ->
                assertThat(error.getCode()).isEqualTo("BUSINESS_NUMBER_FIELD_REQUIRED"));
    }

    private static FormDefinition form(String settings) {
        FormDefinition form = new FormDefinition();
        form.setId(10L);
        form.setSettings(settings);
        form.setSchema("[{\"id\":\"dept\",\"type\":\"text\"}]");
        return form;
    }
}
