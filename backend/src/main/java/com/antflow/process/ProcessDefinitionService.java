package com.antflow.process;

import com.antflow.engine.BizException;
import com.antflow.form.FormDefinitionService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
    public ProcessDefinition saveOrUpdateDraft(Long id, Long formDefId, Object process,
                                                Long userId) {
        ProcessDefinition pd;
        if (id == null) {
            if (mapper.selectCount(new QueryWrapper<ProcessDefinition>().eq("form_def_id", formDefId)) > 0) {
                throw new BizException("PROCESS_EXISTS",
                    "Process for this form already exists; edit instead");
            }
            pd = new ProcessDefinition();
            pd.setFormDefId(formDefId);
            pd.setVersion(1);
            pd.setProcess(writeJson(process));
            pd.setStatus("DRAFT");
            pd.setCreatedBy(userId);
            mapper.insert(pd);
        } else {
            pd = mapper.selectById(id);
            if (!"DRAFT".equals(pd.getStatus())) {
                throw new BizException("NOT_DRAFT", "Only DRAFT process can be edited");
            }
            pd.setProcess(writeJson(process));
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

        validateProcessTree(pd.getProcess());

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

    public ProcessDefinition findByForm(Long formDefId) {
        return mapper.selectOne(new QueryWrapper<ProcessDefinition>()
            .eq("form_def_id", formDefId)
            .orderByDesc("id").last("LIMIT 1"));
    }

    public List<ProcessDefinition> list() {
        return mapper.selectList(null);
    }

    /** Tree validation: ROOT is the unique root; APPROVAL has assignees configured;
     *  CONDITIONS has at least 1 branch including a default branch; node type is known. */
    void validateProcessTree(String processJson) {
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                json.readTree(processJson == null ? "{}" : processJson);
            if (!"ROOT".equals(root.path("type").asText())) {
                throw new BizException("BAD_FLOW", "流程必须以 ROOT 节点开始");
            }
            walk(root);
        } catch (BizException e) {
            throw e;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("BAD_FLOW_JSON", e.getMessage());
        }
    }

    private void walk(com.fasterxml.jackson.databind.JsonNode n) {
        if (n == null || n.isNull() || !n.has("id")) return;
        String type = n.path("type").asText();
        switch (type) {
            case "ROOT", "CC", "EMPTY" -> {}
            case "APPROVAL" -> validateApproval(n);
            case "CONDITIONS" -> {
                com.fasterxml.jackson.databind.JsonNode branchs = n.path("branchs");
                if (!branchs.isArray() || branchs.size() < 1) {
                    throw new BizException("BAD_FLOW", "条件分支至少需要 1 个分支");
                }
                boolean hasDefault = false;
                for (com.fasterxml.jackson.databind.JsonNode b : branchs) {
                    if (b.path("props").path("isDefault").asBoolean(false)) hasDefault = true;
                    walk(b.path("children"));
                }
                if (!hasDefault) {
                    throw new BizException("BAD_FLOW", "条件分支必须包含一个默认分支");
                }
            }
            case "CONDITION" -> {}
            default -> throw new BizException("BAD_NODE_TYPE", "未知节点类型: " + type);
        }
        walk(n.path("children"));
    }

    private void validateApproval(com.fasterxml.jackson.databind.JsonNode n) {
        com.fasterxml.jackson.databind.JsonNode p = n.path("props");
        String at = p.path("assignedType").asText();
        boolean empty = switch (at) {
            case "ASSIGN_USER" -> p.path("assignedUser").size() == 0;
            case "ROLE" -> p.path("role").size() == 0;
            case "LEADER", "SELF", "SELF_SELECT" -> false;
            default -> true;
        };
        if (empty) throw new BizException("BAD_FLOW", "审批节点 " + n.path("id").asText() + " 未配置审批人");
    }

    private String writeJson(Object o) {
        try { return json.writeValueAsString(o); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("BAD_JSON", e.getMessage());
        }
    }
}
