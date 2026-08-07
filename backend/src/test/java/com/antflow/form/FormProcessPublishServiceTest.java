package com.antflow.form;

import com.antflow.audit.AuditService;
import com.antflow.engine.BizException;
import com.antflow.process.ProcessDefinition;
import com.antflow.process.ProcessDefinitionService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FormProcessPublishServiceTest {
    private final FormDefinitionService forms = Mockito.mock(FormDefinitionService.class);
    private final ProcessDefinitionService processes = Mockito.mock(ProcessDefinitionService.class);
    private final AuditService audit = Mockito.mock(AuditService.class);
    private final FormProcessPublishService service =
        new FormProcessPublishService(forms, processes, audit);

    @Test
    void publishesBothDefinitionsBeforeWritingSuccessAudits() {
        FormDefinition form = form(11L, "DRAFT", 1);
        ProcessDefinition process = process(21L, 11L, "DRAFT", 1);
        FormDefinition publishedForm = form(11L, "PUBLISHED", 2);
        ProcessDefinition publishedProcess = process(21L, 11L, "PUBLISHED", 2);
        when(forms.getById(11L)).thenReturn(form);
        when(processes.getById(21L)).thenReturn(process);
        when(forms.publish(11L)).thenReturn(publishedForm);
        when(processes.publish(21L)).thenReturn(publishedProcess);

        FormProcessPublishService.PublishResult result = service.publish(11L, 21L);

        assertThat(result.formDefinition()).isSameAs(publishedForm);
        assertThat(result.processDefinition()).isSameAs(publishedProcess);
        InOrder order = Mockito.inOrder(forms, processes, audit);
        order.verify(forms).publish(11L);
        order.verify(processes).publish(21L);
        order.verify(audit).success(Mockito.eq("form.definition.publish"), Mockito.any(),
            Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        order.verify(audit).success(Mockito.eq("workflow.definition.publish"), Mockito.any(),
            Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void rejectsProcessOwnedByAnotherFormBeforePublishing() {
        when(forms.getById(11L)).thenReturn(form(11L, "DRAFT", 1));
        when(processes.getById(21L)).thenReturn(process(21L, 12L, "DRAFT", 1));

        assertThatThrownBy(() -> service.publish(11L, 21L))
            .isInstanceOf(BizException.class)
            .matches(error -> "PROCESS_FORM_MISMATCH".equals(((BizException) error).getCode()));

        verify(forms, never()).publish(Mockito.anyLong());
        verify(processes, never()).publish(Mockito.anyLong());
        verify(audit, never()).success(Mockito.any(), Mockito.any(), Mockito.any(),
            Mockito.any(), Mockito.any(), Mockito.any());
    }

    private static FormDefinition form(long id, String status, int version) {
        FormDefinition form = new FormDefinition();
        form.setId(id);
        form.setStatus(status);
        form.setVersion(version);
        return form;
    }

    private static ProcessDefinition process(long id, long formId, String status, int version) {
        ProcessDefinition process = new ProcessDefinition();
        process.setId(id);
        process.setFormDefId(formId);
        process.setStatus(status);
        process.setVersion(version);
        return process;
    }
}
