package com.antflow.form;

import com.antflow.authz.FormGrantService;
import com.antflow.engine.BizException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SubmissionValidationFixtureTest {
    @TestFactory
    Stream<DynamicTest> clientsAndServerShareSubmissionExpectations() throws Exception {
        ObjectMapper json = new ObjectMapper();
        FormDefinitionService service = new FormDefinitionService(
            mock(FormDefinitionMapper.class), json, mock(FormGrantService.class));
        var fixture = json.readTree(Files.readString(
            Path.of("../fixtures/field-semantics/validation.json")));
        return java.util.stream.StreamSupport.stream(fixture.path("cases").spliterator(), false)
            .map(sample -> DynamicTest.dynamicTest(sample.path("id").asText(), () -> {
                Map<String, Object> values = json.convertValue(sample.path("values"),
                    new TypeReference<>() { });
                var action = (org.assertj.core.api.ThrowableAssert.ThrowingCallable) () ->
                    service.validateSubmission(sample.path("schema").toString(), values);
                if (sample.path("valid").asBoolean()) {
                    assertThatCode(action).doesNotThrowAnyException();
                } else {
                    assertThatThrownBy(action).isInstanceOf(BizException.class);
                }
            }));
    }
}
