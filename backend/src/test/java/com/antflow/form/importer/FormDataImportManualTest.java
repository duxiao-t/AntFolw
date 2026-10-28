package com.antflow.form.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.antflow.authz.AuthorizationService;
import com.antflow.common.BusinessNumberService;
import com.antflow.engine.BizException;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionMapper;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.FormDefinitionServiceFixture;
import com.antflow.form.importer.FormDataManualImport.ManualImportFailure;
import com.antflow.form.importer.ImportDtos.ImportOutcome;
import com.antflow.form.importer.ImportDtos.ManualImportRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 在线提交一条：落库形状、必填的字段级定位、业务单号幂等。
 *
 * <p>用**真实的校验链**（{@code filterVisibleSubmission → validateSubmission}），只 mock 数据库：
 * 这样测到的是"前端采集的原值能不能被后端接受"，而不是把校验也 mock 掉之后的自我满足。
 */
class FormDataImportManualTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long FORM_ID = 44L;
    private static final long COMPANY_ID = 1L;
    private static final long USER_ID = 7L;

    private JdbcTemplate jdbc;
    private FormDefinitionService forms;
    private FormImportKeyLookup keyLookup;
    private FormDataManualImport manual;

    @BeforeEach
    void setUp() {
        FormDefinition form = new FormDefinition();
        form.setId(FORM_ID);
        form.setCode("inspect");
        form.setName("点检单");
        form.setVersion(1);
        form.setStatus("PUBLISHED");
        form.setSchema(SCHEMA);

        FormDefinitionMapper mapper = mock(FormDefinitionMapper.class);
        when(mapper.selectById(FORM_ID)).thenReturn(form);
        forms = FormDefinitionServiceFixture.service(mapper, JSON);

        jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
            .thenReturn(List.of(101L));
        when(jdbc.batchUpdate(anyString(), anyList())).thenReturn(new int[] {1});
        when(jdbc.queryForList(anyString(), any(Class.class), any(Object[].class)))
            .thenReturn(List.of());

        TransactionTemplate transactions = mock(TransactionTemplate.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> work =
                invocation.getArgument(0);
            work.accept(null);
            return null;
        }).when(transactions).executeWithoutResult(any());

        BusinessNumberService numbers = mock(BusinessNumberService.class);
        when(numbers.next(any(), any())).thenReturn("NO-1");

        AuthorizationService authorization = mock(AuthorizationService.class);
        when(authorization.currentUserId()).thenReturn(USER_ID);

        keyLookup = new FormImportKeyLookup(jdbc);
        manual = new FormDataManualImport(new FormImportTemplateReader(forms, JSON), forms,
            keyLookup, new FormDataImportWriter(jdbc, transactions, numbers, JSON),
            authorization);
    }

    @Test
    void structuredValuesAreStoredExactlyAsSubmitted() throws Exception {
        ImportOutcome outcome = manual.importOne(new ManualImportRequest(FORM_ID, " EXT-1 ",
            data()));

        assertThat(outcome.successCount()).isEqualTo(1);
        assertThat(outcome.atomic()).isTrue();
        assertThat(outcome.createdIds()).containsExactly(101L);

        ArgumentCaptor<List<Object[]>> captor = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(anyString(), captor.capture());
        Object[] row = captor.getValue().get(0);
        assertThat(row[0]).isEqualTo(FORM_ID);
        assertThat(row[3]).as("data 原样落库（不再做任何反解析）").isNotNull();
        // 单公司后不再写 company_id，列顺序整体前移一位。
        // 业务单号两端 trim，避免「EXT-1」与「 EXT-1 」被当成两条。
        assertThat(row[5]).isEqualTo("EXT-1");

        JsonNode stored = JSON.readTree((String) row[3]);
        assertThat(stored.path("subject").asText()).isEqualTo("出差");
        assertThat(stored.path("chk").get(0).path("status").asText()).isEqualTo("pass");
    }

    @Test
    void missingRequiredFieldIsReportedWithFieldIdAndLabel() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("amount", 10);

        assertThatThrownBy(() -> manual.importOne(
            new ManualImportRequest(FORM_ID, null, data)))
            .isInstanceOf(ManualImportFailure.class)
            .satisfies(error -> {
                ManualImportFailure failure = (ManualImportFailure) error;
                assertThat(failure.getCode()).isEqualTo("IMPORT_VALIDATION_FAILED");
                assertThat(failure.failures()).singleElement().satisfies(issue -> {
                    assertThat(issue.fieldId()).isEqualTo("subject");
                    assertThat(issue.fieldLabel()).isEqualTo("事由");
                    assertThat(issue.message())
                        .contains("事由")
                        .contains("必填")
                        .doesNotContain("is required");
                });
            });
        verify(jdbc, never()).batchUpdate(anyString(), anyList());
    }

    @Test
    void repeatedImportKeyIsRejectedAsDuplicate() {
        when(jdbc.queryForList(anyString(), any(Class.class), any(Object[].class)))
            .thenReturn(List.of("EXT-1"));

        assertThatThrownBy(() -> manual.importOne(
            new ManualImportRequest(FORM_ID, "EXT-1", data())))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("已经导入过");
        verify(jdbc, never()).batchUpdate(anyString(), anyList());
    }

    @Test
    void withoutImportKeyDuplicatesAreNotGuessed() throws Exception {
        ImportOutcome outcome = manual.importOne(new ManualImportRequest(FORM_ID, null,
            data()));

        assertThat(outcome.successCount()).isEqualTo(1);
        assertThat(outcome.warnings()).anyMatch(warning -> warning.contains("业务单号"));
    }

    // ---------------------------------------------------------------- 夹具

    private static Map<String, Object> data() {
        List<Map<String, Object>> checklist = List.of(Map.of(
            "id", "item-1", "status", "pass", "images", List.of()));
        return new LinkedHashMap<>(Map.of("subject", "出差", "amount", 10,
            "chk", checklist));
    }

    private static final String SCHEMA = """
        [
          {"id":"subject","type":"text","label":"事由","props":{"required":true}},
          {"id":"amount","type":"number","label":"金额"},
          {"id":"chk","type":"checklist","label":"检查项","props":{
            "items":[{"id":"item-1","label":"检查项1"}],
            "results":[{"id":"pass","label":"通过"},{"id":"fail","label":"不通过"}]}}
        ]
        """;
}
