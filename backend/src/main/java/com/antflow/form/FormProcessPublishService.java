package com.antflow.form;

import com.antflow.audit.AuditService;
import com.antflow.engine.BizException;
import com.antflow.process.ProcessDefinition;
import com.antflow.process.ProcessDefinitionService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FormProcessPublishService {
    private final FormDefinitionService formDefinitionService;
    private final ProcessDefinitionService processDefinitionService;
    private final AuditService auditService;

    @Transactional(rollbackFor = Exception.class)
    public PublishResult publish(long formDefinitionId, long processDefinitionId) {
        FormDefinition form = formDefinitionService.getById(formDefinitionId);
        if (form == null) {
            throw new BizException("FORM_NOT_FOUND", "Form not found: " + formDefinitionId);
        }
        ProcessDefinition process = processDefinitionService.getById(processDefinitionId);
        if (process == null) {
            throw new BizException("PROCESS_NOT_FOUND", "Process not found: " + processDefinitionId);
        }
        if (!java.util.Objects.equals(process.getFormDefId(), formDefinitionId)) {
            throw new BizException("PROCESS_FORM_MISMATCH",
                "Process definition does not belong to the form");
        }

        FormDefinition publishedForm = formDefinitionService.publish(formDefinitionId);
        ProcessDefinition publishedProcess = processDefinitionService.publish(processDefinitionId);
        auditService.success("form.definition.publish", "FORM_DEFINITION", formDefinitionId,
            AuditService.RiskLevel.HIGH,
            Map.of("changedFields", List.of("status", "version")),
            Map.of("version", publishedForm.getVersion()));
        auditService.success("workflow.definition.publish", "PROCESS_DEFINITION",
            processDefinitionId, AuditService.RiskLevel.HIGH,
            Map.of("changedFields", List.of("status", "version")),
            Map.of("formDefinitionId", formDefinitionId,
                "version", publishedProcess.getVersion()));
        return new PublishResult(publishedForm, publishedProcess);
    }

    public record PublishResult(FormDefinition formDefinition,
                                ProcessDefinition processDefinition) { }
}
