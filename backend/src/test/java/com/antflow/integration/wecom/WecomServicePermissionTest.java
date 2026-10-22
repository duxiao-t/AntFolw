package com.antflow.integration.wecom;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.antflow.audit.AuditService;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

class WecomServicePermissionTest {
    private AuthorizationService authorization;
    private WecomService service;

    @BeforeEach
    void setUp() {
        authorization = mock(AuthorizationService.class);
        service = new WecomService(mock(JdbcTemplate.class), mock(WecomSecretCipher.class),
            mock(WecomClient.class), authorization, mock(AuditService.class), new ObjectMapper(),
            mock(TransactionTemplate.class), mock(Executor.class), new WecomProperties(),
            new WecomSyncLease(mock(JdbcTemplate.class)),
            new WecomManagerSync(mock(JdbcTemplate.class), mock(WecomClient.class),
                mock(TransactionTemplate.class), mock(WecomSyncLease.class)),
            new WecomDirectorySync(mock(JdbcTemplate.class), mock(PasswordEncoder.class),
                mock(TransactionTemplate.class), mock(WecomSyncLease.class)),
            new WecomDepartmentSync(mock(JdbcTemplate.class), mock(TransactionTemplate.class),
                mock(WecomSyncLease.class)));
    }

    @Test
    void settingsRequireCompanyManagement() {
        doThrow(new AccessDeniedException("denied")).when(authorization)
            .requirePermission(PermissionCodes.INTEGRATION_WECOM_MANAGE);

        assertThatThrownBy(() -> service.settings(1)).isInstanceOf(AccessDeniedException.class);
        verify(authorization).requirePermission(PermissionCodes.INTEGRATION_WECOM_MANAGE);
    }

    @Test
    void startingSyncRequiresFullDepartmentAndUserScopes() {
        doThrow(new AccessDeniedException("user scope")).when(authorization)
            .requireAllDataScope(PermissionCodes.ORG_USER_MANAGE);

        assertThatThrownBy(() -> service.start(1, "FULL")).isInstanceOf(AccessDeniedException.class);
        verify(authorization).requireAllDataScope(PermissionCodes.ORG_DEPARTMENT_MANAGE);
        verify(authorization).requireAllDataScope(PermissionCodes.ORG_USER_MANAGE);
    }
}
