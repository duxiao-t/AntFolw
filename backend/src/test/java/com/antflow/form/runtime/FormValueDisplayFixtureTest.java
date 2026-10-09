package com.antflow.form.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「值 → 显示文本」口径的**共用 fixture** 的 Java 侧：`fixtures/field-semantics/display-text.json`。
 *
 * <p>同一份文件里还写着 SQL 侧（`v_form_ledger` 的 `value_label` / `value_text`）的期望，由
 * `PostgresTransactionalIntegrityIntegrationTest` 负责断言——两层对着同一份期望，口径漂了才有人喊。
 *
 * <p>期望值来自各层**真实契约**，不取自实现：台账是脱敏投影（自由文本两列都是 NULL），
 * 不是显示渲染器，这一点在 fixture 的 note 里写明了。
 */
class FormValueDisplayFixtureTest {

    private static final Path FIXTURE =
        Path.of("..", "fixtures", "field-semantics", "display-text.json");
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void javaSideMatchesTheSharedDisplayFixture() throws IOException {
        JsonNode fixture = json.readTree(Files.readString(FIXTURE, StandardCharsets.UTF_8));
        JsonNode cases = fixture.path("cases");
        assertThat(cases.isArray()).as("fixture 形状变了").isTrue();
        assertThat(cases).isNotEmpty();

        int asserted = 0;
        for (JsonNode testCase : cases) {
            JsonNode expect = testCase.path("expect");
            if (!expect.has("java")) continue; // 字段整条缺失那条只归 SQL 断言
            String id = testCase.path("id").asText();
            String fieldId = testCase.path("fieldId").asText();
            JsonNode data = testCase.path("data");
            JsonNode value = data.get(fieldId);

            FormValueDisplay display = new FormValueDisplay(
                json, testCase.path("schema"), List.of(data));

            assertThat(display.cell(fieldId, value))
                .as("java 侧 cell() 不符：%s（%s）", id, testCase.path("intent").asText())
                .isEqualTo(expect.path("java").asText());
            asserted++;
        }
        assertThat(asserted).as("一条 java 期望都没断到，fixture 或过滤器写错了").isGreaterThanOrEqualTo(5);
    }

    /** 「数据里没有这个字段」的 Java 侧：它不该出现在 values() 里（台账那边对应"没有行"）。 */
    @Test
    void aMissingFieldIsAbsentFromValues() throws IOException {
        JsonNode fixture = json.readTree(Files.readString(FIXTURE, StandardCharsets.UTF_8));
        JsonNode testCase = null;
        for (JsonNode candidate : fixture.path("cases")) {
            if (!candidate.path("expect").has("java")) testCase = candidate;
        }
        assertThat(testCase).as("fixture 里应有且仅有一条不带 java 期望的用例").isNotNull();

        String fieldId = testCase.path("fieldId").asText();
        JsonNode data = testCase.path("data");
        FormValueDisplay display =
            new FormValueDisplay(json, testCase.path("schema"), List.of(data));

        assertThat(display.values(data))
            .extracting(FormData.FieldValue::fieldId)
            .doesNotContain(fieldId);
    }
}
