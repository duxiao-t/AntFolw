package com.antflow.process;

import com.antflow.engine.BizException;
import com.antflow.form.FormDefinitionService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ProcessDefinitionService {
    private final ProcessDefinitionMapper mapper;
    private final FormDefinitionService formDefinitionService;
    private final ObjectMapper json;

    public ProcessDefinition getById(Long id) {
        return mapper.selectById(id);
    }

    @Transactional
    public ProcessDefinition saveOrUpdateDraft(Long id, Long formDefId, Object nodes,
                                                Object edges, Long userId) {
        ProcessDefinition pd;
        if (id == null) {
            if (mapper.selectCount(new QueryWrapper<ProcessDefinition>().eq("form_def_id", formDefId)) > 0) {
                throw new BizException("PROCESS_EXISTS",
                    "Process for this form already exists; edit instead");
            }
            pd = new ProcessDefinition();
            pd.setFormDefId(formDefId);
            pd.setVersion(1);
            pd.setNodes(writeJson(nodes));
            pd.setEdges(writeJson(edges));
            pd.setStatus("DRAFT");
            pd.setCreatedBy(userId);
            mapper.insert(pd);
        } else {
            pd = mapper.selectById(id);
            if (!"DRAFT".equals(pd.getStatus())) {
                throw new BizException("NOT_DRAFT", "Only DRAFT process can be edited");
            }
            pd.setNodes(writeJson(nodes));
            pd.setEdges(writeJson(edges));
            mapper.updateById(pd);
        }
        return pd;
    }

    @Transactional
    public ProcessDefinition publish(Long id) {
        ProcessDefinition pd = mapper.selectById(id);

        var fd = formDefinitionService.getById(pd.getFormDefId());
        if (!"PUBLISHED".equals(fd.getStatus())) {
            throw new BizException("FOR_FORM_NOT_PUBLISHED",
                "Associated form must be PUBLISHED before publishing the flow");
        }

        validateLinearFlow(pd.getNodes(), pd.getEdges());

        pd.setStatus("PUBLISHED");
        pd.setVersion(pd.getVersion() + 1);
        mapper.updateById(pd);
        return pd;
    }

    public ProcessDefinition latestPublishedForForm(Long formDefId) {
        return mapper.selectOne(new QueryWrapper<ProcessDefinition>()
            .eq("form_def_id", formDefId).eq("status", "PUBLISHED")
            .orderByDesc("version").last("LIMIT 1"));
    }

    public List<ProcessDefinition> list() {
        return mapper.selectList(null);
    }

    /** MVP linear-flow invariant — every non-end node has exactly 1 outgoing edge. */
    private void validateLinearFlow(String nodesJson, String edgesJson) {
        try {
            var nodes = json.readTree(nodesJson);
            var edges = json.readTree(edgesJson);

            Map<String, Long> outDegree = new HashMap<>();
            for (var e : edges) {
                String from = e.path("from").asText();
                outDegree.merge(from, 1L, Long::sum);
            }

            for (var n : nodes) {
                String type = n.path("type").asText();
                String id = n.path("id").asText();
                if ("end".equals(type)) continue;
                if ("start".equals(type) || "approval".equals(type)) {
                    if (outDegree.getOrDefault(id, 0L) != 1) {
                        throw new BizException("BAD_FLOW",
                            "Node " + id + " (" + type + ") must have exactly 1 outgoing edge in MVP (sequential flow only)");
                    }
                } else {
                    throw new BizException("BAD_NODE_TYPE", "Unknown node type: " + type);
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("BAD_FLOW_JSON", e.getMessage());
        }
    }

    private String writeJson(Object o) {
        try { return json.writeValueAsString(o); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("BAD_JSON", e.getMessage());
        }
    }
}
