package com.antflow.automation;

import com.antflow.engine.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebhookSecurityPolicyTest {
    @Test
    void requiresAllowlistAndBlocksLoopbackByDefault() {
        AutomationProperties properties = new AutomationProperties();
        properties.setAllowedHosts(List.of("localhost"));
        WebhookSecurityPolicy policy = new WebhookSecurityPolicy(properties, environment(false));

        assertThatThrownBy(() -> policy.validate(URI.create("http://localhost/hook")))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("不允许访问");
        assertThatThrownBy(() -> policy.validateDefinition(URI.create("https://other.example/hook")))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("允许列表");
    }

    @Test
    void permitsExplicitLocalTestOverrideAndEnforcesHttpsInProduction() {
        AutomationProperties properties = new AutomationProperties();
        properties.setAllowedHosts(List.of("localhost"));
        properties.setAllowPrivateAddresses(true);
        WebhookSecurityPolicy local = new WebhookSecurityPolicy(properties, environment(false));
        assertThatCode(() -> local.validate(URI.create("http://localhost/hook")))
            .doesNotThrowAnyException();

        WebhookSecurityPolicy production = new WebhookSecurityPolicy(properties, environment(true));
        assertThatThrownBy(() -> production.validateDefinition(URI.create("http://localhost/hook")))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("HTTPS");
    }

    private static Environment environment(boolean production) {
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(production);
        return environment;
    }
}
