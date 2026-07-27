package com.antflow.form.runtime;

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
        var fd2 = new FormData();
        fd2.setFormDefId(fd.getId());
        fd2.setFormDefVersion(fd.getVersion());
        fd2.setData(writeJson(data));
        fd2.setStatus(status == null ? "SUBMITTED" : status);
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
