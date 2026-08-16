package com.antflow.mobile.workflow;

import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.audit.AuditService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MobileWorkflowControllerAuditTest {
    private final MobileDraftService draftService = Mockito.mock(MobileDraftService.class);
    private final MobileWorkflowService workflowService = Mockito.mock(MobileWorkflowService.class);
    private final AuthorizationService authorizationService = Mockito.mock(AuthorizationService.class);
    private final AuditService auditService = mockAuditService();
    private final MobileWorkflowController controller = new MobileWorkflowController(
        draftService, workflowService, authorizationService, auditService);

    private static AuditService mockAuditService() {
        AuditService service = Mockito.mock(AuditService.class, Mockito.CALLS_REAL_METHODS);
        Mockito.doNothing().when(service).success(
            Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        return service;
    }

    @BeforeEach
    void setPrincipal() {
        PrincipalHolder.set(new PrincipalHolder.Principal(7L, "mobile-user", List.of("user")));
    }

    @AfterEach
    void clearPrincipal() {
        PrincipalHolder.clear();
    }

    @Test
    void startAuditsFieldNamesAndCountsWithoutFormValues() throws Exception {
        JsonNode data = new ObjectMapper().readTree("{\"salary\":\"private-value\"}");
        StartMobileInstanceRequest request = new StartMobileInstanceRequest(
            "leave", data, Map.of(), null, List.of());
        when(workflowService.start(request, 7L)).thenReturn(
            new MobileStartResult(41L, 51L, "PRIVATE-BUSINESS-NO", List.of(61L)));

        controller.start(request);

        ArgumentCaptor<Map<String, ?>> metadata = mapCaptor();
        verify(auditService).success(eq("workflow.instance.start"),
            eq("PROCESS_INSTANCE"), eq(41L), eq(AuditService.RiskLevel.NORMAL), any(),
            metadata.capture());
        assertThat(metadata.getValue().get("fieldCount")).isEqualTo(1);
        assertThat(metadata.getValue().toString())
            .doesNotContain("private-value", "PRIVATE-BUSINESS-NO");
    }

    @Test
    void taskActionAuditsCommentLengthWithoutCommentText() {
        MobileTaskActionRequest request = new MobileTaskActionRequest("private comment", null);

        controller.approve(71L, request);

        ArgumentCaptor<Map<String, ?>> metadata = mapCaptor();
        verify(auditService).success(eq("workflow.task.approve"), eq("TASK"), eq(71L),
            eq(AuditService.RiskLevel.HIGH), any(), metadata.capture());
        assertThat(metadata.getValue().get("commentLength")).isEqualTo(15);
        assertThat(metadata.getValue().toString()).doesNotContain("private comment");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Map<String, ?>> mapCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }
}
