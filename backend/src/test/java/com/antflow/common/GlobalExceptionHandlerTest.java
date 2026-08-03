package com.antflow.common;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsDuplicateUsernameConstraintToReadableConflict() {
        DataIntegrityViolationException error = new DataIntegrityViolationException(
            "insert failed",
            new IllegalStateException("duplicate key violates t_user_username_key"));

        ResponseEntity<Map<String, Object>> response = handler.handleDataIntegrity(error);

        assertEquals(409, response.getStatusCode().value());
        assertEquals("USERNAME_EXISTS", response.getBody().get("code"));
        assertEquals("账号已存在", response.getBody().get("message"));
    }

    @Test
    void mapsDepartmentUserConstraintToReadableConflict() {
        DataIntegrityViolationException error = new DataIntegrityViolationException(
            "delete failed",
            new IllegalStateException("foreign key violates t_user_dept_id_fkey"));

        ResponseEntity<Map<String, Object>> response = handler.handleDataIntegrity(error);

        assertEquals(409, response.getStatusCode().value());
        assertEquals("HAS_USERS", response.getBody().get("code"));
        assertEquals("部门下仍有成员，请先移动或删除成员", response.getBody().get("message"));
    }
}
