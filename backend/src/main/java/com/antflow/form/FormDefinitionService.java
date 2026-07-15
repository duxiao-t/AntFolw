package com.antflow.form;

import com.antflow.engine.BizException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FormDefinitionService {

    private final FormDefinitionMapper mapper;
    private final ObjectMapper json;

    public FormDefinition getByCode(String code) {
        return mapper.selectOne(new QueryWrapper<FormDefinition>().eq("code", code));
    }
    public FormDefinition getById(Long id) { return mapper.selectById(id); }

    @Transactional
    public FormDefinition saveDraft(Long id, String code, String name,
                                    Object schema, Object settings, Long userId) {
        FormDefinition fd;
        if (id == null) {
            if (mapper.selectCount(new QueryWrapper<FormDefinition>().eq("code", code)) > 0) {
                throw new BizException("CODE_EXISTS", "form code already exists: " + code);
            }
            fd = new FormDefinition();
            fd.setCode(code);
            fd.setName(name);
            fd.setVersion(1);
            fd.setSchema(writeJson(schema));
            fd.setSettings(writeJson(settings == null ? java.util.Map.of() : settings));
            fd.setStatus("DRAFT");
            fd.setCreatedBy(userId);
            mapper.insert(fd);
        } else {
            fd = mapper.selectById(id);
            if (!"DRAFT".equals(fd.getStatus())) {
                throw new BizException("NOT_DRAFT", "Only DRAFT form_definitions can be edited");
            }
            fd.setName(name);
            fd.setSchema(writeJson(schema));
            fd.setSettings(writeJson(settings));
            mapper.updateById(fd);
        }
        return fd;
    }

    @Transactional
    public FormDefinition publish(Long id) {
        FormDefinition fd = mapper.selectById(id);
        if (!"DRAFT".equals(fd.getStatus())) return fd;
        validateSchema(fd.getSchema());
        fd.setStatus("PUBLISHED");
        fd.setVersion(fd.getVersion() + 1);
        mapper.updateById(fd);
        return fd;
    }

    private String writeJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("BAD_JSON", e.getMessage());
        }
    }

    private void validateSchema(String s) {
        if (s == null) {
            throw new BizException("BAD_SCHEMA", "schema must be a non-empty array");
        }
        try {
            var arr = json.readTree(s);
            if (!arr.isArray() || arr.size() == 0) {
                throw new BizException("BAD_SCHEMA", "schema must be a non-empty array");
            }
        } catch (BizException e) {
            throw e;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("BAD_SCHEMA_JSON", e.getMessage());
        }
    }
}
