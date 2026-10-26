package com.antflow.form.correction;

import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 字段策略的边界测试。
 *
 * <p>这层是"改了不该改的字段会导致数据自相矛盾"的唯一防线，所以要盯住三件事：
 * 条件分支字段必须挡住、单号字段必须挡住、配置解析不了时必须拒绝（而不是放行）。
 */
class CorrectionFieldPolicyTest {

    private static final String SCHEMA = """
        [{"id":"amount","type":"money"},{"id":"vendor","type":"text"},
         {"id":"kind","type":"radio"},{"id":"remark","type":"text"}]
        """;

    private CorrectionFieldPolicy policy(String processJson, String settingsJson) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // 条件字段走一条 jdbc.query(..., ResultSetExtractor, formDefId) 调用
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class),
            anyLong())).thenReturn(processJson);
        return new CorrectionFieldPolicy(jdbc, new ObjectMapper());
    }

    @Test
    void 没有条件分支也没有单号规则时全部字段可改() {
        CorrectionFieldPolicy.Policy result = policy(null, "{}").compute(1L, "{}", SCHEMA);

        assertThat(result.allowed()).containsExactlyInAnyOrder("amount", "vendor", "kind", "remark");
        assertThat(result.blocked()).isEmpty();
    }

    @Test
    void 条件分支引用的字段被挡住_含树形递归() {
        // 流程是树形结构：条件挂在 children 下的 props 里
        String process = """
            {"children":[
              {"type":"CONDITION","props":{"groups":[
                 {"groupType":"AND","conditions":[{"field":"amount","operator":">=","value":5000}]}
              ]}},
              {"children":[{"type":"CONDITION","props":{"groups":[
                 {"conditions":[{"field":"kind","operator":"=","value":"A"}]}
              ]}}]}
            ]}
            """;

        CorrectionFieldPolicy.Policy result = policy(process, "{}").compute(1L, "{}", SCHEMA);

        assertThat(result.blocked())
            .containsEntry("amount", CorrectionFieldPolicy.REASON_CONDITION)
            .containsEntry("kind", CorrectionFieldPolicy.REASON_CONDITION);
        assertThat(result.allowed()).containsExactlyInAnyOrder("vendor", "remark");
    }

    @Test
    void 单号规则引用的字段被挡住() {
        String settings = """
            {"businessNumber":{"parts":[
               {"type":"LITERAL","value":"PO"},
               {"type":"FIELD","fieldId":"vendor"},
               {"type":"SEQUENCE","width":4}
            ]}}
            """;

        CorrectionFieldPolicy.Policy result = policy(null, settings).compute(1L, settings, SCHEMA);

        assertThat(result.blocked()).containsEntry("vendor",
            CorrectionFieldPolicy.REASON_BUSINESS_NUMBER);
        assertThat(result.allowed()).containsExactlyInAnyOrder("amount", "kind", "remark");
    }

    @Test
    void 同一字段同时被条件与单号引用时只报一次且原因不漂移() {
        String process = """
            {"children":[{"type":"CONDITION","props":{"groups":[
               {"conditions":[{"field":"vendor"}]}]}}]}
            """;
        String settings = """
            {"businessNumber":{"parts":[{"type":"FIELD","fieldId":"vendor"}]}}
            """;

        CorrectionFieldPolicy.Policy result = policy(process, settings).compute(1L, settings, SCHEMA);

        assertThat(result.blocked()).hasSize(1).containsEntry("vendor",
            CorrectionFieldPolicy.REASON_CONDITION);
    }

    @Test
    void schema解析失败时fail_closed而不是放行() {
        assertThatThrownBy(() -> policy(null, "{}").compute(1L, "{}", "not-json"))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("拒绝修正");
    }

    @Test
    void schema不是数组时也拒绝() {
        assertThatThrownBy(() -> policy(null, "{}").compute(1L, "{}", "{\"id\":\"x\"}"))
            .isInstanceOf(BizException.class);
    }

    @Test
    void schema里不存在的字段不会出现在allowed里() {
        CorrectionFieldPolicy.Policy result = policy(null, "{}")
            .compute(1L, "{}", "[{\"id\":\"amount\",\"type\":\"money\"}]");

        assertThat(result.allowed()).containsExactly("amount");
        assertThat(result.allowed()).doesNotContain("vendor");
    }
}
