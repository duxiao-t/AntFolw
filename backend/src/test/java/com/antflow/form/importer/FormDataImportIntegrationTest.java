package com.antflow.form.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.antflow.attachment.FileStorage;
import com.antflow.attachment.StoredObject;
import com.antflow.auth.PrincipalHolder;
import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 真库验证：原子回滚、唯一索引幂等、created_by = 导入人。
 *
 * <p>为什么必须真库：这三条都由 PostgreSQL 保证（事务回滚、部分唯一索引、列值），
 * mock 出来的"事务"只能证明我们调了 API，证明不了库里发生了什么。
 */
@SpringBootTest(properties = {
    "antflow.audit.archive-cron=-",
    "antflow.automation.poll-interval-ms=3600000",
    "antflow.automation.recovery-interval-ms=3600000",
    "antflow.outbox.poll-interval-ms=3600000",
    "antflow.wecom.schedule-poll-interval-ms=3600000",
    "antflow.mobile.files.processing-reap-interval-ms=3600000",
    "antflow.mobile.files.storage=test",
    "antflow.jwt.secret=test-secret-0123456789-test-secret-0123456789",
    "antflow.auth.bootstrap-admin-password=test-bootstrap-password",
    "antflow.auth.bootstrap-bob-password=test-bootstrap-password"
})
@Import(FormDataImportIntegrationTest.TestFileStorageConfig.class)
class FormDataImportIntegrationTest {

    private static final String EXTERNAL_POSTGRES_URL = System.getenv("ANTFLOW_TEST_POSTGRES_URL");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String XLSX =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    static final PostgreSQLContainer<?> POSTGRES =
        externalPostgres() ? null : new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("antflow")
            .withUsername("antflow")
            .withPassword("antflow");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        if (externalPostgres()) {
            registry.add("spring.datasource.url", () -> jdbcUrl(EXTERNAL_POSTGRES_URL));
            registry.add("spring.datasource.username",
                () -> env("ANTFLOW_TEST_POSTGRES_USERNAME", "antflow"));
            registry.add("spring.datasource.password",
                () -> env("ANTFLOW_TEST_POSTGRES_PASSWORD", "antflow"));
            return;
        }
        POSTGRES.start();
        registry.add("spring.datasource.url", () -> jdbcUrl(POSTGRES.getJdbcUrl()));
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static boolean externalPostgres() {
        return EXTERNAL_POSTGRES_URL != null && !EXTERNAL_POSTGRES_URL.isBlank();
    }

    private static String jdbcUrl(String url) {
        return url + (url.contains("?") ? "&" : "?") + "stringtype=unspecified";
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestFileStorageConfig {
        @Bean
        FileStorage fileStorage() {
            return new FileStorage() {
                private final Map<String, byte[]> files = new java.util.concurrent.ConcurrentHashMap<>();

                @Override
                public StoredObject put(String storageKey, InputStream content, long size,
                                        String contentType) throws IOException {
                    byte[] bytes = content.readAllBytes();
                    files.put(storageKey, bytes);
                    return new StoredObject(storageKey, bytes.length);
                }

                @Override
                public Resource get(String storageKey) {
                    return new ByteArrayResource(files.getOrDefault(storageKey, new byte[0]));
                }

                @Override
                public boolean exists(String storageKey) {
                    return files.containsKey(storageKey);
                }

                @Override
                public void delete(String storageKey) {
                    files.remove(storageKey);
                }
            };
        }
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private FormDataImportService importService;
    @Autowired private FormImportTemplateService templates;

    private long companyId;
    private long deptId;
    private long userId;
    private long ownerId;
    private String ownerNo;
    private long formId;

    @BeforeEach
    void setUp() {
        String token = UUID.randomUUID().toString().replace("-", "");
        companyId = jdbc.queryForObject(
            "INSERT INTO t_company(name) VALUES (?) RETURNING id", Long.class, "导入公司" + token);
        deptId = jdbc.queryForObject("""
            INSERT INTO t_department(company_id, path, name)
            VALUES (?, CAST(? AS ltree), ?) RETURNING id
            """, Long.class, companyId, "imp_" + token, "导入部");
        userId = jdbc.queryForObject("""
            INSERT INTO t_user(dept_id, username, password_hash, display_name, employee_no, status)
            VALUES (?, ?, 'x', '导入人', ?, 'ACTIVE') RETURNING id
            """, Long.class, deptId, "importer_" + token, "E" + token.substring(0, 12));
        ownerNo = "O" + token.substring(0, 12);
        ownerId = jdbc.queryForObject("""
            INSERT INTO t_user(dept_id, username, password_hash, display_name, employee_no, status)
            VALUES (?, ?, 'x', '李四', ?, 'ACTIVE') RETURNING id
            """, Long.class, deptId, "owner_" + token, ownerNo);
        formId = jdbc.queryForObject("""
            INSERT INTO t_form_definition(code, name, version, schema, status)
            VALUES (?, '历史补录测试', 1, ?::jsonb, 'PUBLISHED') RETURNING id
            """, Long.class, "imp-" + token, SCHEMA);
        PrincipalHolder.set(new PrincipalHolder.Principal(userId, "importer_" + token, "导入人",
            Set.of("admin"), Set.of("form:data:import"), 0L, deptId, null));
    }

    @AfterEach
    void tearDown() {
        PrincipalHolder.clear();
        jdbc.update("DROP TRIGGER IF EXISTS imp_fail ON t_form_data");
        jdbc.update("DROP FUNCTION IF EXISTS imp_fail_trigger()");
    }

    // ---------------------------------------------------------------- 正常写入

    @Test
    void importedRowsAreSubmittedAndAttributedToTheImporter() throws Exception {
        ImportDtos.ImportOutcome outcome = importService.importData(formId,
            file(new String[] {"K1", "差旅报销", "100.5", "差旅", ownerNo},
                new String[] {"K2", "办公用品", "20", "办公", ownerNo}), null, false, null);

        assertThat(outcome.successCount()).isEqualTo(2);
        assertThat(outcome.atomic()).isTrue();
        assertThat(outcome.createdIds()).hasSize(2);

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT form_def_id, form_def_version, business_no, data::text, status,
                   created_by, company_id, company_inferred, import_key, import_batch
            FROM t_form_data WHERE import_batch = ? ORDER BY import_key
            """, outcome.batchId());
        assertThat(rows).hasSize(2);
        for (Map<String, Object> row : rows) {
            assertThat(((Number) row.get("form_def_id")).longValue()).isEqualTo(formId);
            assertThat(((Number) row.get("form_def_version")).intValue()).isEqualTo(1);
            assertThat(row.get("status")).isEqualTo("SUBMITTED");
            // 提交人 = 导入人，而不是表格里的"负责人"。
            assertThat(((Number) row.get("created_by")).longValue()).isEqualTo(userId);
            assertThat(((Number) row.get("company_id")).longValue()).isEqualTo(companyId);
            assertThat(row.get("company_inferred")).isEqualTo(false);
            // business_no 是系统生成的，绝不能等于外部单号。
            assertThat(row.get("business_no")).isNotEqualTo(row.get("import_key"));
            assertThat(row.get("business_no")).isNotNull();
        }
        JsonNode data = JSON.readTree((String) rows.get(0).get("data"));
        assertThat(data.path("kind").asText()).isEqualTo("travel");
        assertThat(data.path("owner").asLong()).isEqualTo(ownerId);
    }

    // ---------------------------------------------------------------- 原子回滚

    @Test
    void aFailedRowRollsBackTheWholeAtomicImport() throws Exception {
        jdbc.update("""
            CREATE OR REPLACE FUNCTION imp_fail_trigger() RETURNS trigger AS $$
            BEGIN
              IF NEW.import_key = 'BOOM' THEN RAISE EXCEPTION 'boom'; END IF;
              RETURN NEW;
            END; $$ LANGUAGE plpgsql
            """);
        jdbc.update("CREATE TRIGGER imp_fail BEFORE INSERT ON t_form_data"
            + " FOR EACH ROW EXECUTE FUNCTION imp_fail_trigger()");

        assertThatThrownBy(() -> importService.importData(formId,
            file(new String[] {"K1", "第一行", "1", "差旅", ownerNo},
                new String[] {"BOOM", "第二行", "2", "差旅", ownerNo},
                new String[] {"K3", "第三行", "3", "差旅", ownerNo}), null, false, null))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("未写入任何数据");

        assertThat(countRows()).as("事务必须整体回滚，一行都不能留").isZero();
    }

    // ---------------------------------------------------------------- 幂等

    @Test
    void importingTheSameBusinessKeysAgainOnlyAddsNewRows() throws Exception {
        importService.importData(formId,
            file(new String[] {"K1", "第一行", "1", "差旅", ownerNo},
                new String[] {"K2", "第二行", "2", "差旅", ownerNo}), null, false, null);
        assertThat(countRows()).isEqualTo(2);

        ImportDtos.ImportOutcome second = importService.importData(formId,
            file(new String[] {"K1", "第一行", "1", "差旅", ownerNo},
                new String[] {"K2", "第二行", "2", "差旅", ownerNo},
                new String[] {"K3", "第三行", "3", "差旅", ownerNo}), null, false, null);

        assertThat(second.successCount()).isEqualTo(1);
        assertThat(second.skippedCount()).isEqualTo(2);
        assertThat(countRows()).isEqualTo(3);
    }

    @Test
    void uniqueIndexAlsoBlocksConcurrentStyleDuplicatesAtTheDatabaseLevel() throws Exception {
        importService.importData(formId,
            file(new String[] {"K1", "第一行", "1", "差旅", ownerNo}), null, false, null);

        // 绕过服务层直接插同一个 (form_def_id, company_id, import_key)：唯一索引必须拒绝。
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO t_form_data(form_def_id, form_def_version, business_no, data, status,
                created_by, company_id, company_inferred, import_key)
            VALUES (?, 1, 'MANUAL', '{}'::jsonb, 'SUBMITTED', ?, ?, false, 'K1')
            """, formId, userId, companyId))
            .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    // ---------------------------------------------------------------- 明细 sheet

    /**
     * 核心断言：同一份结构化数据，用明细表填 与 用 JSON 填，落库的 {@code data} **逐字节一致**。
     *
     * <p>为什么必须真库逐字比：两条路径各自"看起来对"很容易，但只要键名、数字类型（2 与 "2"）、
     * 空图片数组任何一处不同，下游展示与统计就会分叉。jsonb 会重排键序，所以比的是规范化后的文本。
     */
    @Test
    void detailSheetsProduceExactlyTheSameStoredJsonAsTheJsonSyntax() throws Exception {
        String token = UUID.randomUUID().toString().replace("-", "");
        long structuredFormId = jdbc.queryForObject("""
            INSERT INTO t_form_definition(code, name, version, schema, status)
            VALUES (?, '明细表导入测试', 1, ?::jsonb, 'PUBLISHED') RETURNING id
            """, Long.class, "detail-" + token, STRUCTURED_SCHEMA);

        byte[] base = templates.render(structuredFormId);
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(base));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet data = workbook.getSheet(FormImportTemplateService.SHEET_DATA);
            // 第 3 行：老写法（JSON 单元格）；第 4 行：新写法（明细表，主表该列留空）。
            writeRow(data, 2, new String[] {"A-JSON", "点检", "[{\"id\":\"item-1\",\"status\":\"pass\"}]",
                "[{\"c1\":\"合同\",\"c2\":\"2\"}]", "{\"cells\":{\"r1\":{\"c1\":\"3\"}}}"});
            writeRow(data, 3, new String[] {"B-DETAIL", "点检", "", "", ""});
            writeRow(workbook.getSheet("检查项"), 2,
                new String[] {"4", "检查项1", "通过", ""});
            writeRow(workbook.getSheet("明细"), 2, new String[] {"4", "合同", "2"});
            writeRow(workbook.getSheet("检查矩阵"), 2, new String[] {"4", "第一行", "3", ""});
            workbook.write(out);
            bytes = out.toByteArray();
        }

        ImportDtos.ImportOutcome outcome = importService.importData(structuredFormId,
            new MockMultipartFile("file", "detail.xlsx", XLSX, bytes), null, false, null);
        assertThat(outcome.successCount()).isEqualTo(2);

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT data::text AS data FROM t_form_data
            WHERE form_def_id = ? ORDER BY import_key
            """, structuredFormId);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).get("data"))
            .as("明细表与 JSON 写法必须落出完全一致的存储值")
            .isEqualTo(rows.get(0).get("data"));
    }

    private static void writeRow(Sheet sheet, int rowIndex, String[] values) {
        Row row = sheet.getRow(rowIndex);
        if (row == null) row = sheet.createRow(rowIndex);
        for (int column = 0; column < values.length; column++) {
            row.createCell(column).setCellValue(values[column]);
        }
    }

    // ---------------------------------------------------------------- 夹具

    private long countRows() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM t_form_data WHERE form_def_id = ?",
            Long.class, formId);
        return count == null ? 0 : count;
    }

    private MockMultipartFile file(String[]... rows) throws Exception {
        byte[] base = templates.render(formId);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(base));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.getSheet(FormImportTemplateService.SHEET_DATA);
            for (int index = 0; index < rows.length; index++) {
                Row row = sheet.getRow(index + 2);
                if (row == null) row = sheet.createRow(index + 2);
                for (int column = 0; column < rows[index].length; column++) {
                    row.createCell(column).setCellValue(rows[index][column]);
                }
            }
            workbook.write(out);
            return new MockMultipartFile("file", "import.xlsx", XLSX, out.toByteArray());
        }
    }

    private static final String SCHEMA = """
        [
          {"id":"subject","type":"text","label":"事由","props":{"required":true}},
          {"id":"amount","type":"number","label":"金额"},
          {"id":"kind","type":"select","label":"类型","props":{"options":[
            {"label":"差旅","value":"travel"},{"label":"办公","value":"office"}]}},
          {"id":"owner","type":"user_picker","label":"负责人"}
        ]
        """;

    /** 三种结构化字段各一张明细表：用来对账"两种写法落库是否一致"。 */
    private static final String STRUCTURED_SCHEMA = """
        [
          {"id":"subject","type":"text","label":"事由"},
          {"id":"chk","type":"checklist","label":"检查项","props":{
            "items":[{"id":"item-1","label":"检查项1"}],
            "results":[{"id":"pass","label":"通过"},{"id":"fail","label":"不通过"}]}},
          {"id":"lines","type":"table_list","label":"明细","children":[
            {"id":"c1","type":"text","label":"名称"},
            {"id":"c2","type":"number","label":"数量"}]},
          {"id":"grid","type":"matrix_fill","label":"检查矩阵","props":{
            "cellType":"number","precision":2,"maxRows":10,"maxColumns":10,
            "rows":[{"id":"r1","label":"第一行"}],
            "columns":[{"id":"c1","label":"上午"},{"id":"c2","label":"下午"}]}}
        ]
        """;
}
