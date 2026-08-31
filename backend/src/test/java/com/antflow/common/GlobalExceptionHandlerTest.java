package com.antflow.common;

import com.antflow.audit.AuditService;
import com.antflow.audit.RequestIdFilter;
import com.antflow.engine.BizException;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class GlobalExceptionHandlerTest {
    private final AuditService auditService = org.mockito.Mockito.mock(AuditService.class);
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(auditService);

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void reusesRequestIdAsTraceId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestIdFilter.ATTRIBUTE, "trace-123");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        ResponseEntity<Map<String, Object>> response = handler.handleBiz(
            new BizException("BAD_REQUEST", "bad request"));

        assertEquals("trace-123", response.getBody().get("traceId"));
    }

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

    @Test
    void auditsBusinessFailureWithoutPersistingExceptionMessage() {
        BizException error = new BizException("FORM_DATA_INVALID", "private field value");

        ResponseEntity<Map<String, Object>> response = handler.handleBiz(error);

        assertEquals(422, response.getStatusCode().value());
        verify(auditService).failure("request.failed", "HTTP_RESOURCE", null,
            AuditService.RiskLevel.HIGH, "FORM_DATA_INVALID",
            Map.of("exceptionType", "BizException"));
    }

    @Test
    void ignoresDisconnectedSseClient() {
        assertEquals(null, handler.handleAny(new IOException("Broken pipe")));
        verifyNoInteractions(auditService);
    }
}
