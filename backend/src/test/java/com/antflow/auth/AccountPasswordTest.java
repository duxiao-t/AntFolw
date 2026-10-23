package com.antflow.auth;

import com.antflow.audit.AuditService;
import com.antflow.common.GlobalExceptionHandler;
import com.antflow.engine.BizException;
import com.antflow.org.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 自助改密的 HTTP 契约。
 *
 * <p>这组用例是为一个真实故障补的：前端曾把整个表单对象（含 {@code confirmPassword}）发过来，
 * 后端 record 没有这个属性，Jackson 在反序列化阶段抛 {@code UnrecognizedPropertyException}，
 * 而全局异常处理器没有对应 handler，最终变成 500 "internal error" —— 用户既改不了密码，
 * 也看不到任何有用信息（业务层的 PASSWORD_INCORRECT 根本没执行到）。
 *
 * <p>三个用例分别钉住：① 多余字段不再报错；② 业务错误仍是 422 + 明确 code；③ 坏请求体是 400 而不是 500。
 */
class AccountPasswordTest {

    private static final long USER_ID = 7L;
    private static final UUID SESSION_ID = UUID.randomUUID();

    private UserService userService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        mvc = MockMvcBuilders
            .standaloneSetup(new AccountController(mock(JdbcTemplate.class), userService))
            .setControllerAdvice(new GlobalExceptionHandler(mock(AuditService.class)))
            .build();
        PrincipalHolder.set(new PrincipalHolder.Principal(
            USER_ID, "admin", "管理员", Set.of("admin"), Set.of(), 0L, 1L, SESSION_ID));
    }

    @AfterEach
    void tearDown() {
        PrincipalHolder.clear();
    }

    @Test
    void ignoresExtraConfirmPasswordField() throws Exception {
        mvc.perform(post("/api/account/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"currentPassword":"old-pass","newPassword":"new-pass-123","confirmPassword":"new-pass-123"}
                    """))
            .andExpect(status().isOk());

        verify(userService).changeOwnPassword(eq(USER_ID), eq(SESSION_ID), eq("old-pass"), eq("new-pass-123"));
    }

    @Test
    void wrongCurrentPasswordSurfacesAsUnprocessableWithCode() throws Exception {
        doThrow(new BizException("PASSWORD_INCORRECT", "原密码不正确"))
            .when(userService).changeOwnPassword(anyLong(), any(), any(), any());

        mvc.perform(post("/api/account/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"currentPassword":"wrong","newPassword":"new-pass-123"}
                    """))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("PASSWORD_INCORRECT"));
    }

    /** 坏 JSON 曾经落到兜底 Exception → 500 "internal error"；现在必须是 400 且不回显 Jackson 原文。 */
    @Test
    void malformedBodyIsBadRequestNotInternalError() throws Exception {
        mvc.perform(post("/api/account/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ this is not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("REQUEST_BODY_INVALID"))
            .andExpect(jsonPath("$.message").value("请求内容无法解析"));
    }
}
