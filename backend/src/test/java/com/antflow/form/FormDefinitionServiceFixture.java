package com.antflow.form;

import com.antflow.authz.FormGrantService;
import com.antflow.form.schema.FormFieldValueValidator;
import com.antflow.form.schema.FormMatrixRules;
import com.antflow.form.schema.FormSchemaJson;
import com.antflow.form.schema.FormSchemaParser;
import com.antflow.form.schema.FormSchemaValidator;
import com.antflow.form.schema.FormStarterCanonicalizer;
import com.antflow.form.schema.FormStarterProjector;
import com.antflow.form.schema.FormSubmissionValidator;
import com.antflow.form.schema.FormVisibility;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.Mockito;

/**
 * 单测里的 {@link FormDefinitionService} 装配。
 *
 * <p>schema 那组协作者是纯函数、无状态，**用真实实现**而不是 mock：它们的组合行为
 * （解析 → 可见性 → 校验/投影）正是这些测试要断言的东西，mock 掉等于只测了门面的转发。
 * 只有"要查库/要外部服务"的依赖才给 mock 或 {@code null}（未装配时门面原本就有 null 守卫）。
 */
public final class FormDefinitionServiceFixture {

    private FormDefinitionServiceFixture() { }

    public static FormDefinitionService service(FormDefinitionMapper mapper, ObjectMapper json) {
        return service(mapper, json, Mockito.mock(FormGrantService.class));
    }

    public static FormDefinitionService service(FormDefinitionMapper mapper, ObjectMapper json,
                                                FormGrantService grants) {
        var matrix = new FormMatrixRules(json);
        var parser = new FormSchemaParser(json, matrix);
        var schemaJson = new FormSchemaJson(json);
        var visibility = new FormVisibility(json);
        var projector = new FormStarterProjector(schemaJson, parser, visibility, json);
        // 叶子值校验是无状态纯函数，直接 new；它只被提交校验用，不进门面。
        var fieldValues = new FormFieldValueValidator();
        var submission = new FormSubmissionValidator(parser, schemaJson, visibility, matrix, fieldValues, null, json);
        // 发布期校验需要 OptionRuntimeService；单测里没有外部选项源，传 null 走"未装配"分支。
        var schemaValidator = new FormSchemaValidator(parser, null, json);
        var canonicalizer = new FormStarterCanonicalizer(
            schemaJson, parser, projector, visibility, submission, json);
        return new FormDefinitionService(mapper, json, schemaJson, parser, schemaValidator,
            submission, canonicalizer, projector, grants);
    }
}
