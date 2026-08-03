package com.antflow.common;

import com.antflow.engine.BizException;
import com.antflow.engine.NoAssigneeFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(NoAssigneeFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoAssignee(NoAssigneeFoundException e) {
        Map<String, Object> body = envelope(e.getCode(), e.getMessage());
        body.put("nodeId", e.nodeId());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Map<String, Object>> handleBiz(BizException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(envelope(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException e) {
        String detail = e.getMostSpecificCause().getMessage();
        if (detail != null && detail.contains("t_user_username_key")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(envelope("USERNAME_EXISTS", "账号已存在"));
        }
        if (detail != null && detail.contains("t_user_dept_id_fkey")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(envelope("HAS_USERS", "部门下仍有成员，请先移动或删除成员"));
        }
        log.warn("data integrity conflict: {}", detail);
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(envelope("DATA_CONFLICT", "数据存在关联或重复，请检查后重试"));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCreds(BadCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(envelope("INVALID_CREDENTIALS", "invalid credentials"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(envelope("ACCESS_DENIED", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, Object> body = envelope("VALIDATION_FAILED", "Request validation failed");
        body.put("fieldErrors", e.getBindingResult().getFieldErrors().stream()
            .map(fe -> Map.of("field", fe.getField(), "message", fe.getDefaultMessage()))
            .toList());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    /** Unknown route (Spring 6.1+ throws NoResourceFoundException for unmapped paths). */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(envelope("NOT_FOUND", "endpoint not found"));
    }

    /** Multipart upload exceeded the configured per-file / per-request limit. */
    @ExceptionHandler({ MaxUploadSizeExceededException.class, MultipartException.class })
    public ResponseEntity<Map<String, Object>> handleMultipart(Exception e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(envelope("FILE_TOO_LARGE", "uploaded file exceeds size limit"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAny(Exception e) {
        log.error("unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(envelope("INTERNAL_ERROR", "internal error"));
    }

    private Map<String, Object> envelope(String code, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("traceId", UUID.randomUUID().toString());
        return body;
    }
}
