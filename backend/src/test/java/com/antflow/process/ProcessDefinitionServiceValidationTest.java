package com.antflow.process;

import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates the recursive process-tree invariant introduced by the
 * 钉钉式流程改造 (Task 3). No Postgres / Spring context required —
 * we construct the service directly with a null mapper because
 * {@code validateProcessTree} is pure.
 */
class ProcessDefinitionServiceValidationTest {

    private final ProcessDefinitionService service =
        new ProcessDefinitionService(null, null, new ObjectMapper());

    @Test void validate_rejects_approval_without_assignee() {
        String tree = """
            {"id":"root","type":"ROOT","props":{},"children":{"id":"a1","type":"APPROVAL",
              "props":{"assignedType":"ASSIGN_USER","assignedUser":[]}}}""";
        assertThatThrownBy(() -> service.validateProcessTree(tree))
            .isInstanceOf(BizException.class)
            .matches(e -> "BAD_FLOW".equals(((BizException) e).getCode()));
    }

    @Test void validate_rejects_conditions_without_default_branch() {
        String tree = """
            {"id":"root","type":"ROOT","props":{},"children":{"id":"c1","type":"CONDITIONS",
              "props":{},"branchs":[
                {"id":"b1","type":"CONDITION","props":{"isDefault":false,"groups":[]},
                 "children":null}
              ]}}""";
        assertThatThrownBy(() -> service.validateProcessTree(tree))
            .isInstanceOf(BizException.class)
            .matches(e -> "BAD_FLOW".equals(((BizException) e).getCode()));
    }

    @Test void validate_accepts_wellformed_tree() {
        String tree = """
            {"id":"root","type":"ROOT","props":{},"children":{"id":"a1","type":"APPROVAL",
              "props":{"assignedType":"ASSIGN_USER","assignedUser":[1]},"children":null}}""";
        assertThatCode(() -> service.validateProcessTree(tree)).doesNotThrowAnyException();
    }
}
