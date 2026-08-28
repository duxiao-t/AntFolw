package com.antflow.form.runtime;

import com.antflow.common.FormalNumberService;
import com.antflow.authz.AuthorizationService;
import com.antflow.engine.BizException;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FormDataService {
    private final FormDataMapper mapper;
    private final FormDefinitionService formDefinitionService;
    private final ObjectMapper json;
    private final FormalNumberService formalNumberService;
    private final AuthorizationService authorizationService;

    /**
     * MVP demo — independent submission (DRAFT or SUBMITTED) outside the workflow engine.
     * Production: process instances must flow through {@code engine.start(...)}.
     */
    @Transactional
    public Long submit(String formCode, String status, Object data, Long userId) {
        FormDefinition fd = formDefinitionService.getByCode(formCode);
        if (fd == null || !"PUBLISHED".equals(fd.getStatus())) {
            throw new BizException("FORM_NOT_PUBLISHED", "Form not published: " + formCode);
        }
        formDefinitionService.validateSubmission(fd.getSchema(), data);
        String normalizedStatus = status == null ? "SUBMITTED" : status;
        Object storedData = "DRAFT".equals(normalizedStatus)
            ? data
            : formDefinitionService.filterVisibleSubmission(fd.getSchema(), data);
        var fd2 = new FormData();
        fd2.setFormDefId(fd.getId());
        fd2.setFormDefVersion(fd.getVersion());
        if (!"DRAFT".equals(normalizedStatus)) {
            fd2.setBusinessNo(formalNumberService.businessNo());
        }
        fd2.setData(writeJson(storedData));
        fd2.setStatus(normalizedStatus);
        fd2.setCreatedBy(userId);
        mapper.insert(fd2);
        return fd2.getId();
    }

    public List<FormData> mySubmissions(Long userId, String formCode) {
        var q = new QueryWrapper<FormData>().eq("created_by", userId);
        if (formCode != null) {
            var fd = formDefinitionService.getByCode(formCode);
            if (fd != null) q.eq("form_def_id", fd.getId());
        }
        return mapper.selectList(q);
    }

    public Page<FormData> adminPage(long page, long size, Long formDefId,
                                    String status, Long createdBy) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), 100);
        var q = new QueryWrapper<FormData>();
        if (formDefId != null) q.eq("form_def_id", formDefId);
        if (status != null && !status.isBlank()) q.eq("status", status);
        if (createdBy != null) q.eq("created_by", createdBy);
        q.orderByDesc("created_at").orderByDesc("id");
        return mapper.selectPage(Page.of(safePage, safeSize), q);
    }

    public Page<FormData> authorizedPage(long page, long size, Long formDefId,
                                         String status, Long createdBy,
                                         long userId, boolean admin) {
        if (admin) {
            return adminPage(page, size, formDefId, status, createdBy);
        }
        var q = new QueryWrapper<FormData>();
        if (formDefId != null) q.eq("form_def_id", formDefId);
        if (status != null && !status.isBlank()) q.eq("status", status);
        if (createdBy != null) q.eq("created_by", createdBy);
        q.orderByDesc("created_at").orderByDesc("id");
        List<FormData> readable = mapper.selectList(q).stream()
            .filter(data -> authorizationService.canReadFormData(data.getId(), userId))
            .toList();
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), 100);
        int from = (int) Math.min((safePage - 1) * safeSize, readable.size());
        int to = (int) Math.min(from + safeSize, readable.size());
        Page<FormData> result = Page.of(safePage, safeSize, readable.size());
        result.setRecords(readable.subList(from, to));
        return result;
    }

    public FormData getById(Long id) {
        FormData data = mapper.selectById(id);
        if (data == null) {
            throw new BizException("FORM_DATA_NOT_FOUND", "Form data not found: " + id);
        }
        return data;
    }

    private String writeJson(Object o) {
        try { return json.writeValueAsString(o); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("BAD_JSON", e.getMessage());
        }
    }
}
