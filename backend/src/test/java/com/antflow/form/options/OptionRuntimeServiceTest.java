package com.antflow.form.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OptionRuntimeServiceTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void fixedPrefixStopsAfterConfiguredSteps() throws Exception {
        var cascade = json.readTree("""
            {"kind":"split","sourceColumn":"编码","split":{"kind":"fixed","lengths":[1,1,1]}}
            """);
        Map<String, String> row = new LinkedHashMap<>();
        row.put("编码", "JKL-NB892387348r");

        assertThat(OptionRuntimeService.depth(cascade)).isEqualTo(3);
        assertThat(OptionRuntimeService.parts(row, cascade)).containsExactly("J", "K", "L");
    }

    @Test
    void shortCodeIsExcludedInsteadOfCrashing() throws Exception {
        var cascade = json.readTree("""
            {"kind":"split","sourceColumn":"编码","split":{"kind":"fixed","lengths":[1,1,1]}}
            """);

        assertThat(OptionRuntimeService.parts(Map.of("编码", "JK"), cascade)).isEmpty();
    }

    @Test
    void columnCascadeUsesConfiguredOrder() throws Exception {
        var cascade = json.readTree("""
            {"kind":"columns","levelColumns":["大区","城市"]}
            """);

        assertThat(OptionRuntimeService.parts(Map.of("大区", "华东", "城市", "上海"), cascade))
            .containsExactly("华东", "上海");
        assertThat(OptionRuntimeService.parts(Map.of("大区", "华东"), cascade)).isEmpty();
    }

    @Test
    void invalidSplitRuleIsRejected() throws Exception {
        var cascade = json.readTree("""
            {"kind":"split","sourceColumn":"编码","split":{"kind":"fixed","lengths":[]}}
            """);

        assertThatThrownBy(() -> OptionRuntimeService.depth(cascade))
            .isInstanceOf(BizException.class);
    }
}
