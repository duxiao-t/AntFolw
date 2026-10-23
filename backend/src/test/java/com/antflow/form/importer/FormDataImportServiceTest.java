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
import com.antflow.form.importer.FormImportValueCodec.DeptRef;
import com.antflow.form.importer.FormImportValueCodec.UserRef;
import com.antflow.form.importer.ImportDtos.ImportInspection;
import com.antflow.form.importer.ImportDtos.ImportOutcome;
import com.antflow.org.CompanyAttribution;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * 导入服务的编排行为：原子模式 0 行落库、跳过模式部分成功、重复跳过、限额。
 *
 * <p>用**真实模板 + 真实解析/校验**，只 mock 数据库与事务：这样测到的是"用户拿到的模板能不能
 * 被自己的导入端读回来"，而不是两边各自 mock 出来的假设。
 */
class FormDataImportServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long FORM_ID = 42L;
    private static final long COMPANY_ID = 9L;
    private static final long USER_ID = 7L;
    private static final String XLSX =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private FormDefinitionMapper formMapper;
    private FormDefinitionService forms;
    private FormImportTemplateService templates;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private BusinessNumberService businessNumbers;
    private CompanyAttribution companyAttribution;
    private AuthorizationService authorization;
    private FormDataImportService service;
    private FormDefinition form;

    @BeforeEach
    void setUp() {
        form = new FormDefinition();
        form.setId(FORM_ID);
        form.setCode("expense");
        form.setName("报销单");
        form.setVersion(1);
        form.setStatus("PUBLISHED");
        form.setSchema(SCHEMA);

        formMapper = mock(FormDefinitionMapper.class);
        when(formMapper.selectById(FORM_ID)).thenReturn(form);
        forms = FormDefinitionServiceFixture.service(formMapper, JSON);
        templates = new FormImportTemplateService(forms, JSON);

        jdbc = mock(JdbcTemplate.class);
        // 批量插入拿不到 RETURNING，服务用批次号回查；这里给固定的两行结果。
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
            .thenReturn(List.of(100L, 101L));
        when(jdbc.batchUpdate(anyString(), anyList())).thenReturn(new int[] {1});

        transactions = mock(TransactionTemplate.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> work =
                invocation.getArgument(0);
            work.accept(null);
            return null;
        }).when(transactions).executeWithoutResult(any());

        businessNumbers = mock(BusinessNumberService.class);
        when(businessNumbers.next(any(), any())).thenReturn("NO-1", "NO-2", "NO-3");

        companyAttribution = mock(CompanyAttribution.class);
        when(companyAttribution.resolve(any())).thenReturn(COMPANY_ID);

        authorization = mock(AuthorizationService.class);
        when(authorization.currentUserId()).thenReturn(USER_ID);

        FormImportValueCodec codec = new FormImportValueCodec(JSON, DIRECTORY);
        service = new FormDataImportService(forms, codec, JSON, jdbc, transactions,
            businessNumbers, companyAttribution, authorization);
    }

    // ---------------------------------------------------------------- 预检

    @Test
    void inspectReportsColumnsAndNeverTouchesTheDatabase() throws Exception {
        ImportInspection inspection = service.inspect(FORM_ID,
            file(rows(new String[] {"K1", "出差", "100.5", "差旅", "李四"})), null);

        assertThat(inspection.formId()).isEqualTo(FORM_ID);
        assertThat(inspection.sheetName()).isEqualTo("数据");
        assertThat(inspection.columns()).hasSize(5);
        assertThat(inspection.columns().get(0).fieldId()).isEqualTo("__import_key");
        assertThat(inspection.rowCount()).isEqualTo(1);
        assertThat(inspection.importableCount()).isEqualTo(1);
        assertThat(inspection.errorCount()).isZero();
        assertThat(inspection.missingColumns()).isEmpty();
        assertThat(inspection.previews()).hasSize(1);
        assertThat(inspection.previews().get(0))
            .containsEntry("subject", "出差")
            .containsEntry("amount", new BigDecimal("100.5"))
            .containsEntry("kind", "travel")
            .containsEntry("owner", 11L);
        verify(jdbc, never()).batchUpdate(anyString(), anyList());
    }

    // ---------------------------------------------------------------- 原子

    @Test
    void atomicImportRefusesEverythingWhenOneRowIsInvalid() throws Exception {
        MultipartFile file = file(rows(
            new String[] {"K1", "出差", "100", "差旅", "李四"},
            new String[] {"K2", "坏行", "abc", "差旅", "李四"}));

        assertThatThrownBy(() -> service.importData(FORM_ID, file, null, false, null))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("0 行落库")
            .hasMessageContaining("第4行");

        // 关键断言：校验失败时一次 insert 都没发生。
        verify(jdbc, never()).batchUpdate(anyString(), anyList());
    }

    @Test
    void atomicImportWritesAllRowsInOneBatch() throws Exception {
        ImportOutcome outcome = service.importData(FORM_ID, file(rows(
            new String[] {"K1", "出差", "100", "差旅", "李四"},
            new String[] {"K2", "办公", "20", "差旅", "李四"})), null, false, null);

        assertThat(outcome.atomic()).isTrue();
        assertThat(outcome.successCount()).isEqualTo(2);
        assertThat(outcome.failureCount()).isZero();
        assertThat(outcome.skippedCount()).isZero();
        assertThat(outcome.createdIds()).containsExactly(100L, 101L);
    }

    // ---------------------------------------------------------------- 跳过

    @Test
    void skipModeImportsValidRowsAndReportsBadOnesWithRealRowNumbers() throws Exception {
        MultipartFile file = file(rows(
            new String[] {"K1", "出差", "100", "差旅", "李四"},
            new String[] {"K2", "坏行", "abc", "差旅", "李四"},
            new String[] {"K3", "办公", "20", "差旅", "李四"}));

        ImportOutcome outcome = service.importData(FORM_ID, file, null, true, null);

        assertThat(outcome.atomic()).isFalse();
        assertThat(outcome.totalRows()).isEqualTo(3);
        assertThat(outcome.successCount()).isEqualTo(2);
        assertThat(outcome.failureCount()).isEqualTo(1);
        assertThat(outcome.failures()).singleElement()
            .satisfies(issue -> {
                assertThat(issue.row()).isEqualTo(4);
                assertThat(issue.code()).isEqualTo("IMPORT_VALUE_INVALID");
            });
        // 只把合格的两行交给数据库。
        org.mockito.ArgumentCaptor<List<Object[]>> captor =
            org.mockito.ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(anyString(), captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    // ---------------------------------------------------------------- 重复

    @Test
    void repeatedImportKeyInsideTheFileIsSkipped() throws Exception {
        ImportOutcome outcome = service.importData(FORM_ID, file(rows(
            new String[] {"K1", "出差", "100", "差旅", "李四"},
            new String[] {"K1", "另一个", "200", "差旅", "李四"})), null, false, null);

        assertThat(outcome.successCount()).isEqualTo(1);
        assertThat(outcome.skippedCount()).isEqualTo(1);
        assertThat(outcome.warnings()).anyMatch(warning -> warning.contains("重复"));
    }

    @Test
    void identicalRowsWithoutKeyAreDedupedByContentHashWithinTheFile() throws Exception {
        ImportOutcome outcome = service.importData(FORM_ID, file(rows(
            new String[] {"", "出差", "100", "差旅", "李四"},
            new String[] {"", "出差", "100", "差旅", "李四"})), null, false, null);

        assertThat(outcome.successCount()).isEqualTo(1);
        assertThat(outcome.skippedCount()).isEqualTo(1);
    }

    @Test
    void fileWhereEverythingWasImportedBeforeIsRejectedAsDuplicate() throws Exception {
        when(jdbc.queryForList(anyString(), org.mockito.ArgumentMatchers.eq(String.class),
            any(Object[].class))).thenReturn(List.of("K1"));

        assertThatThrownBy(() -> service.importData(FORM_ID, file(rows(
            new String[] {"K1", "出差", "100", "差旅", "李四"})), null, false, null))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("都已经导入过");
    }

    // ---------------------------------------------------------------- 限额/格式

    @Test
    void emptyUploadIsRejected() {
        MultipartFile empty = new MockMultipartFile("file", "a.xlsx", XLSX, new byte[0]);
        assertThatThrownBy(() -> service.inspect(FORM_ID, empty, null))
            .isInstanceOf(BizException.class).hasMessageContaining("请上传");
    }

    @Test
    void nonXlsxIsRejected() {
        MultipartFile csv = new MockMultipartFile("file", "a.csv", "text/csv", "a,b".getBytes());
        assertThatThrownBy(() -> service.inspect(FORM_ID, csv, null))
            .isInstanceOf(BizException.class).hasMessageContaining(".xlsx");
    }

    @Test
    void oversizedFileIsRejectedBeforeParsing() {
        MultipartFile big = mock(MultipartFile.class);
        when(big.isEmpty()).thenReturn(false);
        when(big.getSize()).thenReturn(11L * 1024 * 1024);
        when(big.getOriginalFilename()).thenReturn("a.xlsx");
        assertThatThrownBy(() -> service.inspect(FORM_ID, big, null))
            .isInstanceOf(BizException.class).hasMessageContaining("10 MB");
    }

    @Test
    void plainWorkbookWithoutMetadataIsRejected() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet("数据").createRow(0).createCell(0).setCellValue("x");
            workbook.write(out);
            bytes = out.toByteArray();
        }
        assertThatThrownBy(() -> service.inspect(FORM_ID,
            new MockMultipartFile("file", "a.xlsx", XLSX, bytes), null))
            .isInstanceOf(BizException.class).hasMessageContaining("不是系统生成的导入模板");
    }

    @Test
    void sheetNameThatDoesNotExistIsReported() throws Exception {
        assertThatThrownBy(() -> service.inspect(FORM_ID,
            file(rows(new String[] {"K1", "出差", "1", "差旅", "李四"})), "不存在"))
            .isInstanceOf(BizException.class).hasMessageContaining("工作表");
    }

    @Test
    void dataRowsBeyondTheLimitAreRejectedInInspection() throws Exception {
        String[][] data = new String[20_000][];
        for (int index = 0; index < data.length; index++) {
            data[index] = new String[] {"", "行" + index, "", "", ""};
        }
        assertThatThrownBy(() -> service.inspect(FORM_ID, file(rows(data)), null))
            .isInstanceOf(BizException.class).hasMessageContaining("20000");
    }

    // ---------------------------------------------------------------- 夹具

    private static final String SCHEMA = """
        [
          {"id":"subject","type":"text","label":"事由","props":{"required":true}},
          {"id":"amount","type":"number","label":"金额"},
          {"id":"kind","type":"select","label":"类型","props":{"options":[
            {"label":"差旅","value":"travel"},{"label":"办公","value":"office"}]}},
          {"id":"owner","type":"user_picker","label":"负责人"}
        ]
        """;

    private static final FormImportValueCodec.Directory DIRECTORY =
        new FormImportValueCodec.Directory() {
            @Override
            public List<UserRef> users(Collection<String> tokens) {
                return List.of(new UserRef(11, "E001", "李四"));
            }

            @Override
            public List<DeptRef> departments() {
                return List.of(new DeptRef(1, "总部", null));
            }

            @Override
            public List<Map<String, String>> optionRows(long versionId) {
                return List.of();
            }
        };

    private MultipartFile file(String[][] data) throws Exception {
        byte[] base = templates.render(FORM_ID);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(base));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.getSheet(FormImportTemplateService.SHEET_DATA);
            for (int rowIndex = 0; rowIndex < data.length; rowIndex++) {
                Row row = sheet.getRow(rowIndex + 2);
                if (row == null) row = sheet.createRow(rowIndex + 2);
                for (int column = 0; column < data[rowIndex].length; column++) {
                    row.createCell(column).setCellValue(data[rowIndex][column]);
                }
            }
            workbook.write(out);
            return new MockMultipartFile("file", "import.xlsx", XLSX, out.toByteArray());
        }
    }

    private static String[][] rows(String[]... rows) {
        List<String[]> all = new ArrayList<>(List.of(rows));
        return all.toArray(new String[0][]);
    }
}
