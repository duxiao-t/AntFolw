package com.antflow.form.correction;

import com.antflow.attachment.MediaSubmissionValidator;
import com.antflow.attachment.MobileFileLinkService;
import com.antflow.audit.AuditService;
import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.engine.BizException;
import com.antflow.form.FormDefinitionService;
import com.antflow.notify.NotificationPublisher;
import com.antflow.process.DefinitionVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FormDataCorrectionServiceTest {
    private final FormDataCorrectionRepository repository = mock(FormDataCorrectionRepository.class);
    private final CorrectionSchemaResolver schemaResolver = mock(CorrectionSchemaResolver.class);
    private final CorrectionFieldPolicy fieldPolicy = mock(CorrectionFieldPolicy.class);
    private final AuthorizationService authorization = mock(AuthorizationService.class);
    private final FormDefinitionService formDefinitions = mock(FormDefinitionService.class);
    private final DefinitionVersionRepository versions = mock(DefinitionVersionRepository.class);
    private final MobileFileLinkService fileLinks = mock(MobileFileLinkService.class);
    private final MediaSubmissionValidator mediaValidator = mock(MediaSubmissionValidator.class);
    private final AuditService audit = mock(AuditService.class);
    private final NotificationPublisher notifications = mock(NotificationPublisher.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final FormDataCorrectionService service = new FormDataCorrectionService(repository,
        schemaResolver, fieldPolicy, authorization, formDefinitions, versions, fileLinks,
        mediaValidator, audit, notifications, jdbc, new ObjectMapper());

    @AfterEach
    void clearPrincipal() {
        PrincipalHolder.clear();
    }

    @Test
    void draftCannotOpenCorrectionForm() {
        when(repository.findData(1056L)).thenReturn(draft());

        assertThatThrownBy(() -> service.view(1056L))
            .isInstanceOfSatisfying(BizException.class, error ->
                assertThat(error.getCode()).isEqualTo("CORRECTION_STATUS_NOT_ALLOWED"));
    }

    @Test
    void draftCannotBeCorrected() {
        PrincipalHolder.set(new PrincipalHolder.Principal(1L, "admin", List.of("admin")));
        when(repository.lockData(1056L)).thenReturn(draft());

        assertThatThrownBy(() -> service.correct(1056L,
            new CorrectionDtos.CorrectionRequest(null, Map.of("amount", 1), "OTHER", null, null)))
            .isInstanceOfSatisfying(BizException.class, error ->
                assertThat(error.getCode()).isEqualTo("CORRECTION_STATUS_NOT_ALLOWED"));
    }

    @Test
    void draftPendingCorrectionCannotBeApprovedButCanBeRejected() {
        PrincipalHolder.set(new PrincipalHolder.Principal(1L, "admin", List.of("admin")));
        when(repository.lockData(1056L)).thenReturn(draft());

        assertThatThrownBy(() -> service.review(1056L, 7L,
            new CorrectionDtos.ReviewRequest(true, null)))
            .isInstanceOfSatisfying(BizException.class, error ->
                assertThat(error.getCode()).isEqualTo("CORRECTION_STATUS_NOT_ALLOWED"));

        when(repository.lockPending(7L)).thenReturn(new FormDataCorrectionRepository.PendingRow(
            7L, 1056L, "{}", "PENDING_REVIEW", null, 2L));
        when(repository.markRejected(eq(7L), eq(1L), anyString())).thenReturn(1);

        CorrectionDtos.CorrectionResult result = service.review(1056L, 7L,
            new CorrectionDtos.ReviewRequest(false, "草稿不进入修正流程"));

        assertThat(result.status()).isEqualTo("REJECTED");
        verify(repository).markRejected(7L, 1L, "草稿不进入修正流程");
    }

    private FormDataCorrectionRepository.DataRow draft() {
        return new FormDataCorrectionRepository.DataRow(1056L, 12L, 7, "{}", "DRAFT",
            null, 638L, 3675L);
    }
}
