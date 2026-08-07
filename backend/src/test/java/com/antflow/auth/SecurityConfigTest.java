package com.antflow.auth;

import com.antflow.audit.AuditDenialFilter;
import com.antflow.audit.RequestIdFilter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {
    @Test
    void securityChainFiltersAreNotAlsoRegisteredWithServletContainer() {
        RequestIdFilter requestIdFilter = new RequestIdFilter();
        AuditDenialFilter auditDenialFilter = new AuditDenialFilter(null);
        SecurityConfig config = new SecurityConfig(null, null, null, null, null, null,
            requestIdFilter, auditDenialFilter);

        assertThat(config.requestIdFilterRegistration().isEnabled()).isFalse();
        assertThat(config.auditDenialFilterRegistration().isEnabled()).isFalse();
    }
}
