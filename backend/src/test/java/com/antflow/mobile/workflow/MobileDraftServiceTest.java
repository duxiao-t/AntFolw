package com.antflow.mobile.workflow;

import com.antflow.engine.BizException;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.runtime.FormData;
import com.antflow.form.runtime.FormDataMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

class MobileDraftServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private FormDataMapper formDataMapper;
    private FormDefinitionService formDefinitionService;
    private MobileDraftService service;

    @BeforeEach
    void setUp() {
        formDataMapper = Mockito.mock(FormDataMapper.class);
        formDefinitionService = Mockito.mock(FormDefinitionService.class);
        service = new MobileDraftService(formDataMapper, formDefinitionService, objectMapper);
        Mockito.doAnswer(invocation -> {
            FormData draft = invocation.getArgument(0);
            draft.setId(100L);
            return 1;
        }).when(formDataMapper).insert(any(FormData.class));
    }

    @Test
    void createStoresDraftForPublishedForm() throws Exception {
        Mockito.when(formDefinitionService.getByCode("leave")).thenReturn(form("leave", "PUBLISHED"));
        JsonNode data = objectMapper.createObjectNode().put("days", 2);

        Long id = service.create("leave", data, 7L);

        assertThat(id).isEqualTo(100L);
        ArgumentCaptor<FormData> captor = ArgumentCaptor.forClass(FormData.class);
        Mockito.verify(formDataMapper).insert(captor.capture());
        FormData saved = captor.getValue();
        assertThat(saved.getFormDefId()).isEqualTo(10L);
        assertThat(saved.getFormDefVersion()).isEqualTo(3);
        assertThat(saved.getStatus()).isEqualTo("DRAFT");
        assertThat(saved.getCreatedBy()).isEqualTo(7L);
        assertThat(objectMapper.readTree(saved.getData()).path("days").asInt()).isEqualTo(2);
    }

    @Test
    void updateRequiresDraftOwnerAndPublishedTemplate() throws Exception {
        Mockito.when(formDataMapper.selectById(101L)).thenReturn(draft(101L, 7L, "DRAFT"));
        Mockito.when(formDefinitionService.getById(10L)).thenReturn(form("leave", "PUBLISHED"));
        JsonNode data = objectMapper.createObjectNode().put("days", 5);

        FormData updated = service.update(101L, data, 7L);

        assertThat(updated.getId()).isEqualTo(101L);
        assertThat(objectMapper.readTree(updated.getData()).path("days").asInt()).isEqualTo(5);
        Mockito.verify(formDataMapper).updateById(updated);
    }

    @Test
    void updateRejectsSecondUser() {
        Mockito.when(formDataMapper.selectById(101L)).thenReturn(draft(101L, 7L, "DRAFT"));

        assertThatThrownBy(() -> service.update(101L, objectMapper.createObjectNode(), 8L))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void updateRejectsUnpublishedTemplateButGetRemainsReadable() {
        Mockito.when(formDataMapper.selectById(101L)).thenReturn(draft(101L, 7L, "DRAFT"));
        Mockito.when(formDefinitionService.getById(10L)).thenReturn(form("leave", "DEPRECATED"));

        assertThatThrownBy(() -> service.update(101L, objectMapper.createObjectNode(), 7L))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("Form not published");

        MobileDraftDto dto = service.get(101L, 7L);
        assertThat(dto.readOnly()).isTrue();
        assertThat(dto.formCode()).isEqualTo("leave");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listReturnsOnlyOwnedDraftsWithReadOnlyFlag() {
        Mockito.when(formDataMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(draft(101L, 7L, "DRAFT"), draft(102L, 7L, "DRAFT")));
        Mockito.when(formDefinitionService.getById(10L)).thenReturn(form("leave", "PUBLISHED"));

        List<MobileDraftDto> drafts = service.list(7L);

        assertThat(drafts).hasSize(2);
        assertThat(drafts).allSatisfy(draft -> assertThat(draft.readOnly()).isFalse());
        assertThat(drafts.get(0).schema().get(1).path("id").asText()).isEqualTo("days");
        ArgumentCaptor<QueryWrapper<FormData>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        Mockito.verify(formDataMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment().toUpperCase()).contains("CREATED_BY");
        assertThat(captor.getValue().getSqlSegment().toUpperCase()).contains("STATUS");
    }

    @Test
    void deleteRequiresDraftOwner() {
        Mockito.when(formDataMapper.selectById(101L)).thenReturn(draft(101L, 7L, "DRAFT"));

        service.delete(101L, 7L);

        Mockito.verify(formDataMapper).deleteById(101L);
        assertThatThrownBy(() -> service.delete(101L, 8L))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deleteAfterSubmitDeletesOnlyOwnedDraft() {
        Mockito.when(formDataMapper.selectById(101L)).thenReturn(draft(101L, 7L, "DRAFT"));

        service.deleteAfterSubmit(101L, 7L);

        Mockito.verify(formDataMapper).deleteById(101L);
    }

    private static FormDefinition form(String code, String status) {
        FormDefinition form = new FormDefinition();
        form.setId(10L);
        form.setCode(code);
        form.setName("请假申请");
        form.setVersion(3);
        form.setSchema("[{\"id\":\"reason\",\"type\":\"text\"},{\"id\":\"days\",\"type\":\"number\"}]");
        form.setStatus(status);
        return form;
    }

    private static FormData draft(Long id, Long createdBy, String status) {
        FormData draft = new FormData();
        draft.setId(id);
        draft.setFormDefId(10L);
        draft.setFormDefVersion(3);
        draft.setData("{\"days\":1}");
        draft.setStatus(status);
        draft.setCreatedBy(createdBy);
        return draft;
    }
}
