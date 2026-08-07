package com.antflow.engine.handler;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 节点处理上下文 — Sprint 2 C2：handler 之间共用的运行时参数封装，
 * 避免 ProcessEngine 给每个 handler 都传 6 个参数。
 */
public record NodeContext(
        long starterId,
        JsonNode formData,
        Map<String, List<Long>> selfSelected,
        /** 上一节点 id（用于 ARRIVE 历史 fromNodeId） */
        String fromNodeId,
        /** 所属并行网关节点 id（并行分支内任务）；null 表示非并行 */
        String parallelId,
        /** 所属并行分支 id（并行分支内任务）；null 表示非并行 */
        String branchId
) {}
