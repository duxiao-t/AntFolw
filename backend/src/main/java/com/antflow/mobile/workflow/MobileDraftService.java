package com.antflow.mobile.workflow;

import com.antflow.engine.BizException;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.runtime.FormData;
import com.antflow.form.runtime.FormDataMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MobileDraftService {
    private static final String DRAFT_STATUS = "DRAFT";
    private static final String PUBLISHED_STATUS = "PUBLISHED";

    private final FormDataMapper formDataMapper;
    private final FormDefinitionService formDefinitionService;
    private final ObjectMapper objectMapper;

    @Transactional(rollbackFor = Exception.class)
    public Long create(String formCode, JsonNode data, long userId) {
        FormDefinition formDefinition = requirePublishedForm(formCode);
        FormData draft = new FormData();
        draft.setFormDefId(formDefinition.getId());
        draft.setFormDefVersion(formDefinition.getVersion());
        draft.setData(writeJson(data));
        draft.setStatus(DRAFT_STATUS);
        draft.setCreatedBy(userId);
        draft.setUpdatedAt(OffsetDateTime.now());
        formDataMapper.insert(draft);
        return draft.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public FormData update(long draftId, JsonNode data, long userId) {
        FormData draft = requireOwnedDraft(draftId, userId);
        requirePublishedForm(draft.getFormDefId());
        draft.setData(writeJson(data));
        draft.setUpdatedAt(OffsetDateTime.now());
        formDataMapper.updateById(draft);
        return draft;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(long draftId, long userId) {
        requireOwnedDraft(draftId, userId);
        formDataMapper.deleteById(draftId);
    }

    public List<MobileDraftDto> list(long userId) {
        List<FormData> drafts = formDataMapper.selectList(new QueryWrapper<FormData>()
            .eq("created_by", userId)
            .eq("status", DRAFT_STATUS)
            .orderByDesc("updated_at"));
        return drafts.stream().map(this::toDto).toList();
    }

    public MobileDraftDto get(long draftId, long userId) {
        return toDto(requireOwnedDraft(draftId, userId));
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteAfterSubmit(long draftId, long userId) {
        delete(draftId, userId);
    }

    private FormData requireOwnedDraft(long draftId, long userId) {
        FormData draft = formDataMapper.selectById(draftId);
        if (draft == null) {
            throw new BizException("DRAFT_NOT_FOUND", "draft not found");
        }
        if (!DRAFT_STATUS.equals(draft.getStatus())) {
            throw new BizException("NOT_DRAFT", "draft is not editable");
        }
        if (!Objects.equals(draft.getCreatedBy(), userId)) {
            throw new AccessDeniedException("draft belongs to another user");
        }
        return draft;
    }

    private FormDefinition requirePublishedForm(String formCode) {
        FormDefinition formDefinition = formDefinitionService.getByCode(formCode);
        if (formDefinition == null || !PUBLISHED_STATUS.equals(formDefinition.getStatus())) {
            throw new BizException("FORM_NOT_PUBLISHED", "Form not published: " + formCode);
        }
        return formDefinition;
    }

    private FormDefinition requirePublishedForm(Long formDefId) {
        FormDefinition formDefinition = formDefinitionService.getById(formDefId);
        if (formDefinition == null || !PUBLISHED_STATUS.equals(formDefinition.getStatus())) {
            throw new BizException("FORM_NOT_PUBLISHED", "Form not published: " + formDefId);
        }
        return formDefinition;
    }

    private MobileDraftDto toDto(FormData draft) {
        FormDefinition formDefinition = formDefinitionService.getById(draft.getFormDefId());
        boolean readOnly = formDefinition == null
            || !PUBLISHED_STATUS.equals(formDefinition.getStatus());
        return new MobileDraftDto(
            draft.getId(),
            draft.getFormDefId(),
            formDefinition == null ? null : formDefinition.getCode(),
            formDefinition == null ? null : formDefinition.getName(),
            draft.getFormDefVersion(),
            readJsonObject(draft.getData()),
            readOnly,
            draft.getCreatedAt(),
            draft.getUpdatedAt()
        );
    }

    private String writeJson(JsonNode data) {
        try {
            return objectMapper.writeValueAsString(data == null
                ? objectMapper.createObjectNode() : data);
        } catch (JsonProcessingException exception) {
            throw new BizException("BAD_JSON", exception.getMessage());
        }
    }

    private JsonNode readJsonObject(String value) {
        if (value == null || value.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new BizException("BAD_JSON", exception.getMessage());
        }
    }
}
