package com.antflow.common;

import com.antflow.engine.BizException;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FormalNumberService {
    private static final Pattern EMPLOYEE_NO = Pattern.compile("^[0-9]{6}$");
    private static final int MAX_GENERATION_ATTEMPTS = 100;

    private final JdbcTemplate jdbcTemplate;

    public String employeeNo(String requested, Long excludedUserId) {
        String normalized = requested == null ? "" : requested.trim();
        if (!normalized.isEmpty()) {
            if (!EMPLOYEE_NO.matcher(normalized).matches()) {
                throw new BizException("BAD_EMPLOYEE_NO", "工号必须为 6 位数字");
            }
            ensureEmployeeNoAvailable(normalized, excludedUserId);
            return normalized;
        }
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String candidate = nextFormatted("seq_employee_no", 6, "EMPLOYEE_NO_EXHAUSTED");
            if (employeeNoCount(candidate, null) == 0) {
                return candidate;
            }
        }
        throw new BizException("EMPLOYEE_NO_EXHAUSTED", "可用工号已耗尽");
    }

    public String businessNo() {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String candidate = nextFormatted("seq_business_no", 12, "BUSINESS_NO_EXHAUSTED");
            Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_form_data WHERE business_no = ?", Long.class, candidate);
            if (count == null || count == 0) {
                return candidate;
            }
        }
        throw new BizException("BUSINESS_NO_EXHAUSTED", "可用单号已耗尽");
    }

    private void ensureEmployeeNoAvailable(String value, Long excludedUserId) {
        if (employeeNoCount(value, excludedUserId) > 0) {
            throw new BizException("EMPLOYEE_NO_EXISTS", "工号已存在");
        }
    }

    private long employeeNoCount(String value, Long excludedUserId) {
        Long count;
        if (excludedUserId == null) {
            count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_user WHERE employee_no = ?", Long.class, value);
        } else {
            count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_user WHERE employee_no = ? AND id <> ?",
                Long.class, value, excludedUserId);
        }
        return count == null ? 0 : count;
    }

    private String nextFormatted(String sequence, int width, String exhaustedCode) {
        try {
            Long value = jdbcTemplate.queryForObject("SELECT nextval('" + sequence + "')", Long.class);
            if (value == null) {
                throw new BizException(exhaustedCode, "编号生成失败");
            }
            return String.format("%0" + width + "d", value);
        } catch (org.springframework.dao.DataAccessException exception) {
            throw new BizException(exhaustedCode, "编号号段已耗尽");
        }
    }
}
