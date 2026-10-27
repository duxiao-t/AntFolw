package com.antflow.mobile.workflow;

import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises package-private requests through the real services in the PostgreSQL test. */
public final class ReworkValidationFixture {
    public static void previewAndResubmit(MobileWorkflowService workflow,
                                         ApprovalPreviewService previews, String code,
                                         long taskId, long userId, JsonNode revised) {
        var preview = previews.preview(code,
            new ApprovalPreviewRequest(revised, Map.of(), taskId), userId);
        assertThat(preview.nodes()).hasSize(1);
        JsonNode invalid = revised.deepCopy();
        // 可编辑字段给一个当前类型（select）不合法的值：布尔。曾用字符串，那是 switch 时代的写法。
        ((ObjectNode) invalid).put("editable", true);
        assertThatThrownBy(() -> previews.preview(code,
            new ApprovalPreviewRequest(invalid, Map.of(), taskId), userId))
            .isInstanceOfSatisfying(BizException.class,
                error -> assertThat(error.getCode()).isEqualTo("FORM_DATA_INVALID"));
        assertThatThrownBy(() -> workflow.resubmitRework(taskId,
            new ReworkTaskRequest(invalid, List.of()), userId))
            .isInstanceOfSatisfying(BizException.class,
                error -> assertThat(error.getCode()).isEqualTo("FORM_DATA_INVALID"));
        workflow.resubmitRework(taskId, new ReworkTaskRequest(revised, List.of()), userId);
    }
}
