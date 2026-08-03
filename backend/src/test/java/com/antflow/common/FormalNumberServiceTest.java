package com.antflow.common;

import com.antflow.engine.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;

class FormalNumberServiceTest {
    private JdbcTemplate jdbcTemplate;
    private FormalNumberService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = Mockito.mock(JdbcTemplate.class);
        service = new FormalNumberService(jdbcTemplate);
    }

    @Test
    void acceptsAvailableManualEmployeeNumber() {
        Mockito.when(jdbcTemplate.queryForObject(
            eq("SELECT COUNT(*) FROM t_user WHERE employee_no = ?"), eq(Long.class),
            eq("001246"))).thenReturn(0L);

        assertThat(service.employeeNo(" 001246 ", null)).isEqualTo("001246");
    }

    @Test
    void rejectsInvalidAndDuplicateEmployeeNumbers() {
        assertThatThrownBy(() -> service.employeeNo("SC-246", null))
            .isInstanceOf(BizException.class)
            .extracting(error -> ((BizException) error).getCode())
            .isEqualTo("BAD_EMPLOYEE_NO");

        Mockito.when(jdbcTemplate.queryForObject(
            eq("SELECT COUNT(*) FROM t_user WHERE employee_no = ?"), eq(Long.class),
            eq("001246"))).thenReturn(1L);
        assertThatThrownBy(() -> service.employeeNo("001246", null))
            .isInstanceOf(BizException.class)
            .extracting(error -> ((BizException) error).getCode())
            .isEqualTo("EMPLOYEE_NO_EXISTS");
    }

    @Test
    void generatesZeroPaddedEmployeeAndBusinessNumbers() {
        Mockito.when(jdbcTemplate.queryForObject(
            eq("SELECT nextval('seq_employee_no')"), eq(Long.class))).thenReturn(42L);
        Mockito.when(jdbcTemplate.queryForObject(
            eq("SELECT COUNT(*) FROM t_user WHERE employee_no = ?"), eq(Long.class),
            eq("000042"))).thenReturn(0L);
        Mockito.when(jdbcTemplate.queryForObject(
            eq("SELECT nextval('seq_business_no')"), eq(Long.class))).thenReturn(73L);
        Mockito.when(jdbcTemplate.queryForObject(
            eq("SELECT COUNT(*) FROM t_form_data WHERE business_no = ?"), eq(Long.class),
            eq("000000000073"))).thenReturn(0L);

        assertThat(service.employeeNo(null, null)).isEqualTo("000042");
        assertThat(service.businessNo()).isEqualTo("000000000073");
    }
}
