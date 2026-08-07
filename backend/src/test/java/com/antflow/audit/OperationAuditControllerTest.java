package com.antflow.audit;

import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.automation.WorkflowJobService;
import com.antflow.engine.BizException;
import com.antflow.engine.ProcessEngine;
import com.antflow.engine.dto.StartCmd;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionController;
import com.antflow.form.FormDefinitionMapper;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.FormProcessPublishService;
import com.antflow.org.User;
import com.antflow.org.UserController;
import com.antflow.org.UserMapper;
import com.antflow.org.UserService;
import com.antflow.process.ProcessDefinition;
import com.antflow.process.ProcessDefinitionController;
import com.antflow.process.ProcessDefinitionMapper;
import com.antflow.process.ProcessDefinitionService;
import com.antflow.task.InstanceController;
import com.antflow.task.ProcessInstanceMapper;
import com.antflow.task.TaskController;
import com.antflow.task.TaskHistoryMapper;
import com.antflow.task.TaskMapper;
import com.antflow.task.TaskOperationService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationAuditControllerTest {
    @BeforeEach
    void setPrincipal() {
        PrincipalHolder.set(new PrincipalHolder.Principal(7L, "operator", List.of("admin")));
    }

    @AfterEach
    void clearPrincipal() {
        PrincipalHolder.clear();
    }

    @Test
    void formSaveAuditsFieldNamesWithoutDefinitionValues() {
        FormDefinitionService service = Mockito.mock(FormDefinitionService.class);
        AuditService auditService = mockAuditService();
        FormDefinitionController controller = new FormDefinitionController(service,
            Mockito.mock(FormDefinitionMapper.class), Mockito.mock(AuthorizationService.class),
            auditService, Mockito.mock(FormProcessPublishService.class));
        FormDefinition saved = new FormDefinition();
        saved.setId(41L);
        saved.setVersion(1);
        Object schema = List.of(Map.of("id", "salary", "defaultValue", "private-value"));
        Object settings = Map.of("webhookSecret", "never-audit-this");
        when(service.saveDraft(isNull(), eq("leave"), eq("Leave request"),
            eq("private description"), eq(schema), eq(settings), eq(7L))).thenReturn(saved);

        controller.save(new FormDefinitionController.SaveBody(null, "leave", "Leave request",
            "private description", null, schema, settings));

        ArgumentCaptor<Map<String, ?>> diff = mapCaptor();
        ArgumentCaptor<Map<String, ?>> metadata = mapCaptor();
        verify(auditService).success(eq("form.definition.create"),
            eq("FORM_DEFINITION"), eq(41L), eq(AuditService.RiskLevel.HIGH),
            diff.capture(), metadata.capture());
        assertThat(diff.getValue().toString())
            .contains("schema", "settings")
            .doesNotContain("private-value", "never-audit-this", "private description");
    }

    @Test
    void processSaveDoesNotCopyWebhookConfigurationIntoAuditData() {
        ProcessDefinitionService service = Mockito.mock(ProcessDefinitionService.class);
        AuditService auditService = mockAuditService();
        ProcessDefinitionController controller = new ProcessDefinitionController(service,
            Mockito.mock(ProcessDefinitionMapper.class), Mockito.mock(AuthorizationService.class),
            auditService);
        ProcessDefinition saved = new ProcessDefinition();
        saved.setId(51L);
        saved.setFormDefId(41L);
        saved.setVersion(3);
        Object process = Map.of("url", "https://internal.example/hook",
            "secret", "never-audit-this");
        when(service.saveOrUpdateDraft(isNull(), eq(41L), eq(process), eq(7L)))
            .thenReturn(saved);

        controller.save(new ProcessDefinitionController.SaveBody(null, 41L, process));

        ArgumentCaptor<Map<String, ?>> diff = mapCaptor();
        ArgumentCaptor<Map<String, ?>> metadata = mapCaptor();
        verify(auditService).success(eq("workflow.definition.save"),
            eq("PROCESS_DEFINITION"), eq(51L), eq(AuditService.RiskLevel.HIGH),
            diff.capture(), metadata.capture());
        assertThat(diff.getValue().toString()).contains("process")
            .doesNotContain("internal.example", "never-audit-this");
        assertThat(metadata.getValue().get("formDefinitionId")).isEqualTo(41L);
    }

    @Test
    void taskAuditRunsAfterSuccessAndStoresOnlyCommentLength() {
        ProcessEngine engine = Mockito.mock(ProcessEngine.class);
        TaskOperationService operations = Mockito.mock(TaskOperationService.class);
        AuditService auditService = mockAuditService();
        TaskController controller = new TaskController(engine, operations,
            Mockito.mock(AuthorizationService.class), Mockito.mock(TaskMapper.class),
            Mockito.mock(ProcessInstanceMapper.class), auditService);
        when(operations.transfer(10L, 12L, "private approval comment")).thenReturn(11L);

        controller.transfer(10L, Map.of("targetUserId", 12L,
            "comment", "private approval comment"));

        InOrder order = inOrder(operations, auditService);
        order.verify(operations).transfer(10L, 12L, "private approval comment");
        ArgumentCaptor<Map<String, ?>> metadata = mapCaptor();
        order.verify(auditService).success(eq("workflow.task.transfer"), eq("TASK"),
            eq(10L), eq(AuditService.RiskLevel.HIGH), any(), metadata.capture());
        assertThat(metadata.getValue().get("commentLength")).isEqualTo(24);
        assertThat(metadata.getValue().toString()).doesNotContain("private approval comment");
    }

    @Test
    void failedWorkflowOperationDoesNotEmitSuccessAudit() {
        ProcessEngine engine = Mockito.mock(ProcessEngine.class);
        AuditService auditService = mockAuditService();
        TaskController controller = new TaskController(engine,
            Mockito.mock(TaskOperationService.class), Mockito.mock(AuthorizationService.class),
            Mockito.mock(TaskMapper.class), Mockito.mock(ProcessInstanceMapper.class), auditService);
        Mockito.doThrow(new BizException("TASK_NOT_PENDING", "task not pending"))
            .when(engine).approve(any(), eq(7L));

        assertThrows(BizException.class,
            () -> controller.approve(10L, Map.of("comment", "private comment")));

        verify(auditService, never()).success(any(), any(), any(), any(), any(), any());
    }

    @Test
    void workflowStartAuditsCountsWithoutSubmittedFormValues() {
        ProcessEngine engine = Mockito.mock(ProcessEngine.class);
        AuditService auditService = mockAuditService();
        InstanceController controller = new InstanceController(engine,
            Mockito.mock(ProcessInstanceMapper.class), Mockito.mock(TaskMapper.class),
            Mockito.mock(TaskHistoryMapper.class), Mockito.mock(WorkflowJobService.class),
            Mockito.mock(AuthorizationService.class), auditService);
        StartCmd command = new StartCmd("leave", Map.of("salary", "private-value"), Map.of());
        when(engine.start(command, 7L)).thenReturn(Map.of(
            "instanceId", 61L, "businessNo", "PRIVATE-BUSINESS-NO"));

        controller.start(command);

        ArgumentCaptor<Map<String, ?>> metadata = mapCaptor();
        verify(auditService).success(eq("workflow.instance.start"),
            eq("PROCESS_INSTANCE"), eq(61L), eq(AuditService.RiskLevel.NORMAL), any(),
            metadata.capture());
        assertThat(metadata.getValue().get("fieldCount")).isEqualTo(1);
        assertThat(metadata.getValue().toString())
            .doesNotContain("private-value", "PRIVATE-BUSINESS-NO");
    }

    @Test
    void userCreateFiltersRequestValuesAndUnknownSensitiveFields() {
        UserService service = Mockito.mock(UserService.class);
        AuditService auditService = mockAuditService();
        UserController controller = new UserController(Mockito.mock(UserMapper.class), service,
            Mockito.mock(AuthorizationService.class), auditService);
        when(service.create(any(User.class), eq(List.of(2L)), eq("never-audit-this"))).thenReturn(71L);
        Map<String, Object> body = Map.ofEntries(
            Map.entry("username", "private-user"),
            Map.entry("displayName", "Private Name"),
            Map.entry("email", "private@example.com"),
            Map.entry("password", "never-audit-this"),
            Map.entry("roleIds", List.of(2L)));

        controller.create(body);

        ArgumentCaptor<Map<String, ?>> diff = mapCaptor();
        verify(auditService).success(eq("org.user.create"), eq("USER"), eq(71L),
            eq(AuditService.RiskLevel.HIGH), diff.capture(), any());
        assertThat(diff.getValue().toString()).contains("username", "displayName", "email")
            .doesNotContain("private-user", "Private Name", "private@example.com",
                "password", "never-audit-this");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Map<String, ?>> mapCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }

    private static AuditService mockAuditService() {
        AuditService service = Mockito.mock(AuditService.class, Mockito.CALLS_REAL_METHODS);
        Mockito.doNothing().when(service).success(any(), any(), any(), any(), any(), any());
        return service;
    }
}
