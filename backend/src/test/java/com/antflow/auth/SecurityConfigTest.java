package com.antflow.auth;

import com.antflow.audit.AuditDenialFilter;
import com.antflow.audit.RequestIdFilter;
import com.antflow.common.IdempotencyFilter;
import com.antflow.common.IdempotencyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {
    @Test
    void securityChainFiltersAreNotAlsoRegisteredWithServletContainer() {
        RequestIdFilter requestIdFilter = new RequestIdFilter();
        AuditDenialFilter auditDenialFilter = new AuditDenialFilter(null);
        IdempotencyFilter idempotencyFilter = new IdempotencyFilter(
            new IdempotencyService(new ObjectMapper()));
        SecurityConfig config = new SecurityConfig(null, null, null, null, null,
            idempotencyFilter, requestIdFilter, auditDenialFilter);

        assertThat(config.requestIdFilterRegistration().isEnabled()).isFalse();
        assertThat(config.auditDenialFilterRegistration().isEnabled()).isFalse();
        assertThat(config.idempotencyFilterRegistration().isEnabled()).isFalse();
    }
}
