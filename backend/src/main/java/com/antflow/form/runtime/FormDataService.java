package com.antflow.form.runtime;

import com.antflow.engine.BizException;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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

    private String writeJson(Object o) {
        try { return json.writeValueAsString(o); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("BAD_JSON", e.getMessage());
        }
    }
}
