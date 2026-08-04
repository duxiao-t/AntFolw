package com.antflow.form;

import com.antflow.engine.BizException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class FormDefinitionService {

    private final FormDefinitionMapper mapper;
    private final ObjectMapper json;

    private static final Set<String> STATUSES = Set.of("DRAFT", "PUBLISHED", "DEPRECATED");
    private static final Set<String> FIELD_TYPES = Set.of(
        "text", "textarea", "number", "money",
        "radio", "checkbox", "select", "multi_select", "search",
        "date", "date_range", "time", "switch",
        "image_upload", "video_upload", "file_upload", "checklist",
        "user_picker", "dept_picker",
        "description", "span_layout", "table_list"
    );
    private static final Set<String> CONTAINER_TYPES = Set.of("span_layout");

    public Page<FormDefinition> list(long page, long size, String keyword, String status) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), 100);
        var q = new QueryWrapper<FormDefinition>();
        if (keyword != null && !keyword.isBlank()) {
            q.and(w -> w.like("name", keyword).or().like("code", keyword));
        }
        if (status != null && !status.isBlank()) {
            validateStatus(status);
            q.eq("status", status);
        }
        q.orderByDesc("updated_at").orderByDesc("id");
        return mapper.selectPage(Page.of(safePage, safeSize), q);
    }

    public FormDefinition getByCode(String code) {
        return mapper.selectOne(new QueryWrapper<FormDefinition>().eq("code", code));
    }

    public FormDefinition getPublishedByCode(String code) {
        return mapper.selectOne(new QueryWrapper<FormDefinition>()
            .eq("code", code)
            .eq("status", "PUBLISHED"));
    }
    public FormDefinition getById(Long id) { return mapper.selectById(id); }

    @Transactional
    public FormDefinition saveDraft(Long id, String code, String name,
                                    String description, Object schema,
                                    Object settings, Long userId) {
        FormDefinition fd;
        if (id == null) {
            if (mapper.selectCount(new QueryWrapper<FormDefinition>().eq("code", code)) > 0) {
                throw new BizException("CODE_EXISTS", "form code already exists: " + code);
            }
            fd = new FormDefinition();
            fd.setCode(code);
            fd.setName(name);
            fd.setDescription(description);
            fd.setVersion(1);
            fd.setSchema(writeJson(schema));
            fd.setSettings(writeJson(settings == null ? java.util.Map.of() : settings));
            fd.setStatus("DRAFT");
            fd.setCreatedBy(userId);
            fd.setDeleted(0);
            mapper.insert(fd);
        } else {
            fd = mapper.selectById(id);
            if (fd == null) {
                throw new BizException("FORM_NOT_FOUND", "Form not found: " + id);
            }
            if ("DEPRECATED".equals(fd.getStatus())) {
                throw new BizException("NOT_DRAFT", "Only DRAFT form_definitions can be edited");
            }
            fd.setName(name);
            fd.setDescription(description);
            fd.setSchema(writeJson(schema));
            fd.setSettings(writeJson(settings));
            fd.setStatus("DRAFT");
            mapper.updateById(fd);
        }
        return fd;
    }

    @Transactional
    public FormDefinition update(Long id, String name, String description,
                                 String status, Object schema, Object settings) {
        FormDefinition fd = mapper.selectById(id);
        if (fd == null) {
            throw new BizException("FORM_NOT_FOUND", "Form not found: " + id);
        }
        if (name != null) fd.setName(name);
        if (description != null) fd.setDescription(description);
        if (status != null) {
            validateStatus(status);
            if ("PUBLISHED".equals(status) && !"PUBLISHED".equals(fd.getStatus())) {
                throw new BizException("USE_PUBLISH", "Use publish endpoint to publish a form");
            }
            fd.setStatus(status);
        }
        if (schema != null || settings != null) {
            if (!"DRAFT".equals(fd.getStatus())) {
                throw new BizException("NOT_DRAFT", "Only DRAFT form_definitions can change schema/settings");
            }
            if (schema != null) fd.setSchema(writeJson(schema));
            if (settings != null) fd.setSettings(writeJson(settings));
        }
        mapper.updateById(fd);
        return fd;
    }

    @Transactional
    public FormDefinition publish(Long id) {
        FormDefinition fd = mapper.selectById(id);
        if (fd == null) {
            throw new BizException("FORM_NOT_FOUND", "Form not found: " + id);
        }
        if (!"DRAFT".equals(fd.getStatus())) return fd;
        validateSchema(fd.getSchema());
        fd.setStatus("PUBLISHED");
        fd.setVersion(fd.getVersion() + 1);
        mapper.updateById(fd);
        return fd;
    }

    @Transactional
    public FormDefinition disable(Long id) {
        FormDefinition fd = mapper.selectById(id);
        if (fd == null) {
            throw new BizException("FORM_NOT_FOUND", "Form not found: " + id);
        }
        fd.setStatus("DEPRECATED");
        mapper.updateById(fd);
        return fd;
    }

    @Transactional
    public void softDelete(Long id) {
        FormDefinition fd = mapper.selectById(id);
        if (fd == null) {
            throw new BizException("FORM_NOT_FOUND", "Form not found: " + id);
        }
        mapper.deleteById(id);
    }

    public void validateSubmission(String schema, Object data) {
        var root = parseSchema(schema);
        Map<?, ?> values = data instanceof Map<?, ?> map ? map : Map.of();
        for (var node : root) {
            validateNodeValue(node, values);
        }
    }

    private String writeJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("BAD_JSON", e.getMessage());
        }
    }

    private void validateSchema(String s) {
        parseSchema(s);
    }

    private List<com.fasterxml.jackson.databind.JsonNode> parseSchema(String s) {
        if (s == null) {
            throw new BizException("BAD_SCHEMA", "schema must be a non-empty array");
        }
        try {
            var arr = json.readTree(s);
            if (!arr.isArray() || arr.size() == 0) {
                throw new BizException("BAD_SCHEMA", "schema must be a non-empty array");
            }
            var nodes = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
            arr.forEach(n -> {
                validateNode(n);
                nodes.add(n);
            });
            return nodes;
        } catch (BizException e) {
            throw e;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("BAD_SCHEMA_JSON", e.getMessage());
        }
    }

    private void validateNode(com.fasterxml.jackson.databind.JsonNode node) {
        String id = node.path("id").asText("");
        String type = node.path("type").asText("");
        if (id.isBlank()) {
            throw new BizException("BAD_SCHEMA", "node.id is required");
        }
        if (type.isBlank() || !FIELD_TYPES.contains(type)) {
            throw new BizException("BAD_SCHEMA", "unsupported field type: " + type);
        }
        var rules = node.path("rules");
        if (!rules.isMissingNode() && !rules.isObject()) {
            throw new BizException("BAD_SCHEMA", "node.rules must be an object");
        }
        var children = node.path("children");
        if (!children.isMissingNode()) {
            if (!children.isArray()) {
                throw new BizException("BAD_SCHEMA", "node.children must be an array");
            }
            children.forEach(this::validateNode);
        }
    }

    private void validateNodeValue(com.fasterxml.jackson.databind.JsonNode node, Map<?, ?> values) {
        if (CONTAINER_TYPES.contains(node.path("type").asText(""))) {
            var containerChildren = node.path("children");
            if (containerChildren.isArray()) {
                containerChildren.forEach(child -> validateNodeValue(child, values));
            }
            return;
        }
        Object value = values.get(node.path("id").asText());
        var props = node.path("props");
        var rules = node.path("rules");
        boolean required = rules.path("required").asBoolean(props.path("required").asBoolean(false));
        if (required && isEmpty(value)) {
            throw new BizException("FORM_DATA_INVALID", node.path("label").asText(node.path("id").asText()) + " is required");
        }
        int maxLength = rules.path("maxLength").asInt(props.path("maxLength").asInt(-1));
        if (maxLength > -1 && value instanceof String s && s.length() > maxLength) {
            throw new BizException("FORM_DATA_INVALID", node.path("label").asText(node.path("id").asText()) + " exceeds maxLength");
        }
        int minChecked = rules.path("minChecked").asInt(-1);
        int maxChecked = rules.path("maxChecked").asInt(props.path("maxSelected").asInt(-1));
        if (value instanceof List<?> list) {
            if (minChecked > -1 && list.size() < minChecked) {
                throw new BizException("FORM_DATA_INVALID", node.path("label").asText(node.path("id").asText()) + " below minChecked");
            }
            if (maxChecked > -1 && list.size() > maxChecked) {
                throw new BizException("FORM_DATA_INVALID", node.path("label").asText(node.path("id").asText()) + " exceeds maxChecked");
            }
        }
        var children = node.path("children");
        if (children.isArray()) {
            children.forEach(child -> validateNodeValue(child, values));
        }
    }

    private boolean isEmpty(Object value) {
        return value == null
            || (value instanceof String s && s.isBlank())
            || (value instanceof List<?> list && list.isEmpty())
            || (value instanceof Map<?, ?> map && map.isEmpty());
    }

    private void validateStatus(String status) {
        if (!STATUSES.contains(status)) {
            throw new BizException("BAD_STATUS", "Unsupported form status: " + status);
        }
    }
}