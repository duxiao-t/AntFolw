package com.antflow.engine.resolver;

import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Shared parsing for approval-node assignee configuration. */
public final class ApprovalAssigneeSpecs {
    private ApprovalAssigneeSpecs() {
    }

    public static AssigneeSpec from(JsonNode node, long starterId,
                                    Map<String, List<Long>> selfSelected) {
        JsonNode props = node.path("props");
        String type = props.path("assignedType").asText();
        return switch (type) {
            case "ASSIGN_USER" -> AssigneeSpec.of("ASSIGN_USER",
                readIds(props.path("assignedUser")));
            case "ROLE" -> AssigneeSpec.of("ROLE", readIds(props.path("role")));
            case "LEADER" -> new AssigneeSpec("LEADER", List.of(),
                props.path("leader").path("level").asInt(1), starterId, List.of());
            case "DIRECT_MANAGER" -> new AssigneeSpec("DIRECT_MANAGER", List.of(),
                props.path("manager").path("level").asInt(1), starterId, List.of());
            case "SELF" -> new AssigneeSpec("SELF", List.of(), 1, starterId, List.of());
            case "SELF_SELECT" -> selfSelect(node, starterId, selfSelected);
            default -> throw new IllegalArgumentException("未识别审批人类型: " + type);
        };
    }

    private static AssigneeSpec selfSelect(JsonNode node, long starterId,
                                           Map<String, List<Long>> selfSelected) {
        List<Long> selected = selfSelected == null ? List.of()
            : selfSelected.getOrDefault(node.path("id").asText(), List.of());
        long selectedCount = selected.stream().filter(Objects::nonNull).distinct().count();
        if (selectedCount == 0) {
            throw new BizException("SELF_SELECT_REQUIRED", "请选择审批人");
        }
        JsonNode multipleNode = node.path("props").path("selfSelect").path("multiple");
        boolean multiple = multipleNode.isBoolean() && multipleNode.asBoolean();
        if (!multiple && selectedCount != 1) {
            throw new BizException("SELF_SELECT_MULTIPLE_NOT_ALLOWED",
                "该审批节点只能选择一名审批人");
        }
        return new AssigneeSpec("SELF_SELECT", List.of(), 1, starterId, selected);
    }

    private static List<Long> readIds(JsonNode values) {
        List<Long> result = new ArrayList<>();
        if (!values.isArray()) return result;
        for (JsonNode value : values) {
            if (value.isNumber()) result.add(value.asLong());
            else if (value.isTextual()) {
                try {
                    result.add(Long.parseLong(value.asText()));
                } catch (NumberFormatException ignored) {
                    // Invalid ids are ignored consistently with historical flow snapshots.
                }
            }
        }
        return result;
    }
}
