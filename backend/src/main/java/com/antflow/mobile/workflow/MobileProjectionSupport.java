package com.antflow.mobile.workflow;

import com.antflow.engine.BizException;
import com.antflow.engine.tree.ProcessTreeNav;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 移动端各投影协作者共用的读取底座：JSON 树解析（含错误码）、节点显示名。
 *
 * <p>抽出来的理由不是"少几行"，而是这几件事必须只有一份口径：
 * <ul>
 *   <li>{@code BAD_FLOW_JSON} / {@code BAD_SCHEMA_JSON} / {@code BAD_JSON} 三个错误码
 *       原先在 6 个方法里各写一遍，复制之后必然有人写错其中一个，前端只会看到某条路径的报错变了；</li>
 *   <li>节点显示名统一走 {@link ProcessTreeNav}：桌面 / 移动 / 监控同一套口径
 *       （含 {@code __rework__} 哨兵节点），任何一份本地实现都会在某次改文案后漂移。</li>
 * </ul>
 *
 * <p>刻意**不做成 Spring bean**：它只用调用方已有的 ObjectMapper，做成 bean 只会让单测里
 * 出现"两套 ObjectMapper"（配置不同时，读出来的 {@link JsonNode} 行为也不同）。
 */
final class MobileProjectionSupport {
    private final ObjectMapper objectMapper;

    MobileProjectionSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    JsonNode readJsonObject(String value, String code) {
        if (value == null || value.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new BizException(code, exception.getMessage());
        }
    }

    JsonNode readJsonArray(String value, String code) {
        if (value == null || value.isBlank()) {
            return objectMapper.createArrayNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new BizException(code, exception.getMessage());
        }
    }

    String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BizException("BAD_JSON", exception.getMessage());
        }
    }

    /** 节点显示名统一走 {@link ProcessTreeNav}：桌面/移动/监控同一套口径（含 `__rework__` 哨兵）。 */
    static String nodeName(JsonNode root, String nodeId) {
        return ProcessTreeNav.displayName(root, nodeId);
    }

    static String nodeName(JsonNode node) {
        return ProcessTreeNav.displayName(node);
    }
}
