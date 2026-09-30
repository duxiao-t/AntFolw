package com.antflow.integration;

import com.antflow.audit.AuditService;
import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.FormGrantService;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.HiddenResourceException;
import com.antflow.authz.PermissionCodes;
import com.antflow.authz.RoleAdminService;
import com.antflow.engine.BizException;
import com.antflow.engine.NoAssigneeFoundException;
import com.antflow.engine.ProcessEngine;
import com.antflow.engine.dto.CompleteCmd;
import com.antflow.engine.dto.StartCmd;
import com.antflow.form.FormProcessPublishService;
import com.antflow.form.options.OptionSourceService;
import com.antflow.form.FormDefinitionMapper;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.runtime.FormDataMapper;
import com.antflow.form.runtime.FormDataService;
import com.antflow.integration.wecom.WecomService;
import com.antflow.mobile.workflow.MobileWorkflowMapper;
import com.antflow.mobile.workflow.MobileDraftService;
import com.antflow.mobile.workflow.MobileAppService;
import com.antflow.navigation.MenuService;
import com.antflow.report.ReportService;
import com.antflow.mobile.workflow.FileStorage;
import com.antflow.mobile.workflow.StoredObject;
import com.antflow.org.User;
import com.antflow.org.UserService;
import com.antflow.task.ProcessInstanceMapper;
import com.antflow.task.WorkflowMonitoringController;
import com.antflow.task.TaskMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
    "antflow.audit.archive-cron=-",
    "antflow.automation.poll-interval-ms=3600000",
    "antflow.automation.recovery-interval-ms=3600000",
    "antflow.outbox.poll-interval-ms=3600000",
    "antflow.wecom.schedule-poll-interval-ms=3600000",
    "antflow.mobile.files.storage=test",
    "antflow.jwt.secret=test-secret-0123456789-test-secret-0123456789",
    // 引导口令放在测试自己这里（pom 的全局属性会盖掉外部传值，故已从 pom 移除）。
    // 用非公开口令：初始化器会拒绝已知共享口令，而 V48 会把种子账号打成哨兵，
    // 测试的 Spring 上下文必须能把它解开。
    "antflow.auth.bootstrap-admin-password=test-bootstrap-password",
    "antflow.auth.bootstrap-bob-password=test-bootstrap-password"
})
@Import(PostgresTransactionalIntegrityIntegrationTest.TestFileStorageConfig.class)
class PostgresTransactionalIntegrityIntegrationTest {
    private static final String EXTERNAL_POSTGRES_URL = System.getenv("ANTFLOW_TEST_POSTGRES_URL");
    private static final String VALID_SCHEMA =
        "[{\"id\":\"subject\",\"type\":\"text\",\"label\":\"Subject\"}]";
    private final ObjectMapper flowJson = new ObjectMapper();

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
        registry.add("spring.datasource.url", () -> {
            return jdbcUrl(POSTGRES.getJdbcUrl());
        });
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
            // MinIO lifecycle is covered by MobileAttachmentMinioIntegrationTest.
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
                public void delete(String storageKey) {
                    files.remove(storageKey);
                }
            };
        }
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;
    @Autowired private ProcessEngine processEngine;
    @Autowired private UserService userService;
    @Autowired private AuditService auditService;
    @Autowired private FormProcessPublishService publishService;
    @Autowired private ProcessInstanceMapper processInstanceMapper;
    @Autowired private TaskMapper taskMapper;
    @Autowired private FormGrantService formGrantService;
    @Autowired private MobileWorkflowMapper mobileWorkflowMapper;
    @Autowired private AuthorizationService authorizationService;
    @Autowired private RoleAdminService roleAdminService;
    @Autowired private com.antflow.report.ReportService reportService;
    @Autowired private FormDefinitionMapper formDefinitionMapper;
    @Autowired private FormDataService formDataService;
    @Autowired private FormDataMapper formDataMapper;
    @Autowired private FormDefinitionService formDefinitionService;
    @Autowired private MobileDraftService mobileDraftService;
    @Autowired private MobileAppService mobileAppService;
    @Autowired private MenuService menuService;
    @Autowired private WecomService wecomService;
    @Autowired private OptionSourceService optionSourceService;
    @Autowired private com.antflow.form.options.OptionRuntimeService optionRuntimeService;

    /**
     * 隐藏字段的选项可见性必须按"调用者自己所处的节点"判，而不是只看发起人。
     * 三条路径（instanceId / dataId / selectedValues 回查）共用同一套判据，这里走前两条。
     */
    @Test
    void optionFieldVisibilityFollowsTheViewersOwnNode() {
        long starter = userId("admin");
        long first = insertUser("vis-first");
        long second = insertUser("vis-second");
        long reader = insertUser("vis-reader");
        long approverRole = insertRole("vis-approver");
        jdbcTemplate.update("INSERT INTO t_role_permission(role_id, permission_code) "
            + "VALUES (?, 'workflow:task:read')", approverRole);
        assignRole(first, approverRole);
        assignRole(second, approverRole);
        long readerRole = insertRole("vis-instance-reader");
        jdbcTemplate.update("INSERT INTO t_role_permission(role_id, permission_code, scope_override) "
            + "VALUES (?, 'workflow:instance:read', 'ALL'), (?, 'form:data:read', 'ALL')",
            readerRole, readerRole);
        assignRole(reader, readerRole);

        long sourceId = insertOptionSource("vis_source");
        long versionId = optionSourceVersionId(sourceId);
        jdbcTemplate.update("INSERT INTO t_option_data_source_row(version_id, row_no, data) "
            + "VALUES (?, 1, '{\"col\":\"甲\"}'::jsonb)", versionId);
        java.util.function.Function<String, String> boundSelect = id ->
            "{\"id\":\"" + id + "\",\"type\":\"select\",\"label\":\"F\",\"props\":{\"optionSource\":"
                + "{\"sourceId\":" + sourceId + ",\"versionId\":" + versionId
                + ",\"valueColumn\":\"col\",\"labelColumn\":\"col\"}}}";
        String schema = "[" + boundSelect.apply("plain") + "," + boundSelect.apply("hiddenA")
            + "," + boundSelect.apply("hiddenB") + "]";
        // a1 把 hiddenA 设为隐藏、a2 把 hiddenB 设为隐藏。
        String flow = """
            {"id":"root","type":"ROOT","children":{
              "id":"a1","type":"APPROVAL","props":{"assignedType":"ASSIGN_USER",
                "assignedUser":[%d],"mode":"OR","formPerms":[{"fieldId":"hiddenA","mode":"HIDDEN"}]},
              "children":{"id":"a2","type":"APPROVAL","props":{"assignedType":"ASSIGN_USER",
                "assignedUser":[%d],"mode":"OR","formPerms":[{"fieldId":"hiddenB","mode":"HIDDEN"}]},
                "children":null}}}
            """.formatted(first, second);
        long formId = insertForm("DRAFT", schema, starter);
        long processId = insertProcess(formId, "DRAFT", flow);
        String code = jdbcTemplate.queryForObject(
            "SELECT code FROM t_form_definition WHERE id = ?", String.class, formId);
        long createdInstance = 0L;
        try {
            PrincipalHolder.set(new PrincipalHolder.Principal(starter, "admin", List.of("admin")));
            publishService.publish(formId, processId);
            Map<String, Object> started = processEngine.start(
                new StartCmd(code, Map.of(), Map.of()), starter);
            final long instanceId = ((Number) started.get("instanceId")).longValue();
            createdInstance = instanceId;
            long dataId = jdbcTemplate.queryForObject(
                "SELECT form_data_id FROM t_process_instance WHERE id = ?", Long.class, instanceId);
            long firstTask = ((List<?>) started.get("firstTaskIds")).stream()
                .map(Number.class::cast).mapToLong(Number::longValue).findFirst().orElseThrow();

            // 发起人：沿用 ROOT 的 formPerms（ROOT 没配 → 不受影响）
            assertThat(queryOptionsByInstance(instanceId, "hiddenA")).isNotEmpty();

            // a1 处理人：自己节点没隐藏 hiddenB → 必须查得到（不能被"任一节点隐藏"误伤）
            setPrincipal(first);
            assertThat(queryOptionsByInstance(instanceId, "hiddenB")).isNotEmpty();
            assertThatThrownBy(() -> queryOptionsByInstance(instanceId, "hiddenA"))
                .isInstanceOf(HiddenResourceException.class);

            // 用 SQL 推进到 a2 而不是 processEngine.approve：t_task_history 是 append-only
            // （触发器保护），一旦走了真审批就没法清理这次测试留下的实例。
            jdbcTemplate.update("UPDATE t_task SET status = 'APPROVED', approved_by = ?, "
                + "approved_at = now() WHERE id = ?", first, firstTask);
            jdbcTemplate.update("INSERT INTO t_task(proc_inst_id, node_id, assignee_id, status, "
                + "approval_mode, task_type, version) "
                + "VALUES (?, 'a2', ?, 'PENDING', 'OR_SIGN', 'APPROVAL', 0)", instanceId, second);

            // a2 处理人：反过来
            setPrincipal(second);
            assertThat(queryOptionsByInstance(instanceId, "hiddenA")).isNotEmpty();
            assertThatThrownBy(() -> queryOptionsByInstance(instanceId, "hiddenB"))
                .isInstanceOf(HiddenResourceException.class);

            // 不是处理人（只有实例读 + 数据读权限）：任一节点隐藏就拒；没隐藏的照常
            setPrincipal(reader);
            assertThat(queryOptionsByInstance(instanceId, "plain")).isNotEmpty();
            assertThatThrownBy(() -> queryOptionsByInstance(instanceId, "hiddenA"))
                .isInstanceOf(HiddenResourceException.class);
            // 拿 dataId 绕不开：同一条记录反查实例后按同一套判
            assertThat(queryOptionsByDataId(dataId, "plain")).isNotEmpty();
            assertThatThrownBy(() -> queryOptionsByDataId(dataId, "hiddenB"))
                .isInstanceOf(HiddenResourceException.class);
        } finally {
            PrincipalHolder.clear();
            cleanupOptionVisibilityFixture(sourceId, approverRole, readerRole);
        }
    }

    private List<com.antflow.form.options.OptionRuntimeService.OptionValue> queryOptionsByInstance(
            long instanceId, String fieldId) {
        return optionRuntimeService.query(new com.antflow.form.options.OptionRuntimeService.OptionQuery(
            null, null, instanceId, null, fieldId, null, 1, 20, Map.of(), List.of(), null)).items();
    }

    private List<com.antflow.form.options.OptionRuntimeService.OptionValue> queryOptionsByDataId(
            long dataId, String fieldId) {
        return optionRuntimeService.query(new com.antflow.form.options.OptionRuntimeService.OptionQuery(
            null, null, null, dataId, fieldId, null, 1, 20, Map.of(), List.of(), null)).items();
    }

    /**
     * 由引擎启动的实例清不掉：`t_task_history` 是 append-only（触发器保护），而它引用 proc_inst_id，
     * 于是 instance → form_data → form_definition 这条链都得留着——与
     * {@code workflowHistoryIsAppendOnlyAtDatabaseBoundary} 一样，测完不回收。
     * 只回收本次独有的数据源与角色，避免影响"按列表断言"的用例。
     */
    private void cleanupOptionVisibilityFixture(long sourceId, long approverRole, long readerRole) {
        // 源→版本的 FK 不级联，得按 行 → 版本 → 源 的顺序删。
        jdbcTemplate.update("DELETE FROM t_option_data_source_row WHERE version_id IN "
            + "(SELECT id FROM t_option_data_source_version WHERE source_id = ?)", sourceId);
        jdbcTemplate.update("DELETE FROM t_option_data_source_version WHERE source_id = ?", sourceId);
        jdbcTemplate.update("DELETE FROM t_form_option_source WHERE source_id = ?", sourceId);
        jdbcTemplate.update("DELETE FROM t_option_data_source WHERE id = ?", sourceId);
        jdbcTemplate.update("DELETE FROM t_user_role WHERE role_id IN (?, ?)", approverRole, readerRole);
        jdbcTemplate.update("DELETE FROM t_role WHERE id IN (?, ?)", approverRole, readerRole);
    }

    @Test
    void directSubmissionConsumesOnlyItsSourceDraft() {
        long adminId = userId("admin");
        long formId = insertForm("PUBLISHED",
            "[{\"id\":\"subject\",\"type\":\"text\",\"label\":\"Subject\",\"props\":{\"required\":true}}]");
        String code = jdbcTemplate.queryForObject(
            "SELECT code FROM t_form_definition WHERE id = ?", String.class, formId);
        long draftId = insertDraft(formId, adminId);
        PrincipalHolder.set(new PrincipalHolder.Principal(adminId, "admin", List.of("admin")));
        try {
            formDataService.submit(code, "SUBMITTED", Map.of("subject", "done"), adminId,
                List.of(), draftId);

            assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_form_data WHERE id = ?", Long.class, draftId)).isZero();

            long invalidDraftId = insertDraft(formId, adminId);
            assertThatThrownBy(() -> formDataService.submit(code, "SUBMITTED", Map.of(), adminId,
                List.of(), invalidDraftId)).isInstanceOf(BizException.class);
            assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_form_data WHERE id = ?", Long.class, invalidDraftId))
                .isEqualTo(1L);
        } finally {
            PrincipalHolder.clear();
        }
    }

    @Test
    void formDataPageUsesCapabilityScopeWithoutFormUsageGrant() {
        long userId = insertUser("all-scope-" + UUID.randomUUID());
        long roleId = insertRole("all_scope_" + UUID.randomUUID().toString().replace("-", ""));
        long formId = insertForm("PUBLISHED", VALID_SCHEMA);
        long dataId = insertSubmittedData(formId, userId("admin"));
        try {
            assignRole(userId, roleId);
            jdbcTemplate.update("""
                INSERT INTO t_role_permission(role_id, permission_code, scope_override)
                VALUES (?, 'form:data:read', 'ALL')
                """, roleId);
            setPrincipal(userId);

            Page<com.antflow.form.runtime.FormData> visible = formDataService.authorizedPage(
                1, 20, null, null, null, null, userId, false);
            assertThat(visible.getRecords()).extracting(com.antflow.form.runtime.FormData::getId)
                .contains(dataId);
        } finally {
            PrincipalHolder.clear();
            jdbcTemplate.update("DELETE FROM t_form_data WHERE id = ?", dataId);
            jdbcTemplate.update("DELETE FROM t_form_resource_grant WHERE form_def_id = ?", formId);
            jdbcTemplate.update("DELETE FROM t_form_definition WHERE id = ?", formId);
            jdbcTemplate.update("DELETE FROM t_user_role WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM t_role WHERE id = ?", roleId);
            jdbcTemplate.update("DELETE FROM t_user WHERE id = ?", userId);
        }
    }

    @Test
    void directSubmissionRequiresGrantAndDraftListRemainsSelfService() {
        long userId = insertUser("direct-submit-" + UUID.randomUUID());
        long allowedForm = insertForm("PUBLISHED", VALID_SCHEMA);
        long deniedForm = insertForm("PUBLISHED", VALID_SCHEMA);
        long deniedDraft = insertDraft(deniedForm, userId);
        assignRole(userId, roleId("employee"));
        jdbcTemplate.update("""
            INSERT INTO t_form_resource_grant(form_def_id, subject_type, subject_id, granted_by)
            VALUES (?, 'USER', ?, ?)
            """, allowedForm, userId, userId("admin"));
        String allowedCode = jdbcTemplate.queryForObject(
            "SELECT code FROM t_form_definition WHERE id = ?", String.class, allowedForm);
        String deniedCode = jdbcTemplate.queryForObject(
            "SELECT code FROM t_form_definition WHERE id = ?", String.class, deniedForm);
        try {
            setPrincipal(userId);
            var drafts = mobileDraftService.list(userId);
            assertThat(drafts)
                .extracting(com.antflow.mobile.workflow.MobileDraftDto::id)
                .containsExactly(deniedDraft);
            assertThat(drafts.get(0).readOnly()).isTrue();
            assertThat(mobileDraftService.count(userId)).isEqualTo(1L);

            assertThatThrownBy(() -> formDataService.submit(deniedCode, "SUBMITTED",
                Map.of("subject", "blocked"), userId, List.of(), deniedDraft))
                .isInstanceOf(HiddenResourceException.class);
            assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_form_data WHERE id = ?", Long.class, deniedDraft))
                .isEqualTo(1L);

            FormDataService.SubmitResult submitted = formDataService.submit(allowedCode,
                "SUBMITTED", Map.of("subject", "allowed"), userId, List.of());
            assertThat(submitted.dataId()).isNotNull();
        } finally {
            PrincipalHolder.clear();
            jdbcTemplate.update("DELETE FROM t_form_data WHERE form_def_id IN (?, ?)",
                allowedForm, deniedForm);
            jdbcTemplate.update("DELETE FROM t_form_resource_grant WHERE form_def_id IN (?, ?)",
                allowedForm, deniedForm);
            jdbcTemplate.update("DELETE FROM t_form_definition WHERE id IN (?, ?)",
                allowedForm, deniedForm);
            jdbcTemplate.update("DELETE FROM t_user_role WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM t_user WHERE id = ?", userId);
        }
    }

    @Test
    void mobileCatalogAndDetailUseTheSameFormUsageGrant() {
        long grantedUser = insertUser("mobile-granted-" + UUID.randomUUID());
        long ungrantedUser = insertUser("mobile-ungranted-" + UUID.randomUUID());
        long roleId = insertRole("mobile_runtime_"
            + UUID.randomUUID().toString().replace("-", ""));
        long firstForm = insertForm("PUBLISHED", VALID_SCHEMA);
        long secondForm = insertForm("PUBLISHED", VALID_SCHEMA);
        try {
            jdbcTemplate.update("""
                INSERT INTO t_role_permission(role_id, permission_code)
                VALUES (?, 'form:runtime:read')
                """, roleId);
            assignRole(grantedUser, roleId);
            assignRole(ungrantedUser, roleId);
            jdbcTemplate.update("""
                INSERT INTO t_form_resource_grant(form_def_id, subject_type, subject_id, granted_by)
                VALUES (?, 'USER', ?, ?), (?, 'USER', ?, ?)
                """, firstForm, grantedUser, userId("admin"),
                secondForm, grantedUser, userId("admin"));

            assertThat(mobileAppService.list(grantedUser, null, null))
                .extracting("formId")
                .contains(firstForm, secondForm);
            assertThat(mobileAppService.list(ungrantedUser, null, null)).isEmpty();
            assertThat(authorizationService.canUseForm(firstForm, grantedUser)).isTrue();
            assertThat(authorizationService.canUseForm(firstForm, ungrantedUser)).isFalse();
        } finally {
            jdbcTemplate.update("DELETE FROM t_form_resource_grant WHERE form_def_id IN (?, ?)",
                firstForm, secondForm);
            jdbcTemplate.update("DELETE FROM t_form_definition WHERE id IN (?, ?)",
                firstForm, secondForm);
            jdbcTemplate.update("DELETE FROM t_user_role WHERE user_id IN (?, ?)",
                grantedUser, ungrantedUser);
            jdbcTemplate.update("DELETE FROM t_role WHERE id = ?", roleId);
            jdbcTemplate.update("DELETE FROM t_user WHERE id IN (?, ?)",
                grantedUser, ungrantedUser);
            authorizationService.evict(grantedUser);
            authorizationService.evict(ungrantedUser);
        }
    }

    @Test
    void formDefinitionListAndActionsShareMaintainerPredicate() {
        long userId = insertUser("form-scope-" + UUID.randomUUID());
        long roleId = insertRole("form_scope_" + UUID.randomUUID().toString().replace("-", ""));
        long ownForm = insertForm("DRAFT", VALID_SCHEMA, userId);
        long otherForm = insertForm("DRAFT", VALID_SCHEMA);
        try {
            assignRole(userId, roleId);
            jdbcTemplate.update("""
                INSERT INTO t_role_permission(role_id, permission_code, scope_override)
                VALUES (?, 'form:definition:read', 'SELF'),
                       (?, 'form:definition:manage', 'SELF')
                """, roleId, roleId);
            jdbcTemplate.update("""
                INSERT INTO t_form_resource_grant(form_def_id, subject_type, subject_id, granted_by)
                VALUES (?, 'USER', ?, ?), (?, 'USER', ?, ?)
                """, ownForm, userId, userId("admin"),
                otherForm, userId, userId("admin"));
            jdbcTemplate.update("""
                INSERT INTO t_form_maintainer(form_def_id, user_id, granted_by)
                VALUES (?, ?, ?)
                """, ownForm, userId, userId("admin"));
            setPrincipal(userId);

            Page<FormDefinitionMapper.Summary> page = formDefinitionService.list(
                1, 100, null, null, userId, false);
            assertThat(page.getRecords()).extracting(FormDefinitionMapper.Summary::id)
                .contains(ownForm).doesNotContain(otherForm);
            page.getRecords().forEach(row -> authorizationService.requireFormMaintenance(
                row.id(), PermissionCodes.FORM_DEFINITION_READ));
            assertThatCode(() -> authorizationService.requireFormMaintenance(
                ownForm, PermissionCodes.FORM_DEFINITION_MANAGE)).doesNotThrowAnyException();
            assertThatThrownBy(() -> authorizationService.requireFormMaintenance(
                otherForm, PermissionCodes.FORM_DEFINITION_MANAGE))
                .isInstanceOf(HiddenResourceException.class);

            jdbcTemplate.update("""
                INSERT INTO t_form_maintainer(form_def_id, user_id, granted_by)
                VALUES (?, ?, ?)
                """, otherForm, userId, userId("admin"));
            Page<FormDefinitionMapper.Summary> expanded = formDefinitionService.list(
                1, 100, null, null, userId, false);
            assertThat(expanded.getRecords()).extracting(FormDefinitionMapper.Summary::id)
                .contains(ownForm, otherForm);
            assertThatCode(() -> authorizationService.requireFormMaintenance(
                otherForm, PermissionCodes.FORM_DEFINITION_MANAGE)).doesNotThrowAnyException();
        } finally {
            PrincipalHolder.clear();
            jdbcTemplate.update("DELETE FROM t_form_maintainer WHERE form_def_id IN (?, ?)",
                ownForm, otherForm);
            jdbcTemplate.update("DELETE FROM t_form_resource_grant WHERE form_def_id IN (?, ?)",
                ownForm, otherForm);
            jdbcTemplate.update("DELETE FROM t_form_definition WHERE id IN (?, ?)",
                ownForm, otherForm);
            jdbcTemplate.update("DELETE FROM t_user_role WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM t_role WHERE id = ?", roleId);
            jdbcTemplate.update("DELETE FROM t_user WHERE id = ?", userId);
        }
    }

    @Test
    void menuReplacementUsesCanonicalCapabilitiesAndAtomicVersion() throws Exception {
        PrincipalHolder.set(new PrincipalHolder.Principal(1L, "admin", List.of("admin")));
        MenuService.MenuDocument original;
        try {
            original = menuService.menu();
            assertThatThrownBy(() -> menuService.replace(
                new MenuService.MenuDocument(original.version(), List.of())))
                .isInstanceOfSatisfying(BizException.class, error ->
                    assertThat(error.getCode()).isEqualTo("MENU_REQUIRED"));
            MenuService.MenuNode unknown = new MenuService.MenuNode(
                "ghost", null, "PAGE", "ghost", "幽灵页", null,
                List.of(), 10, true, List.of());
            assertThatThrownBy(() -> menuService.replace(
                new MenuService.MenuDocument(original.version(), List.of(unknown))))
                .isInstanceOfSatisfying(BizException.class, error ->
                    assertThat(error.getCode()).isEqualTo("MENU_PAGE_KEY_UNKNOWN"));
        } finally {
            PrincipalHolder.clear();
        }
        MenuService.MenuNode forged = new MenuService.MenuNode(
            "workplace", null, "PAGE", "workplace", "工作台", "home",
            List.of("security:role:manage"), 10, true, List.of());
        MenuService.MenuDocument request = new MenuService.MenuDocument(
            original.version(), List.of(forged));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = List.of(
            executor.submit(() -> replaceMenu(request, ready, start)),
            executor.submit(() -> replaceMenu(request, ready, start)));
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<Object> outcomes;
        try {
            outcomes = List.of(futures.get(0).get(20, TimeUnit.SECONDS),
                futures.get(1).get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertThat(outcomes.stream().filter(MenuService.MenuDocument.class::isInstance).count())
            .isEqualTo(1L);
        assertThat(outcomes.stream().filter(BizException.class::isInstance)
            .map(BizException.class::cast).map(BizException::getCode))
            .containsExactly("MENU_VERSION_CONFLICT");
        MenuService.MenuDocument saved = outcomes.stream()
            .filter(MenuService.MenuDocument.class::isInstance)
            .map(MenuService.MenuDocument.class::cast).findFirst().orElseThrow();
        assertThat(saved.nodes().get(0).requiredPermissions()).isEmpty();
        PrincipalHolder.set(new PrincipalHolder.Principal(1L, "admin", List.of("admin")));
        try {
            menuService.replace(new MenuService.MenuDocument(saved.version(), original.nodes()));
        } finally {
            PrincipalHolder.clear();
        }
    }

    @Test
    void v41RemovesLegacyAdminOnlyAndPageDerivedGrants() {
        String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure().dataSource(dataSource).defaultSchema(schema).schemas(schema)
                .locations("classpath:db/migration").target("40").load().migrate();
            Long employeeRoleId = jdbcTemplate.queryForObject(
                "SELECT id FROM " + schema + ".t_role WHERE code = 'employee'", Long.class);
            Long bobId = jdbcTemplate.queryForObject(
                "SELECT id FROM " + schema + ".t_user WHERE username = 'bob'", Long.class);
            jdbcTemplate.update("INSERT INTO " + schema
                + ".t_role_permission(role_id, permission_code) VALUES "
                + "(?, 'security:user_role:read'), (?, 'integration:storage:manage')",
                employeeRoleId, employeeRoleId);
            Long before = jdbcTemplate.queryForObject(
                "SELECT authz_version FROM " + schema + ".t_user WHERE id = ?", Long.class, bobId);

            Flyway.configure().dataSource(dataSource).defaultSchema(schema).schemas(schema)
                .locations("classpath:db/migration").target("41").load().migrate();

            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + schema
                + ".t_role_permission WHERE role_id = ? AND permission_code IN "
                + "('security:user_role:read', 'integration:storage:manage')",
                Long.class, employeeRoleId)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                "SELECT authz_version FROM " + schema + ".t_user WHERE id = ?", Long.class, bobId))
                .isEqualTo(before + 1);
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void v42BackfillsCreatorsAndQualifiedManagersWithoutPromotingOrdinaryUsers() {
        String schema = "maintainer_migration_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure().dataSource(dataSource).defaultSchema(schema).schemas(schema)
                .locations("classpath:db/migration").target("41").load().migrate();
            Long adminId = jdbcTemplate.queryForObject(
                "SELECT id FROM " + schema + ".t_user WHERE username = 'admin'", Long.class);
            Long bobId = jdbcTemplate.queryForObject(
                "SELECT id FROM " + schema + ".t_user WHERE username = 'bob'", Long.class);
            Long managerRoleId = jdbcTemplate.queryForObject("INSERT INTO " + schema
                + ".t_role(code, name) VALUES ('migration_manager', 'Migration manager') "
                + "RETURNING id", Long.class);
            jdbcTemplate.update("INSERT INTO " + schema
                + ".t_user_role(user_id, role_id) VALUES (?, ?)", bobId, managerRoleId);
            jdbcTemplate.update("INSERT INTO " + schema
                + ".t_role_permission(role_id, permission_code, scope_override) "
                + "VALUES (?, 'form:definition:manage', 'SELF')", managerRoleId);
            Long plainUserId = jdbcTemplate.queryForObject("INSERT INTO " + schema
                + ".t_user(employee_no, username, password_hash, display_name, status) "
                + "SELECT '990001', 'migration_plain', password_hash, 'Migration plain', 'ACTIVE' "
                + "FROM " + schema + ".t_user WHERE id = ? RETURNING id", Long.class, bobId);
            Long employeeRoleId = jdbcTemplate.queryForObject(
                "SELECT id FROM " + schema + ".t_role WHERE code = 'employee'", Long.class);
            jdbcTemplate.update("INSERT INTO " + schema
                + ".t_user_role(user_id, role_id) VALUES (?, ?)", plainUserId, employeeRoleId);
            Long formId = jdbcTemplate.queryForObject("INSERT INTO " + schema
                + ".t_form_definition(code, name, schema, settings, status, created_by) "
                + "VALUES ('maintainer_migration', 'Maintainer migration', '[]', '{}', "
                + "'DRAFT', ?) RETURNING id", Long.class, adminId);
            jdbcTemplate.update("INSERT INTO " + schema
                + ".t_form_resource_grant(form_def_id, subject_type, subject_id, granted_by) "
                + "VALUES (?, 'USER', ?, ?), (?, 'USER', ?, ?)",
                formId, bobId, adminId, formId, plainUserId, adminId);

            Flyway.configure().dataSource(dataSource).defaultSchema(schema).schemas(schema)
                .locations("classpath:db/migration").load().migrate();

            assertThat(jdbcTemplate.queryForList("SELECT user_id FROM " + schema
                + ".t_form_maintainer WHERE form_def_id = ? ORDER BY user_id",
                Long.class, formId)).containsExactlyInAnyOrder(adminId, bobId)
                .doesNotContain(plainUserId);
            assertThat(jdbcTemplate.queryForObject("SELECT scopeable FROM " + schema
                + ".t_permission WHERE code = 'form:definition:manage'", Boolean.class)).isFalse();
            assertThat(jdbcTemplate.queryForObject("SELECT scope_override FROM " + schema
                + ".t_role_permission WHERE role_id = ? "
                + "AND permission_code = 'form:definition:manage'", String.class, managerRoleId))
                .isNull();
            assertThat(jdbcTemplate.queryForObject("SELECT scopeable FROM " + schema
                + ".t_permission WHERE code = 'form:data:read'", Boolean.class)).isTrue();
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void v43ProtectsTheLastActiveMaintainerOnAV42Database() throws Exception {
        String schema = "last_maintainer_migration_"
            + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure().dataSource(dataSource).defaultSchema(schema).schemas(schema)
                .locations("classpath:db/migration").target("42").load().migrate();
            Long adminId = jdbcTemplate.queryForObject(
                "SELECT id FROM " + schema + ".t_user WHERE username = 'admin'", Long.class);
            Long bobId = jdbcTemplate.queryForObject(
                "SELECT id FROM " + schema + ".t_user WHERE username = 'bob'", Long.class);
            Long formId = jdbcTemplate.queryForObject("INSERT INTO " + schema
                + ".t_form_definition(code, name, schema, settings, status, created_by) "
                + "VALUES ('last_maintainer', 'Last maintainer', '[]', '{}', 'DRAFT', ?) "
                + "RETURNING id", Long.class, adminId);
            jdbcTemplate.update("INSERT INTO " + schema
                + ".t_form_maintainer(form_def_id, user_id, granted_by) VALUES (?, ?, ?)",
                formId, adminId, adminId);

            Flyway.configure().dataSource(dataSource).defaultSchema(schema).schemas(schema)
                .locations("classpath:db/migration").load().migrate();

            assertThatThrownBy(() -> jdbcTemplate.update("UPDATE " + schema
                + ".t_user SET status = 'DISABLED' WHERE id = ?", adminId))
                .isInstanceOf(DataIntegrityViolationException.class);
            jdbcTemplate.update("INSERT INTO " + schema
                + ".t_form_maintainer(form_def_id, user_id, granted_by) VALUES (?, ?, ?)",
                formId, bobId, adminId);
            assertThat(jdbcTemplate.update("UPDATE " + schema
                + ".t_user SET status = 'DISABLED' WHERE id = ?", adminId)).isEqualTo(1);

            jdbcTemplate.update("UPDATE " + schema
                + ".t_user SET status = 'ACTIVE' WHERE id = ?", adminId);
            List<Throwable> outcomes = runConcurrently(adminId,
                () -> jdbcTemplate.update("UPDATE " + schema
                    + ".t_user SET status = 'DISABLED' WHERE id = ?", adminId),
                () -> jdbcTemplate.update("UPDATE " + schema
                    + ".t_user SET status = 'DISABLED' WHERE id = ?", bobId));
            assertThat(outcomes.stream().filter(Objects::isNull).count()).isEqualTo(1L);
            assertThat(outcomes.stream().filter(Objects::nonNull).toList())
                .singleElement().isInstanceOf(DataIntegrityViolationException.class);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + schema
                + ".t_form_maintainer maintenance JOIN " + schema
                + ".t_user user_row ON user_row.id = maintenance.user_id "
                + "WHERE maintenance.form_def_id = ? AND user_row.status = 'ACTIVE'",
                Long.class, formId)).isEqualTo(1L);
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void v46BackfillsFormOptionSourceReferences() {
        String schema = "form_option_source_migration_"
            + UUID.randomUUID().toString().replace("-", "");
        try {
            // 先停在 V45：那时候还没有引用表，表单只能把绑定写在 schema 里。
            Flyway.configure().dataSource(dataSource).defaultSchema(schema).schemas(schema)
                .locations("classpath:db/migration").target("45").load().migrate();
            long sourceId = jdbcTemplate.queryForObject("INSERT INTO " + schema
                + ".t_option_data_source(code, name) VALUES ('ledger', 'Ledger') RETURNING id",
                Long.class);
            long formId = jdbcTemplate.queryForObject("INSERT INTO " + schema
                + ".t_form_definition(code, name, version, schema, settings, status, deleted) "
                + "VALUES ('ref_form', 'Ref form', 1, jsonb_build_array(jsonb_build_object("
                + "'id', 'dept', 'type', 'select', 'label', 'Dept', 'props', jsonb_build_object("
                + "'optionSource', jsonb_build_object('sourceId', ?::bigint)))), "
                + "'{}'::jsonb, 'DRAFT', 0) RETURNING id", Long.class, sourceId);

            Flyway.configure().dataSource(dataSource).defaultSchema(schema).schemas(schema)
                .locations("classpath:db/migration").load().migrate();

            assertThat(jdbcTemplate.queryForList("SELECT source_id FROM " + schema
                + ".t_form_option_source WHERE form_def_id = ?", Long.class, formId))
                .containsExactly(sourceId);
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void v48RotatesOnlySharedWecomPasswordsAndRevokesTheirSessions() {
        String schema = "wecom_password_migration_"
            + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure().dataSource(dataSource).defaultSchema(schema).schemas(schema)
                .locations("classpath:db/migration").target("47").load().migrate();
            long companyId = jdbcTemplate.queryForObject(
                "SELECT id FROM " + schema + ".t_company ORDER BY id LIMIT 1", Long.class);
            long rotatedId = jdbcTemplate.queryForObject("INSERT INTO " + schema
                + ".t_user(employee_no, username, password_hash, display_name, status) "
                + "VALUES ('wx-rotate', 'wx-rotate', crypt('qwer1234', gen_salt('bf', 10)), "
                + "'Rotate', 'ACTIVE') RETURNING id", Long.class);
            long preservedId = jdbcTemplate.queryForObject("INSERT INTO " + schema
                + ".t_user(employee_no, username, password_hash, display_name, status) "
                + "VALUES ('wx-keep', 'wx-keep', crypt('kept-secret', gen_salt('bf', 10)), "
                + "'Keep', 'ACTIVE') RETURNING id", Long.class);
            jdbcTemplate.update("INSERT INTO " + schema
                + ".t_wecom_user_mapping(company_id, wecom_user_id, user_id) VALUES (?, ?, ?)",
                companyId, "wx-rotate", rotatedId);
            jdbcTemplate.update("INSERT INTO " + schema
                + ".t_wecom_user_mapping(company_id, wecom_user_id, user_id) VALUES (?, ?, ?)",
                companyId, "wx-keep", preservedId);
            jdbcTemplate.update("INSERT INTO " + schema
                + ".t_auth_session(user_id, refresh_token_hash, csrf_token_hash, device_name, expires_at) "
                + "VALUES (?, 'rotate-refresh', 'rotate-csrf', 'test', now() + interval '1 day')",
                rotatedId);

            Flyway.configure().dataSource(dataSource).defaultSchema(schema).schemas(schema)
                .locations("classpath:db/migration").load().migrate();

            assertThat(jdbcTemplate.queryForObject("SELECT password_hash = crypt('qwer1234', "
                + "password_hash) FROM " + schema + ".t_user WHERE id = ?", Boolean.class,
                rotatedId)).isFalse();
            assertThat(jdbcTemplate.queryForObject("SELECT password_hash = crypt('kept-secret', "
                + "password_hash) FROM " + schema + ".t_user WHERE id = ?", Boolean.class,
                preservedId)).isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT password_hash FROM " + schema
                + ".t_user WHERE username = 'admin'", String.class))
                .isEqualTo("!ANTFLOW_BOOTSTRAP_REQUIRED!");
            assertThat(jdbcTemplate.queryForObject("SELECT revoked_at IS NOT NULL FROM " + schema
                + ".t_auth_session WHERE user_id = ?", Boolean.class, rotatedId)).isTrue();

            long formId = jdbcTemplate.queryForObject("INSERT INTO " + schema
                + ".t_form_definition(code, name, schema, settings, status) VALUES "
                + "('bi_redact', 'BI redact', '[{\"id\":\"secret\",\"type\":\"text\"},"
                + "{\"id\":\"count\",\"type\":\"number\"}]'::jsonb, '{}'::jsonb, "
                + "'PUBLISHED') RETURNING id", Long.class);
            jdbcTemplate.update("INSERT INTO " + schema
                + ".t_form_definition_version(form_definition_id, version_no, schema, checksum) "
                + "SELECT id, 1, schema, 'test' FROM " + schema
                + ".t_form_definition WHERE id = ?", formId);
            long dataId = jdbcTemplate.queryForObject("INSERT INTO " + schema
                + ".t_form_data(form_def_id, form_def_version, data, status) "
                + "VALUES (?, 1, '{\"secret\":\"private\",\"count\":42,"
                + "\"unknown\":\"hidden\"}'::jsonb, 'SUBMITTED') RETURNING id",
                Long.class, formId);
            assertThat(jdbcTemplate.queryForObject("SELECT value_text FROM " + schema
                + ".v_form_ledger WHERE data_id = ? AND field_id = 'secret'",
                String.class, dataId)).isNull();
            assertThat(jdbcTemplate.queryForObject("SELECT value_json::text FROM " + schema
                + ".v_form_ledger WHERE data_id = ? AND field_id = 'unknown'",
                String.class, dataId)).isNull();
            assertThat(jdbcTemplate.queryForObject("SELECT value_text FROM " + schema
                + ".v_form_ledger WHERE data_id = ? AND field_id = 'count'",
                String.class, dataId)).isEqualTo("42");
            assertThat(jdbcTemplate.queryForObject("SELECT employee_no FROM " + schema
                + ".v_user_catalog WHERE id = ?", String.class, rotatedId)).isNull();
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void ledgerResolvesLabelsFromTheRowsOwnVersionAndJoinsMultiSelectLabels() {
        String schema = "ledger_version_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure().dataSource(dataSource).defaultSchema(schema).schemas(schema)
                .locations("classpath:db/migration").target("48").load().migrate();
            long formId = jdbcTemplate.queryForObject("INSERT INTO " + schema
                + ".t_form_definition(code, name, schema, settings, status) VALUES "
                + "('ledger_v', 'Ledger', '[{\"id\":\"pick\",\"type\":\"multi_select\","
                + "\"label\":\"新下拉\",\"props\":{\"options\":[{\"value\":\"a\","
                + "\"label\":\"X\"},{\"value\":\"b\",\"label\":\"Y\"}]}}]'::jsonb, "
                + "'{}'::jsonb, 'PUBLISHED') RETURNING id", Long.class);
            // v1 是这一行数据当时的 schema；之后字段改名、选项显示名也改了。
            jdbcTemplate.update("INSERT INTO " + schema
                + ".t_form_definition_version(form_definition_id, version_no, schema, checksum) "
                + "VALUES (?, 1, '[{\"id\":\"pick\",\"type\":\"multi_select\","
                + "\"label\":\"旧下拉\",\"props\":{\"options\":[{\"value\":\"a\","
                + "\"label\":\"甲\"},{\"value\":\"b\",\"label\":\"乙\"}]}}]'::jsonb, 'v1')",
                formId);
            jdbcTemplate.update("INSERT INTO " + schema
                + ".t_form_definition_version(form_definition_id, version_no, schema, checksum) "
                + "VALUES (?, 2, '[{\"id\":\"pick\",\"type\":\"multi_select\","
                + "\"label\":\"新下拉\",\"props\":{\"options\":[{\"value\":\"a\","
                + "\"label\":\"X\"},{\"value\":\"b\",\"label\":\"Y\"}]}}]'::jsonb, 'v2')",
                formId);
            long dataId = jdbcTemplate.queryForObject("INSERT INTO " + schema
                + ".t_form_data(form_def_id, form_def_version, data, status) "
                + "VALUES (?, 1, '{\"pick\":[\"a\",\"b\"]}'::jsonb, 'SUBMITTED') RETURNING id",
                Long.class, formId);

            Flyway.configure().dataSource(dataSource).defaultSchema(schema).schemas(schema)
                .locations("classpath:db/migration").load().migrate();

            assertThat(jdbcTemplate.queryForObject("SELECT field_label FROM " + schema
                + ".v_form_ledger WHERE data_id = ? AND field_id = 'pick'",
                String.class, dataId)).isEqualTo("旧下拉");
            assertThat(jdbcTemplate.queryForObject("SELECT value_label FROM " + schema
                + ".v_form_ledger WHERE data_id = ? AND field_id = 'pick'",
                String.class, dataId)).isEqualTo("甲、乙");
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void bindableListsOnlySourcesTheFormReferences() {
        long adminId = userId("admin");
        long formId = insertForm("DRAFT",
            "[{\"id\":\"dept\",\"type\":\"select\",\"label\":\"Dept\",\"props\":{}}]");
        long referenced = insertOptionSource("ref_" + UUID.randomUUID().toString().replace("-", ""));
        long unreferenced = insertOptionSource("unref_" + UUID.randomUUID().toString().replace("-", ""));
        long bound = insertOptionSource("bound_" + UUID.randomUUID().toString().replace("-", ""));
        jdbcTemplate.update("INSERT INTO t_form_option_source(form_def_id, source_id) VALUES (?, ?)",
            formId, referenced);
        jdbcTemplate.update("INSERT INTO t_form_option_source(form_def_id, source_id) VALUES (?, ?)",
            formId, bound);
        // 绑定写在 schema 里、但引用行被撤掉：仍要出现在候选里，否则该字段的版本下拉会空掉。
        jdbcTemplate.update("DELETE FROM t_form_option_source WHERE form_def_id = ? AND source_id = ?",
            formId, bound);
        jdbcTemplate.update("UPDATE t_form_definition SET schema = jsonb_build_array("
            + "jsonb_build_object('id', 'dept', 'type', 'select', 'label', 'Dept', 'props', "
            + "jsonb_build_object('optionSource', jsonb_build_object('sourceId', ?::bigint)))) "
            + "WHERE id = ?", bound, formId);

        PrincipalHolder.set(new PrincipalHolder.Principal(adminId, "admin", List.of("admin")));
        try {
            assertThat(optionSourceService.bindable(formId))
                .extracting(OptionSourceService.BindableSource::id)
                .containsExactlyInAnyOrder(referenced, bound);
        } finally {
            PrincipalHolder.clear();
        }
    }

    @Test
    void retiredVersionLeavesThePickerButKeepsServingBoundForms() {
        long adminId = userId("admin");
        long sourceId = insertOptionSource("retire_" + UUID.randomUUID().toString().replace("-", ""));
        long v1 = optionSourceVersionId(sourceId);
        // 表单钉着 v1（这就是「在用」的来源）。
        long formId = insertForm("DRAFT", optionSchema(sourceId, v1));
        // 再发一版，然后把它停用。
        long v2 = jdbcTemplate.queryForObject("INSERT INTO t_option_data_source_version("
            + "source_id, version_no, status, columns_json, row_count, sha256) "
            + "VALUES (?, 2, 'PUBLISHED', '[\"col\"]'::jsonb, 3, 'deadbeef') RETURNING id",
            Long.class, sourceId);

        PrincipalHolder.set(new PrincipalHolder.Principal(adminId, "admin", List.of("admin")));
        try {
            optionSourceService.disableVersion(sourceId, v2);

            // 停用后的版本不再进候选；表单钉着的 v1 照常在（否则版本下拉会显示空白）。
            assertThat(optionSourceService.bindable(formId))
                .extracting(OptionSourceService.BindableSource::versionId)
                .containsExactly(v1);
            // 读路径完全不受影响：v1 仍是 PUBLISHED，钉着它的表单照常能读候选。
            assertThat(optionSourceService.previewRows(sourceId, v1, 10)).isNotNull();
            // 「最新」回落到仍然可用的 v1。
            assertThat(optionSourceService.detail(sourceId).source().publishedVersionId())
                .isEqualTo(v1);

            optionSourceService.enableVersion(sourceId, v2);
            assertThat(optionSourceService.bindable(formId))
                .extracting(OptionSourceService.BindableSource::versionId)
                .containsExactlyInAnyOrder(v1, v2);
        } finally {
            PrincipalHolder.clear();
        }
    }

    @Test
    void publishingABindingToARetiredVersionIsRefused() {
        long adminId = userId("admin");
        long sourceId = insertOptionSource("retirepub_" + UUID.randomUUID().toString().replace("-", ""));
        // insertOptionSource 建的是 v1（可用），这里再加一个已发布但会被停用的 v2。
        long usable = optionSourceVersionId(sourceId);
        long retired = jdbcTemplate.queryForObject("INSERT INTO t_option_data_source_version("
            + "source_id, version_no, status, columns_json, row_count, sha256) "
            + "VALUES (?, 2, 'PUBLISHED', '[\"col\"]'::jsonb, 1, 'deadbeef') RETURNING id",
            Long.class, sourceId);

        PrincipalHolder.set(new PrincipalHolder.Principal(adminId, "admin", List.of("admin")));
        try {
            optionSourceService.disableVersion(sourceId, retired);

            // 停用只把版本从候选列表里移除是不够的：直接提交带该 versionId 的 schema 再发布就绕过去了。
            long badForm = insertForm("DRAFT", boundOptionSchema(sourceId, retired));
            assertThatThrownBy(() -> formDefinitionService.publish(badForm))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不可绑定")
                // 还要点名是**哪个字段**：一张表可能有十几个下拉绑着不同数据源，
                // 只说"数据源版本不存在"维护人得挨个点开猜（字段标题才是他在表单上看到的东西）。
                .hasMessageContaining("字段「Dept」绑定的数据源版本不存在、未发布或不可绑定");

            // 未停用的版本照常可绑定——别把这道闸做成"一律拒绝"。
            long okForm = insertForm("DRAFT", boundOptionSchema(sourceId, usable));
            assertThat(formDefinitionService.publish(okForm).getStatus()).isEqualTo("PUBLISHED");
        } finally {
            PrincipalHolder.clear();
        }
    }

    @Test
    void formListKeywordSearchWorksAgainstRealPostgres() {
        long adminId = userId("admin");
        long formId = insertForm("DRAFT", VALID_SCHEMA);
        String code = jdbcTemplate.queryForObject(
            "SELECT code FROM t_form_definition WHERE id = ?", String.class, formId);
        PrincipalHolder.set(new PrincipalHolder.Principal(adminId, "admin", List.of("admin")));
        try {
            // 连接串带 stringtype=unspecified：参数以 unknown 送到 PG，CONCAT 少了 ::text 会 500。
            assertThat(formDefinitionService.list(1, 50, code.substring(0, 8), null, adminId, true)
                .getRecords()).extracting(FormDefinitionMapper.Summary::id).contains(formId);
            assertThat(formDefinitionService.list(1, 50, "Integration form", null, adminId, true)
                .getRecords()).extracting(FormDefinitionMapper.Summary::id).contains(formId);
        } finally {
            PrincipalHolder.clear();
        }
    }

    @Test
    void mobileListSearchWorksAgainstRealPostgres() {
        long adminId = userId("admin");
        long formId = insertForm("PUBLISHED", VALID_SCHEMA);
        long dataId = insertSubmittedData(formId, adminId);
        String businessNo = jdbcTemplate.queryForObject(
            "SELECT business_no FROM t_form_data WHERE id = ?", String.class, dataId);

        // 这三个查询都带 keyword；参数没转成 text 时 PG 直接 "could not determine data type"。
        assertThatCode(() -> mobileWorkflowMapper.selectTaskPage(adminId, "todo", "x", null, 20, 0))
            .doesNotThrowAnyException();
        assertThatCode(() -> mobileWorkflowMapper.selectInstancePage(adminId, "x", null, 20, 0))
            .doesNotThrowAnyException();
        // 光"不抛异常"也可能是因为什么都没查到，所以这条要求真的命中。
        assertThat(mobileWorkflowMapper.selectInitiatedPage(adminId, businessNo, null, 50, 0))
            .extracting(MobileWorkflowMapper.InitiatedRow::id)
            .contains(dataId);
    }

    @Test
    void unpublishIsRefusedWhileAFormBindsTheVersion() {
        long adminId = userId("admin");
        long sourceId = insertOptionSource("unpub_" + UUID.randomUUID().toString().replace("-", ""));
        long versionId = optionSourceVersionId(sourceId);
        long formId = insertForm("DRAFT", optionSchema(sourceId, versionId));
        PrincipalHolder.set(new PrincipalHolder.Principal(adminId, "admin", List.of("admin")));
        try {
            assertThatThrownBy(() -> optionSourceService.unpublish(sourceId, versionId))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能取消发布");

            // 解绑之后就能退回待发布——这就是「发布错了」的退路。
            jdbcTemplate.update("UPDATE t_form_definition SET schema = '[]'::jsonb WHERE id = ?", formId);
            assertThat(optionSourceService.unpublish(sourceId, versionId).status()).isEqualTo("DRAFT");
        } finally {
            PrincipalHolder.clear();
        }
    }

    @Test
    void discardDropsOnlyDraftVersions() {
        long adminId = userId("admin");
        long sourceId = insertOptionSource("discard_" + UUID.randomUUID().toString().replace("-", ""));
        long publishedId = optionSourceVersionId(sourceId);
        long draftId = jdbcTemplate.queryForObject("INSERT INTO t_option_data_source_version("
            + "source_id, version_no, status, columns_json, row_count, sha256) "
            + "VALUES (?, 2, 'DRAFT', '[\"col\"]'::jsonb, 2, 'deadbeef') RETURNING id",
            Long.class, sourceId);
        jdbcTemplate.update("INSERT INTO t_option_data_source_row(version_id, row_no, data) "
            + "VALUES (?, 1, '{\"col\":\"a\"}'::jsonb), (?, 2, '{\"col\":\"b\"}'::jsonb)",
            draftId, draftId);

        PrincipalHolder.set(new PrincipalHolder.Principal(adminId, "admin", List.of("admin")));
        try {
            assertThatThrownBy(() -> optionSourceService.discardVersion(sourceId, publishedId))
                .isInstanceOf(BizException.class).hasMessageContaining("先取消发布");

            optionSourceService.discardVersion(sourceId, draftId);

            assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_option_data_source_row WHERE version_id = ?", Long.class, draftId))
                .isZero();
            assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_option_data_source_version WHERE source_id = ? AND status = 'DRAFT'",
                Long.class, sourceId)).isZero();
        } finally {
            PrincipalHolder.clear();
        }
    }

    private long optionSourceVersionId(long sourceId) {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM t_option_data_source_version WHERE source_id = ?", Long.class, sourceId);
    }

    @Test
    void importStoresTheNoteAndBumpsTheOptimisticVersion() {
        long adminId = userId("admin");
        long sourceId = insertOptionSource("note_" + UUID.randomUUID().toString().replace("-", ""));
        PrincipalHolder.set(new PrincipalHolder.Principal(adminId, "admin", List.of("admin")));
        try {
            int before = optionSourceService.detail(sourceId).source().version();

            OptionSourceService.VersionView draft =
                optionSourceService.importDraft(sourceId, null, "a\nb", null, "  新增 b  ");
            assertThat(draft.note()).isEqualTo("新增 b");
            // 导入把草稿整个换掉了（连 versionId 都换），必须递增 source.version：
            // 否则导入前打开的旧页面还能拿旧 version 通过 replaceGrants/replaceForms 的乐观锁。
            assertThat(optionSourceService.detail(sourceId).source().version()).isEqualTo(before + 1);

            // 空白说明按没填处理（列上不存空串）。
            assertThat(optionSourceService.importDraft(sourceId, null, "a", null, "   ").note()).isNull();
        } finally {
            PrincipalHolder.clear();
        }
    }

    @Test
    void republishingAPreviouslyDisabledVersionMakesItAvailableAgain() {
        long adminId = userId("admin");
        long sourceId = insertOptionSource("redis_" + UUID.randomUUID().toString().replace("-", ""));
        long v2 = jdbcTemplate.queryForObject("INSERT INTO t_option_data_source_version("
            + "source_id, version_no, status, columns_json, row_count, sha256) "
            + "VALUES (?, 2, 'PUBLISHED', '[\"col\"]'::jsonb, 1, 'deadbeef') RETURNING id",
            Long.class, sourceId);

        PrincipalHolder.set(new PrincipalHolder.Principal(adminId, "admin", List.of("admin")));
        try {
            // 停用 → 取消发布 → 再发布：取消发布必须把停用标记一起清掉。不清就会留下
            // status=PUBLISHED 但 disabled_at 非空的版本（界面显示"已停用"、list() 认不出「最新」、
            // bindable() 也不给新绑定），用户会以为"我重新发布了却没生效"。
            optionSourceService.disableVersion(sourceId, v2);
            optionSourceService.unpublish(sourceId, v2);
            optionSourceService.publish(sourceId, v2);

            assertThat(jdbcTemplate.queryForObject(
                "SELECT disabled_at FROM t_option_data_source_version WHERE id = ?",
                OffsetDateTime.class, v2)).isNull();
            assertThat(optionSourceService.detail(sourceId).source().publishedVersionId()).isEqualTo(v2);
            assertThat(optionSourceService.detail(sourceId).versions().stream()
                .filter(version -> version.id() == v2).findFirst().orElseThrow().publishedByName())
                .isNotBlank();
        } finally {
            PrincipalHolder.clear();
        }
    }

    @Test
    void versionDiffReportsAddedRemovedRowsAndColumnChanges() {
        long adminId = userId("admin");
        long sourceId = insertOptionSource("diff_" + UUID.randomUUID().toString().replace("-", ""));
        long v1 = optionSourceVersionId(sourceId);
        // v1 有三行、两列——「备注」这一列在 v2 里没了，用来验证列变化也会被报出来。
        jdbcTemplate.update("UPDATE t_option_data_source_version SET columns_json = "
            + "'[\"选项\",\"备注\"]'::jsonb WHERE id = ?", v1);
        jdbcTemplate.update("INSERT INTO t_option_data_source_row(version_id, row_no, data) VALUES "
            + "(?, 1, '{\"选项\":\"a\"}'::jsonb), (?, 2, '{\"选项\":\"b\"}'::jsonb), "
            + "(?, 3, '{\"选项\":\"c\"}'::jsonb)", v1, v1, v1);

        PrincipalHolder.set(new PrincipalHolder.Principal(adminId, "admin", List.of("admin")));
        try {
            long v2 = optionSourceService.importDraft(sourceId, null, "b\nc\nd", null, "加 d、去 a").id();

            OptionSourceService.VersionDiff diff = optionSourceService.diffVersions(sourceId, v2);
            assertThat(diff.previousVersionNo()).isEqualTo(1);
            assertThat(diff.added()).containsExactly(Map.of("选项", "d"));
            assertThat(diff.removed()).containsExactly(Map.of("选项", "a"));
            assertThat(diff.addedTotal()).isEqualTo(1);
            assertThat(diff.removedTotal()).isEqualTo(1);
            assertThat(diff.columnChanges()).containsExactly("删除列：备注");
            assertThat(diff.truncated()).isFalse();

            // 第一版没有上一版：回空 diff，不是错误（界面据此把「对比」按钮藏掉）。
            OptionSourceService.VersionDiff first = optionSourceService.diffVersions(sourceId, v1);
            assertThat(first.previousVersionNo()).isNull();
            assertThat(first.added()).isEmpty();
            assertThat(first.removed()).isEmpty();
        } finally {
            PrincipalHolder.clear();
        }
    }

    /**
     * 「可引用表单」清单只拦**新增**绑定：撤销引用不该让已经绑着它的表单失去保存能力，
     * 否则表单 1/14 那种状态（引用被撤销、字段仍绑着）会直接卡死。
     */
    @Test
    void optionReferenceListOnlyGatesNewBindings() {
        long adminId = userId("admin");
        long sourceA = insertOptionSource("ref_gate_a");
        long sourceB = insertOptionSource("ref_gate_b");
        long versionA = optionSourceVersionId(sourceA);
        long versionB = optionSourceVersionId(sourceB);
        // 表单本来就绑着 A，且 A 对该表单**没有**引用行（模拟撤销引用后的状态）。
        long formId = insertForm("PUBLISHED", boundOptionSchema(sourceA, versionA));
        String code = jdbcTemplate.queryForObject(
            "SELECT code FROM t_form_definition WHERE id = ?", String.class, formId);
        PrincipalHolder.set(new PrincipalHolder.Principal(adminId, "admin", List.of("admin")));
        try {
            // 原样再存一次：旧绑定照常放行
            assertThatCode(() -> formDefinitionService.saveDraft(formId, code, "Integration form",
                null, jsonFor(boundOptionSchema(sourceA, versionA)), null, adminId))
                .doesNotThrowAnyException();

            // 但"同一个源被绑到**新字段**上"必须拦住：只比 sourceId 集合的话，(A, S) 在旧新集合里
            // 都有，新增的 (新字段, S) 会被当成旧绑定放行——差集得按「字段 + 源」这个绑定对算。
            String copiedToNewField = "[{\"id\":\"dept\",\"type\":\"select\",\"label\":\"Dept\","
                + "\"props\":{\"optionSource\":{\"sourceId\":" + sourceA + ",\"versionId\":" + versionA
                + ",\"valueColumn\":\"col\",\"labelColumn\":\"col\"}}},"
                + "{\"id\":\"dept2\",\"type\":\"select\",\"label\":\"Dept2\",\"props\":{\"optionSource\":"
                + "{\"sourceId\":" + sourceA + ",\"versionId\":" + versionA
                + ",\"valueColumn\":\"col\",\"labelColumn\":\"col\"}}}]";
            assertThatThrownBy(() -> formDefinitionService.saveDraft(formId, code, "Integration form",
                null, jsonFor(copiedToNewField), null, adminId))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("可引用表单");

            // 新增一条绑定到同样没有被引用的 B → 拒绝
            String both = "[{\"id\":\"dept\",\"type\":\"select\",\"label\":\"Dept\",\"props\":"
                + "{\"optionSource\":{\"sourceId\":" + sourceB + ",\"versionId\":" + versionB
                + ",\"valueColumn\":\"col\",\"labelColumn\":\"col\"}}}]";
            assertThatThrownBy(() -> formDefinitionService.saveDraft(formId, code, "Integration form",
                null, jsonFor(both), null, adminId))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("可引用表单");

            // 把 B 加回该表单的可引用清单后放行
            jdbcTemplate.update("INSERT INTO t_form_option_source(form_def_id, source_id, created_by) "
                + "VALUES (?, ?, ?)", formId, sourceB, adminId);
            assertThatCode(() -> formDefinitionService.saveDraft(formId, code, "Integration form",
                null, jsonFor(both), null, adminId))
                .doesNotThrowAnyException();
        } finally {
            PrincipalHolder.clear();
            jdbcTemplate.update("DELETE FROM t_form_option_source WHERE form_def_id = ?", formId);
        }
    }

    private Object jsonFor(String schema) {
        try {
            return flowJson.readValue(schema, Object.class);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    // ===== V40 换算里三条「只记录、不修改」的存量权限事实（见 docs/DECISIONS.md）=====
    // 这三条都不是 bug 修复，而是把**当前行为**钉住：哪天有人要改，得先让它们红。

    /** ① form:authorization:manage 在 V40 是 scopeable=false，换算时旧 t_role.data_scope 被丢成 NULL。 */
    @Test
    void v40DropsTheOldDataScopeOfTheFormAuthorizationCapability() {
        String schema = "v40_scope_" + UUID.randomUUID().toString().replace("-", "");
        try {
            migrateTo(schema, "39");
            long roleId = jdbcTemplate.queryForObject("INSERT INTO " + schema
                + ".t_role(code, name, data_scope) "
                + "VALUES ('scope_probe', 'probe', 'DEPARTMENT') RETURNING id", Long.class);
            jdbcTemplate.update("INSERT INTO " + schema
                + ".t_role_permission(role_id, permission_code) "
                + "VALUES (?, 'form.authorization.manage')", roleId);

            migrateTo(schema, null);

            assertThat(jdbcTemplate.queryForObject("SELECT scope_override FROM " + schema
                + ".t_role_permission WHERE role_id = ? "
                + "AND permission_code = 'form:authorization:manage'", String.class, roleId))
                .as("scopeable=false 的能力在换算时拿不到覆盖值，旧部门范围就此丢失")
                .isNull();
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    /** ② 旧的 create 与 design 都收敛到同一个写能力，粒度没了（拿到 create 就等于能 design）。 */
    @Test
    void v40MergesFormCreateAndDesignIntoOneWriteCapability() {
        String schema = "v40_merge_" + UUID.randomUUID().toString().replace("-", "");
        try {
            migrateTo(schema, "39");
            long roleId = jdbcTemplate.queryForObject("INSERT INTO " + schema
                + ".t_role(code, name, data_scope) "
                + "VALUES ('create_probe', 'probe', 'ALL') RETURNING id", Long.class);
            jdbcTemplate.update("INSERT INTO " + schema
                + ".t_role_permission(role_id, permission_code) "
                + "VALUES (?, 'form.definition.create')", roleId);

            migrateTo(schema, null);

            assertThat(jdbcTemplate.queryForList("SELECT permission_code FROM " + schema
                + ".t_role_permission WHERE role_id = ?", String.class, roleId))
                .as("只授过 create 的角色，换算后拿到的是合并后的写能力")
                .contains("form:definition:manage");
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    /** ③ 内置角色改名（user → employee）没有冲突保护：已有 employee 角色时整个 V40 回滚。 */
    @Test
    void v40RenameRollsBackWhenAnEmployeeRoleAlreadyExists() {
        String schema = "v40_rename_" + UUID.randomUUID().toString().replace("-", "");
        try {
            migrateTo(schema, "39");
            jdbcTemplate.update("INSERT INTO " + schema
                + ".t_role(code, name, data_scope) VALUES ('employee', '自建员工', 'ALL')");

            assertThatThrownBy(() -> migrateTo(schema, null))
                .as("t_role.code 唯一，裸 UPDATE 撞上自建 employee 会让整个 V40 回滚")
                .hasMessageContaining("employee");
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private void migrateTo(String schema, String target) {
        var configuration = Flyway.configure().dataSource(dataSource)
            .defaultSchema(schema).schemas(schema).locations("classpath:db/migration");
        if (target != null) {
            configuration = configuration.target(target);
        }
        configuration.load().migrate();
    }

    private static String optionSchema(long sourceId, long versionId) {
        return "[{\"id\":\"dept\",\"type\":\"select\",\"label\":\"Dept\",\"props\":"
            + "{\"optionSource\":{\"sourceId\":" + sourceId + ",\"versionId\":" + versionId + "}}}]";
    }

    /** 带完整列映射的绑定，能过 requireVersion 的列检查——用来测"能不能发布"这类到了后面的路径。 */
    private static String boundOptionSchema(long sourceId, long versionId) {
        return "[{\"id\":\"dept\",\"type\":\"select\",\"label\":\"Dept\",\"props\":{\"optionSource\":"
            + "{\"sourceId\":" + sourceId + ",\"versionId\":" + versionId
            + ",\"valueColumn\":\"col\",\"labelColumn\":\"col\"}}}]";
    }

    private long insertOptionSource(String code) {
        long sourceId = jdbcTemplate.queryForObject("INSERT INTO t_option_data_source(code, name) "
            + "VALUES (?, 'Integration source') RETURNING id", Long.class, code);
        jdbcTemplate.update("INSERT INTO t_option_data_source_version(source_id, version_no, "
            + "status, columns_json, row_count, sha256) "
            + "VALUES (?, 1, 'PUBLISHED', '[\"col\"]'::jsonb, 2, 'deadbeef')", sourceId);
        return sourceId;
    }

    @Test
    void v2StartBindsImmutableVersionsNodeInstanceAndFormRevision() {
        long adminId = userId("admin");
        long bobId = userId("bob");
        long formId = insertForm("DRAFT", VALID_SCHEMA);
        long processId = insertProcess(formId, "DRAFT", twoApprovalFlow(bobId, adminId));
        String code = jdbcTemplate.queryForObject(
            "SELECT code FROM t_form_definition WHERE id = ?", String.class, formId);
        PrincipalHolder.set(new PrincipalHolder.Principal(adminId, "admin", List.of("admin")));
        try {
            publishService.publish(formId, processId);
            Map<String, Object> started = processEngine.start(
                new StartCmd(code, Map.of("subject", "versioned"), Map.of()), adminId);
            long instanceId = ((Number) started.get("instanceId")).longValue();
            long firstTaskId = ((List<?>) started.get("firstTaskIds")).stream()
                .map(Number.class::cast).mapToLong(Number::longValue).findFirst().orElseThrow();

            assertThat(jdbcTemplate.queryForMap("""
                SELECT engine_version, process_definition_version_id,
                       current_form_revision_id, current_node_instance_id, round_no
                FROM t_process_instance WHERE id = ?
                """, instanceId))
                .containsEntry("engine_version", 2)
                .containsEntry("round_no", 1)
                .allSatisfy((key, value) -> assertThat(value).isNotNull());
            assertThat(jdbcTemplate.queryForMap("""
                SELECT node_instance_id, action_form_revision_id
                FROM t_task WHERE id = ?
                """, firstTaskId)).allSatisfy((key, value) -> assertThat(value).isNotNull());

            jdbcTemplate.update("""
                UPDATE t_process_definition SET process =
                  '{"id":"root","type":"ROOT","children":null}'::jsonb
                WHERE id = ?
                """, processId);
            processEngine.approve(new CompleteCmd(firstTaskId, "APPROVE", "ok", null), bobId);

            assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_task
                WHERE proc_inst_id = ? AND node_id = 'a2' AND assignee_id = ?
                  AND status = 'PENDING'
                """, Long.class, instanceId, adminId)).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_form_data_revision
                WHERE form_data_id = ?
                """, Long.class, ((Number) started.get("formDataId")).longValue()))
                .isEqualTo(1L);
        } finally {
            PrincipalHolder.clear();
        }
    }

    @Test
    void publishedDefinitionsAndRevisionAcceptEscapedJsonText() {
        long adminId = userId("admin");
        long bobId = userId("bob");
        String schema = """
            [{"id":"subject","type":"text","label":"第一行\\n路径 C:\\\\temp"}]
            """;
        long formId = insertForm("DRAFT", schema);
        long processId = insertProcess(formId, "DRAFT", twoApprovalFlow(bobId, adminId));
        String code = jdbcTemplate.queryForObject(
            "SELECT code FROM t_form_definition WHERE id = ?", String.class, formId);
        PrincipalHolder.set(new PrincipalHolder.Principal(adminId, "admin", List.of("admin")));
        try {
            publishService.publish(formId, processId);
            Map<String, Object> started = processEngine.start(new StartCmd(code,
                Map.of("subject", "第一行\n路径 C:\\temp"), Map.of()), adminId);

            assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_form_definition_version
                WHERE form_definition_id = ? AND length(checksum) = 64
                """, Long.class, formId)).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_process_definition_version
                WHERE process_definition_id = ? AND length(checksum) = 64
                """, Long.class, processId)).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_form_data_revision
                WHERE form_data_id = ? AND length(checksum) = 64
                """, Long.class, ((Number) started.get("formDataId")).longValue()))
                .isEqualTo(1L);
        } finally {
            PrincipalHolder.clear();
        }
    }

    @Test
    void customBusinessNumbersIncrementPerFormAndAppearInStartResult() {
        long adminId = userId("admin");
        long bobId = userId("bob");
        long formId = insertForm("DRAFT", VALID_SCHEMA);
        jdbcTemplate.update("""
            UPDATE t_form_definition SET settings = ?::jsonb WHERE id = ?
            """, """
            {"businessNumber":{"enabled":true,"namespace":"ITNUM","reset":"DAILY","parts":[
              {"type":"LITERAL","value":"-"},
              {"type":"DATE","pattern":"yyyyMMdd"},
              {"type":"LITERAL","value":"-"},
              {"type":"SEQUENCE","width":4}]}}
            """, formId);
        long processId = insertProcess(formId, "DRAFT", approvalFlow(bobId));
        String code = jdbcTemplate.queryForObject(
            "SELECT code FROM t_form_definition WHERE id = ?", String.class, formId);
        PrincipalHolder.set(new PrincipalHolder.Principal(adminId, "admin", List.of("admin")));
        try {
            publishService.publish(formId, processId);
            String first = String.valueOf(processEngine.start(
                new StartCmd(code, Map.of("subject", "first"), Map.of()), adminId)
                .get("businessNo"));
            String second = String.valueOf(processEngine.start(
                new StartCmd(code, Map.of("subject", "second"), Map.of()), adminId)
                .get("businessNo"));

            assertThat(first).matches("ITNUM-[0-9]{8}-0001");
            assertThat(second).matches("ITNUM-[0-9]{8}-0002");
            assertThat(second.substring(0, 15)).isEqualTo(first.substring(0, 15));
        } finally {
            PrincipalHolder.clear();
        }
    }

    @Test
    void v2AllSignWaitsForEveryoneBeforeCreatingDownstreamTask() {
        long adminId = userId("admin");
        long bobId = userId("bob");
        StartedV2 started = startV2(allSignFlow(adminId, bobId, adminId), adminId);
        long adminTask = taskIdForAssignee(started.instanceId(), "a1", adminId);
        long bobTask = taskIdForAssignee(started.instanceId(), "a1", bobId);

        processEngine.approve(new CompleteCmd(adminTask, "APPROVE", "ok", null), adminId);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM t_task WHERE proc_inst_id = ? AND node_id = 'a2'
            """, Long.class, started.instanceId())).isZero();

        processEngine.approve(new CompleteCmd(bobTask, "APPROVE", "ok", null), bobId);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM t_task
            WHERE proc_inst_id = ? AND node_id = 'a2' AND status = 'PENDING'
            """, Long.class, started.instanceId())).isEqualTo(1L);
    }

    @Test
    void v2ConcurrentAnySignAdvancesOnceAndCancelsLosingTask() throws Exception {
        long adminId = userId("admin");
        long bobId = userId("bob");
        StartedV2 started = startV2(anySignFlow(adminId, bobId, adminId), adminId);
        long adminTask = taskIdForAssignee(started.instanceId(), "a1", adminId);
        long bobTask = taskIdForAssignee(started.instanceId(), "a1", bobId);

        List<Throwable> outcomes = runConcurrently(adminId,
            () -> processEngine.approve(
                new CompleteCmd(adminTask, "APPROVE", "ok", null), adminId),
            () -> processEngine.approve(
                new CompleteCmd(bobTask, "APPROVE", "ok", null), bobId));

        assertThat(outcomes.stream().filter(Objects::isNull).count())
            .withFailMessage(() -> outcomes.stream()
                .map(error -> error == null ? "success" : error.toString()).toList().toString())
            .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM t_task
            WHERE proc_inst_id = ? AND node_id = 'a2' AND status = 'PENDING'
            """, Long.class, started.instanceId())).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForList("""
            SELECT status FROM t_task WHERE id IN (?, ?) ORDER BY id
            """, String.class, adminTask, bobTask))
            .containsExactlyInAnyOrder("APPROVED", "CANCELLED");
    }

    @Test
    void v2AnySignRejectsImmediatelyAndCancelsOtherApprovers() {
        long adminId = userId("admin");
        long bobId = userId("bob");
        StartedV2 started = startV2(anySignFlow(adminId, bobId, adminId), adminId);
        long adminTask = taskIdForAssignee(started.instanceId(), "a1", adminId);
        long bobTask = taskIdForAssignee(started.instanceId(), "a1", bobId);

        processEngine.reject(new CompleteCmd(adminTask, "REJECT", "no", null), adminId);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM t_task WHERE id = ?", String.class, bobTask))
            .isEqualTo("CANCELLED");
        assertThat(pendingReworkCount(started.instanceId())).isEqualTo(1L);
        assertThat(pendingTaskCount(started.instanceId(), "a2")).isZero();
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM t_workflow_outbox
            WHERE aggregate_id = ? AND event_type = 'TASK_CANCELLED'
              AND recipient_id = ?
            """, Long.class, started.instanceId(), bobId)).isEqualTo(1L);
    }

    @Test
    void v2AllSignKeepsOtherTasksAfterRejectAndRejectsOnlyAfterEveryoneActs() {
        long adminId = userId("admin");
        long bobId = userId("bob");
        StartedV2 started = startV2(allSignFlow(adminId, bobId, adminId), adminId);
        long adminTask = taskIdForAssignee(started.instanceId(), "a1", adminId);
        long bobTask = taskIdForAssignee(started.instanceId(), "a1", bobId);

        processEngine.reject(new CompleteCmd(adminTask, "REJECT", "no", "a2"), adminId);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM t_task WHERE id = ?", String.class, bobTask))
            .isEqualTo("PENDING");
        assertThat(pendingReworkCount(started.instanceId())).isZero();

        processEngine.approve(new CompleteCmd(bobTask, "APPROVE", "ok", null), bobId);

        assertThat(pendingReworkCount(started.instanceId())).isEqualTo(1L);
        assertThat(pendingTaskCount(started.instanceId(), "a2")).isZero();
    }

    @Test
    void concurrentDuplicateApprovalsAdvanceSameTaskOnce() throws Exception {
        long adminId = userId("admin");
        long bobId = userId("bob");
        StartedV2 started = startV2(twoApprovalFlow(bobId, adminId), adminId);
        long taskId = taskIdForAssignee(started.instanceId(), "a1", bobId);

        List<Throwable> outcomes = runConcurrently(adminId,
            () -> processEngine.approve(
                new CompleteCmd(taskId, "APPROVE", "ok", null), bobId),
            () -> processEngine.approve(
                new CompleteCmd(taskId, "APPROVE", "ok", null), bobId));

        assertOneSuccessOneTaskConflict(outcomes);
        assertThat(pendingTaskCount(started.instanceId(), "a2")).isEqualTo(1L);
    }

    @Test
    void concurrentApproveRejectLeavesExactlyOneOutcome() throws Exception {
        long adminId = userId("admin");
        long bobId = userId("bob");
        StartedV2 started = startV2(twoApprovalFlow(bobId, adminId), adminId);
        long taskId = taskIdForAssignee(started.instanceId(), "a1", bobId);

        List<Throwable> outcomes = runConcurrently(adminId,
            () -> processEngine.approve(
                new CompleteCmd(taskId, "APPROVE", "ok", null), bobId),
            () -> processEngine.reject(
                new CompleteCmd(taskId, "REJECT", "no", null), bobId));

        assertOneSuccessOneTaskConflict(outcomes);
        assertThat(pendingTaskCount(started.instanceId(), "a2") == 1L)
            .isNotEqualTo(pendingReworkCount(started.instanceId()) == 1L);
    }

    @Test
    void duplicateParallelApprovalsCreateOneJoinTask() throws Exception {
        long adminId = userId("admin");
        long bobId = userId("bob");
        StartedV2 started = startV2(parallelFlow(adminId, bobId, adminId), adminId);
        long firstTask = taskIdForAssignee(started.instanceId(), "a1", adminId);
        long secondTask = taskIdForAssignee(started.instanceId(), "a2", bobId);

        assertOneSuccessOneTaskConflict(runConcurrently(adminId,
            () -> processEngine.approve(
                new CompleteCmd(firstTask, "APPROVE", "ok", null), adminId),
            () -> processEngine.approve(
                new CompleteCmd(firstTask, "APPROVE", "ok", null), adminId)));
        assertThat(pendingTaskCount(started.instanceId(), "a3")).isZero();

        assertOneSuccessOneTaskConflict(runConcurrently(adminId,
            () -> processEngine.approve(
                new CompleteCmd(secondTask, "APPROVE", "ok", null), bobId),
            () -> processEngine.approve(
                new CompleteCmd(secondTask, "APPROVE", "ok", null), bobId)));
        assertThat(pendingTaskCount(started.instanceId(), "a3")).isEqualTo(1L);
    }

    @Test
    void parallelApproveRejectRaceNeverCreatesJoinAndReworkTogether() throws Exception {
        long adminId = userId("admin");
        long bobId = userId("bob");
        StartedV2 started = startV2(parallelFlow(adminId, bobId, adminId), adminId);
        long approveTask = taskIdForAssignee(started.instanceId(), "a1", adminId);
        long rejectTask = taskIdForAssignee(started.instanceId(), "a2", bobId);

        List<Throwable> outcomes = runConcurrently(adminId,
            () -> processEngine.approve(
                new CompleteCmd(approveTask, "APPROVE", "ok", null), adminId),
            () -> processEngine.reject(
                new CompleteCmd(rejectTask, "REJECT", "no", null), bobId));

        assertThat(outcomes.stream().filter(Objects::isNull).count()).isBetween(1L, 2L);
        assertThat(outcomes.stream().filter(Objects::nonNull).toList())
            .allSatisfy(error -> assertThat(error)
                .isInstanceOfSatisfying(BizException.class, exception ->
                    assertThat(exception.getCode()).isEqualTo("TASK_NOT_PENDING")));
        assertThat(pendingReworkCount(started.instanceId())).isEqualTo(1L);
        assertThat(pendingTaskCount(started.instanceId(), "a3")).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM t_task WHERE id = ?", String.class, rejectTask))
            .isEqualTo("REJECTED");
        Map<String, Object> approval = jdbcTemplate.queryForMap(
            "SELECT status, operation_kind FROM t_task WHERE id = ?", approveTask);
        assertThat(approval.get("status")).isIn("APPROVED", "CANCELLED");
        if ("APPROVED".equals(approval.get("status"))) {
            assertThat(approval.get("operation_kind")).isEqualTo("INVALIDATED");
        }
    }

    @Test
    void v2ParallelAllRejectInvalidatesEarlierApprovalAndReturnsToStarter() {
        long adminId = userId("admin");
        long bobId = userId("bob");
        StartedV2 started = startV2(parallelFlow(adminId, bobId, adminId), adminId);
        long first = jdbcTemplate.queryForObject("""
            SELECT id FROM t_task WHERE proc_inst_id = ? AND node_id = 'a1'
            """, Long.class, started.instanceId());
        long second = jdbcTemplate.queryForObject("""
            SELECT id FROM t_task WHERE proc_inst_id = ? AND node_id = 'a2'
            """, Long.class, started.instanceId());

        processEngine.approve(new CompleteCmd(first, "APPROVE", "ok", null), adminId);
        processEngine.reject(new CompleteCmd(second, "REJECT", "no", null), bobId);

        assertThat(jdbcTemplate.queryForObject("""
            SELECT operation_kind FROM t_task WHERE id = ?
            """, String.class, first)).isEqualTo("INVALIDATED");
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM t_task
            WHERE proc_inst_id = ? AND task_type = 'REWORK' AND status = 'PENDING'
            """, Long.class, started.instanceId())).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM t_workflow_outbox
            WHERE aggregate_id = ? AND event_type = 'APPROVAL_INVALIDATED'
            """, Long.class, started.instanceId())).isEqualTo(1L);
    }

    @Test
    void v2RatioSignWaitsForEveryoneThenSettlesAgainstTheThreshold() {
        long adminId = userId("admin");
        List<Long> approvers = List.of(insertUser("ratio-a-" + UUID.randomUUID()),
            insertUser("ratio-b-" + UUID.randomUUID()),
            insertUser("ratio-c-" + UUID.randomUUID()),
            insertUser("ratio-d-" + UUID.randomUUID()),
            insertUser("ratio-e-" + UUID.randomUUID()));

        StartedV2 passed = startV2(multiSignFlow("RATIO", approvers, 60, adminId), adminId);
        for (int index = 0; index < 3; index++) {
            long userId = approvers.get(index);
            processEngine.approve(new CompleteCmd(
                taskIdForAssignee(passed.instanceId(), "a1", userId), "APPROVE", "ok", null),
                userId);
            assertThat(pendingTaskCount(passed.instanceId(), "a2")).isZero();
        }
        for (int index = 3; index < 5; index++) {
            long userId = approvers.get(index);
            processEngine.reject(new CompleteCmd(
                taskIdForAssignee(passed.instanceId(), "a1", userId), "REJECT", "no", null),
                userId);
            if (index == 3) assertThat(pendingTaskCount(passed.instanceId(), "a2")).isZero();
        }
        assertThat(pendingTaskCount(passed.instanceId(), "a2")).isEqualTo(1L);
        assertThat(pendingReworkCount(passed.instanceId())).isZero();

        StartedV2 rejected = startV2(multiSignFlow("RATIO", approvers, 60, adminId), adminId);
        for (int index = 0; index < 2; index++) {
            long userId = approvers.get(index);
            processEngine.approve(new CompleteCmd(
                taskIdForAssignee(rejected.instanceId(), "a1", userId), "APPROVE", "ok", null),
                userId);
            assertThat(pendingReworkCount(rejected.instanceId())).isZero();
        }
        for (int index = 2; index < 5; index++) {
            long userId = approvers.get(index);
            processEngine.reject(new CompleteCmd(
                taskIdForAssignee(rejected.instanceId(), "a1", userId), "REJECT", "no", null),
                userId);
            if (index < 4) assertThat(pendingReworkCount(rejected.instanceId())).isZero();
        }
        assertThat(pendingReworkCount(rejected.instanceId())).isEqualTo(1L);
    }

    @Test
    void v2SequentialSignCreatesTasksInConfiguredOrder() {
        long adminId = userId("admin");
        long firstCreated = insertUser("sequential-a-" + UUID.randomUUID());
        long secondCreated = insertUser("sequential-b-" + UUID.randomUUID());
        long thirdCreated = insertUser("sequential-c-" + UUID.randomUUID());
        List<Long> configuredOrder = List.of(thirdCreated, firstCreated, secondCreated);
        StartedV2 started = startLegacyV2(
            multiSignFlow("SEQUENTIAL", configuredOrder, null, adminId), adminId);

        for (long approver : configuredOrder) {
            assertThat(jdbcTemplate.queryForList("""
                SELECT assignee_id FROM t_task
                WHERE proc_inst_id = ? AND node_id = 'a1' AND status = 'PENDING'
                """, Long.class, started.instanceId())).containsExactly(approver);
            processEngine.approve(new CompleteCmd(
                taskIdForAssignee(started.instanceId(), "a1", approver),
                "APPROVE", "ok", null), approver);
        }

        assertThat(pendingTaskCount(started.instanceId(), "a2")).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForList("""
            SELECT responsible_user_id FROM t_node_participant participant
            JOIN t_process_node_instance node ON node.id = participant.node_instance_id
            WHERE node.proc_inst_id = ? AND node.node_id = 'a1'
            ORDER BY participant.sequence_no
            """, Long.class, started.instanceId())).containsExactlyElementsOf(configuredOrder);
    }

    @Test
    void v2AnyParallelRejectsOnlyAfterEveryBranchFailsAndHidesReworkFromStartedList() {
        long adminId = userId("admin");
        long first = insertUser("any-reject-a-" + UUID.randomUUID());
        long second = insertUser("any-reject-b-" + UUID.randomUUID());
        StartedV2 started = startV2(parallelFlow("ANY", first, second, adminId), adminId);

        processEngine.reject(new CompleteCmd(
            taskIdForAssignee(started.instanceId(), "a1", first), "REJECT", "no", null), first);
        assertThat(pendingReworkCount(started.instanceId())).isZero();
        assertThat(pendingTaskCount(started.instanceId(), "a2")).isEqualTo(1L);

        processEngine.reject(new CompleteCmd(
            taskIdForAssignee(started.instanceId(), "a2", second), "REJECT", "no", null), second);
        assertThat(pendingReworkCount(started.instanceId())).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT current_node_id FROM t_process_instance WHERE id = ?
            """, String.class, started.instanceId())).isEqualTo("__rework__");
        assertThat(mobileWorkflowMapper.selectInstancePage(adminId, null, null, 20, 0))
            .extracting(MobileWorkflowMapper.InstanceRow::id)
            .doesNotContain(started.instanceId());
    }

    @Test
    void v2UsesExplicitNodeFallbackAndFreezesDelegationOnTask() {
        long adminId = userId("admin");
        long bobId = userId("bob");
        long unavailable = insertUser("disabled-approver-" + UUID.randomUUID());
        jdbcTemplate.update("UPDATE t_user SET status = 'DISABLED' WHERE id = ?", unavailable);

        StartedV2 nodeFallback = startV2(
            approvalWithFallbackFlow(unavailable, bobId), adminId);
        assertThat(jdbcTemplate.queryForList("""
            SELECT assignee_id FROM t_task
            WHERE proc_inst_id = ? AND status = 'PENDING'
            """, Long.class, nodeFallback.instanceId())).containsExactly(bobId);

        long unavailableFallback = insertUser("disabled-fallback-" + UUID.randomUUID());
        jdbcTemplate.update("UPDATE t_user SET status = 'DISABLED' WHERE id = ?", unavailableFallback);
        long formId = insertForm("DRAFT", VALID_SCHEMA);
        long processId = insertProcess(formId, "DRAFT", approvalWithFallbackFlow(unavailable,
            unavailableFallback));
        String code = jdbcTemplate.queryForObject(
            "SELECT code FROM t_form_definition WHERE id = ?", String.class, formId);
        PrincipalHolder.set(new PrincipalHolder.Principal(adminId, "admin", List.of("admin")));
        try {
            publishService.publish(formId, processId);
            assertThatThrownBy(() -> processEngine.start(
                new StartCmd(code, Map.of("subject", "no implicit admin"), Map.of()), adminId))
                .isInstanceOfSatisfying(BizException.class,
                    error -> assertThat(error.getCode()).isEqualTo("FALLBACK_UNAVAILABLE"));
            assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_process_instance WHERE proc_def_id = ?
                """, Long.class, processId)).isZero();
        } finally {
            PrincipalHolder.clear();
        }

        long principal = insertUser("delegation-principal-" + UUID.randomUUID());
        long agent = insertUser("delegation-agent-" + UUID.randomUUID());
        jdbcTemplate.update("""
            INSERT INTO t_approval_delegation(
                principal_id, agent_id, starts_at, ends_at, created_by)
            VALUES (?, ?, now() - interval '1 minute', now() + interval '1 hour', ?)
            """, principal, agent, adminId);
        StartedV2 delegated = startV2(approvalFlow(principal), adminId);
        assertThat(jdbcTemplate.queryForMap("""
            SELECT task.assignee_id, task.delegated_from,
                   participant.responsible_user_id, participant.actual_user_id
            FROM t_task task
            JOIN t_node_participant participant
              ON participant.node_instance_id = task.node_instance_id
             AND participant.sequence_no = task.sequence_no
            WHERE task.proc_inst_id = ? AND task.status = 'PENDING'
            """, delegated.instanceId()))
            .containsEntry("assignee_id", agent)
            .containsEntry("delegated_from", principal)
            .containsEntry("responsible_user_id", principal)
            .containsEntry("actual_user_id", agent);
    }

    @Test
    void v2ApprovalCanBeRecalledUntilDownstreamActsAndCompletedTimeoutIsIdempotent() {
        long adminId = userId("admin");
        long bobId = userId("bob");
        StartedV2 recalled = startV2(twoApprovalFlow(bobId, adminId), adminId);
        long firstTask = taskIdForAssignee(recalled.instanceId(), "a1", bobId);
        processEngine.approve(new CompleteCmd(firstTask, "APPROVE", "ok", null), bobId);
        long oldDownstream = taskIdForAssignee(recalled.instanceId(), "a2", adminId);

        processEngine.recallApproval(firstTask, bobId);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM t_task WHERE id = ?", String.class, firstTask)).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM t_task WHERE id = ?", String.class, oldDownstream))
            .isEqualTo("CANCELLED");

        processEngine.approve(new CompleteCmd(firstTask, "APPROVE", "ok again", null), bobId);
        long newDownstream = taskIdForAssignee(recalled.instanceId(), "a2", adminId);
        processEngine.approve(new CompleteCmd(newDownstream, "APPROVE", "ok", null), adminId);
        assertThatThrownBy(() -> processEngine.recallApproval(firstTask, bobId))
            .isInstanceOf(BizException.class);

        StartedV2 timed = startV2(timeoutFlow(bobId), adminId);
        long timedTask = taskIdForAssignee(timed.instanceId(), "a1", bobId);
        long timeoutJob = jdbcTemplate.queryForObject("""
            SELECT id FROM t_workflow_job WHERE task_id = ? AND job_type = 'TASK_TIMEOUT'
            """, Long.class, timedTask);
        processEngine.approve(new CompleteCmd(timedTask, "APPROVE", "manual", null), bobId);
        jdbcTemplate.update("UPDATE t_workflow_job SET status = 'RUNNING' WHERE id = ?", timeoutJob);

        assertThat(processEngine.completeTaskTimeout(timeoutJob)).isFalse();
        assertThat(jdbcTemplate.queryForMap("""
            SELECT status, last_error FROM t_workflow_job WHERE id = ?
            """, timeoutJob)).containsEntry("status", "SUCCEEDED")
            .containsEntry("last_error", "task already completed");
    }

    @Test
    void v2ResubmitSupportsFullReplayAndDiffContinue() {
        long adminId = userId("admin");
        long first = insertUser("resubmit-a-" + UUID.randomUUID());
        long second = insertUser("resubmit-b-" + UUID.randomUUID());

        StartedV2 unchanged = startV2(
            parallelResubmitFlow("DIFF_CONTINUE", first, second, adminId), adminId);
        rejectParallelAfterFirstApproval(unchanged.instanceId(), first, second);
        processEngine.resubmitRework(pendingReworkTaskId(unchanged.instanceId()), adminId);
        assertThat(pendingTaskCount(unchanged.instanceId(), "a1")).isZero();
        assertThat(pendingTaskCount(unchanged.instanceId(), "a2")).isEqualTo(1L);

        StartedV2 changed = startV2(
            parallelResubmitFlow("DIFF_CONTINUE", first, second, adminId), adminId);
        rejectParallelAfterFirstApproval(changed.instanceId(), first, second);
        jdbcTemplate.update("UPDATE t_form_data SET data = ?::jsonb WHERE id = ?",
            "{\"subject\":\"changed\"}", changed.formDataId());
        processEngine.resubmitRework(pendingReworkTaskId(changed.instanceId()), adminId);
        assertThat(pendingTaskCount(changed.instanceId(), "a1")).isEqualTo(1L);
        assertThat(pendingTaskCount(changed.instanceId(), "a2")).isEqualTo(1L);

        StartedV2 full = startV2(
            parallelResubmitFlow("FULL", first, second, adminId), adminId);
        rejectParallelAfterFirstApproval(full.instanceId(), first, second);
        processEngine.resubmitRework(pendingReworkTaskId(full.instanceId()), adminId);
        assertThat(pendingTaskCount(full.instanceId(), "a1")).isEqualTo(1L);
        assertThat(pendingTaskCount(full.instanceId(), "a2")).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT round_no FROM t_process_instance WHERE id = ?",
            Integer.class, full.instanceId())).isEqualTo(2);
        assertThat(mobileWorkflowMapper.selectApprovalTasks(full.instanceId()))
            .extracting(MobileWorkflowMapper.ApprovalRow::roundNo)
            .contains(1, 2);
    }

    @Test
    void v2RejectOnlyAllowsConfiguredUpstreamTargetAndStartsANewRound() {
        long adminId = userId("admin");
        long first = insertUser("reject-target-a-" + UUID.randomUUID());
        long second = insertUser("reject-target-b-" + UUID.randomUUID());
        long third = insertUser("reject-target-c-" + UUID.randomUUID());
        StartedV2 started = startV2(threeApprovalFlow(first, second, third), adminId);
        processEngine.approve(new CompleteCmd(
            taskIdForAssignee(started.instanceId(), "a1", first), "APPROVE", "ok", null), first);
        processEngine.approve(new CompleteCmd(
            taskIdForAssignee(started.instanceId(), "a2", second), "APPROVE", "ok", null), second);
        long thirdTask = taskIdForAssignee(started.instanceId(), "a3", third);

        assertThatThrownBy(() -> processEngine.reject(
            new CompleteCmd(thirdTask, "REJECT", "wrong target", "a2"), third))
            .isInstanceOfSatisfying(BizException.class,
                error -> assertThat(error.getCode()).isEqualTo("BAD_REJECT_TARGET"));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM t_task WHERE id = ?", String.class, thirdTask)).isEqualTo("PENDING");

        processEngine.reject(new CompleteCmd(thirdTask, "REJECT", "restart", "a1"), third);
        assertThat(jdbcTemplate.queryForMap("""
            SELECT current_node_id, round_no FROM t_process_instance WHERE id = ?
            """, started.instanceId())).containsEntry("current_node_id", "a1")
            .containsEntry("round_no", 2);
        assertThat(pendingTaskCount(started.instanceId(), "a1")).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM t_task
            WHERE proc_inst_id = ? AND node_id = 'a1' AND status = 'APPROVED'
              AND operation_kind IS DISTINCT FROM 'INVALIDATED'
            """, Long.class, started.instanceId())).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM t_task
            WHERE proc_inst_id = ? AND node_id = 'a2' AND status = 'APPROVED'
              AND operation_kind = 'INVALIDATED'
            """, Long.class, started.instanceId())).isEqualTo(1L);
    }

    @Test
    void wecomActiveJobConstraintAndRestartRecoveryWorkOnPostgres() {
        long companyId = jdbcTemplate.queryForObject(
            "SELECT id FROM t_company ORDER BY id LIMIT 1", Long.class);
        long adminId = userId("admin");
        long firstId = jdbcTemplate.queryForObject("""
            INSERT INTO t_wecom_sync_job(company_id, initiated_by) VALUES (?, ?) RETURNING id
            """, Long.class, companyId, adminId);
        List<Long> duplicate = jdbcTemplate.queryForList("""
            INSERT INTO t_wecom_sync_job(company_id, initiated_by) VALUES (?, ?)
            ON CONFLICT (company_id) WHERE status IN ('PENDING', 'RUNNING') DO NOTHING
            RETURNING id
            """, Long.class, companyId, adminId);

        assertThat(duplicate).isEmpty();
        wecomService.failInterruptedJobs();
        assertThat(jdbcTemplate.queryForMap("""
            SELECT status, message FROM t_wecom_sync_job WHERE id = ?
            """, firstId)).containsEntry("status", "FAILED")
            .containsEntry("message", "服务已重启，请重新同步");

        long nextId = jdbcTemplate.queryForObject("""
            INSERT INTO t_wecom_sync_job(company_id, initiated_by) VALUES (?, ?) RETURNING id
            """, Long.class, companyId, adminId);
        jdbcTemplate.update("DELETE FROM t_wecom_sync_job WHERE id IN (?, ?)", firstId, nextId);
    }

    @Test
    void wecomScheduleMigratesWithSafeDefaultsAndClaimsOnceAfterActiveJobFinishes() {
        long companyId = jdbcTemplate.queryForObject(
            "INSERT INTO t_company(name) VALUES (?) RETURNING id", Long.class,
            "schedule-" + UUID.randomUUID());
        try {
            jdbcTemplate.update("""
                INSERT INTO t_wecom_config(company_id, corp_id, secret_encrypted)
                VALUES (?, 'ww-schedule-test', 'encrypted')
                """, companyId);
            assertThat(jdbcTemplate.queryForMap("""
                SELECT schedule_enabled, schedule_time, schedule_mode, schedule_last_run_date
                FROM t_wecom_config WHERE company_id = ?
                """, companyId))
                .containsEntry("schedule_enabled", false)
                .containsEntry("schedule_mode", "INCREMENTAL")
                .containsEntry("schedule_last_run_date", null);

            long adminId = userId("admin");
            PrincipalHolder.set(new PrincipalHolder.Principal(adminId, "admin", List.of("admin")));
            try {
                WecomService.SettingsDto saved = wecomService.saveSettings(companyId,
                    "ww-schedule-test", "", null, null, false, false, false,
                    true, "03:00:00", "FULL");
                assertThat(saved.scheduleEnabled()).isTrue();
                assertThat(saved.scheduleTime()).isEqualTo("03:00:00");
                assertThat(saved.scheduleMode()).isEqualTo("FULL");
                assertThat(wecomService.settings(companyId)).isEqualTo(saved);
            } finally {
                PrincipalHolder.clear();
            }
            LocalDate today = LocalDate.of(2026, 9, 8);
            assertThat(ReflectionTestUtils.<Long>invokeMethod(wecomService, "claimScheduledJob",
                companyId, today, LocalTime.of(2, 59))).isNull();
            Long first = ReflectionTestUtils.invokeMethod(wecomService, "claimScheduledJob",
                companyId, today, LocalTime.NOON);
            assertThat(first).isNotNull();
            assertThat(jdbcTemplate.queryForObject("""
                SELECT sync_mode FROM t_wecom_sync_job WHERE id = ?
                """, String.class, first)).isEqualTo("FULL");
            assertThat(ReflectionTestUtils.<Long>invokeMethod(wecomService, "claimScheduledJob",
                companyId, today, LocalTime.NOON)).isNull();

            jdbcTemplate.update("""
                UPDATE t_wecom_config SET schedule_last_run_date = NULL WHERE company_id = ?
                """, companyId);
            assertThat(ReflectionTestUtils.<Long>invokeMethod(wecomService, "claimScheduledJob",
                companyId, today, LocalTime.NOON)).isNull();
            assertThat(jdbcTemplate.queryForObject("""
                SELECT schedule_last_run_date FROM t_wecom_config WHERE company_id = ?
                """, LocalDate.class, companyId)).isNull();

            jdbcTemplate.update("UPDATE t_wecom_sync_job SET status = 'SUCCESS' WHERE id = ?", first);
            Long afterActive = ReflectionTestUtils.invokeMethod(wecomService, "claimScheduledJob",
                companyId, today, LocalTime.NOON);
            assertThat(afterActive).isNotNull();
            jdbcTemplate.update("UPDATE t_wecom_sync_job SET status = 'FAILED' WHERE id = ?",
                afterActive);
            assertThat(ReflectionTestUtils.<Long>invokeMethod(wecomService, "claimScheduledJob",
                companyId, today, LocalTime.NOON)).isNull();
        } finally {
            jdbcTemplate.update("DELETE FROM t_company WHERE id = ?", companyId);
        }
    }

    @Test
    void performanceIndexesExist() {
        assertThat(jdbcTemplate.queryForList("""
            SELECT indexname FROM pg_indexes
            WHERE schemaname = 'public' AND indexname IN (
              'idx_task_history_instance_created',
              'idx_process_instance_started_by_started_at',
              'idx_process_instance_status_started_at',
              'idx_role_permission_permission_role')
            """, String.class)).containsExactlyInAnyOrder(
                "idx_task_history_instance_created",
                "idx_process_instance_started_by_started_at",
                "idx_process_instance_status_started_at",
                "idx_role_permission_permission_role");
    }

    @Test
    void workflowHistoryIsAppendOnlyAtDatabaseBoundary() {
        StartedV2 started = startV2(approvalFlow(userId("bob")), userId("admin"));
        long historyId = jdbcTemplate.queryForObject("""
            SELECT id FROM t_task_history WHERE proc_inst_id = ? ORDER BY id LIMIT 1
            """, Long.class, started.instanceId());

        assertThatThrownBy(() -> jdbcTemplate.update(
            "UPDATE t_task_history SET comment = 'tampered' WHERE id = ?", historyId))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbcTemplate.update(
            "DELETE FROM t_task_history WHERE id = ?", historyId))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("append-only");
    }

    @Test
    void optimizedAdminListsExecuteAgainstPostgres() {
        long adminId = userId("admin");
        long formId = insertForm("DRAFT", VALID_SCHEMA);
        PrincipalHolder.set(new PrincipalHolder.Principal(adminId, "admin", List.of("admin")));
        try {
            assertThat(roleAdminService.roles()).isNotEmpty();
            assertThat(userService.listAuthorizedPage(null, null, false, 1, 20).getRecords())
                .isNotEmpty();
            assertThat(formDefinitionMapper.selectSummaryPage(Page.of(1, 20), null, null,
                adminId, true).getRecords())
                .extracting(FormDefinitionMapper.Summary::id).contains(formId);
        } finally {
            PrincipalHolder.clear();
        }
    }

    @Test
    void approvalSummaryCountsByFormAndNarrowsToTheCallersScope() {
        long adminId = userId("admin");
        long bobId = userId("bob");
        long formId = insertForm("PUBLISHED", VALID_SCHEMA);
        long processId = insertProcess(formId, "PUBLISHED", twoApprovalFlow(bobId, adminId));
        long scopedUser = insertUser("report-scoped");
        long scopedRole = insertRole("report_scoped");
        long emptyUser = insertUser("report-empty");
        long emptyRole = insertRole("report_empty");
        OffsetDateTime started = OffsetDateTime.now().minusDays(2).withNano(0);
        java.util.List<Long> dataIds = new java.util.ArrayList<>();
        try {
            jdbcTemplate.update("INSERT INTO t_role_permission(role_id, permission_code, scope_override) "
                + "VALUES (?, 'form:data:read', 'SELF')", scopedRole);
            assignRole(scopedUser, scopedRole);
            // DEPARTMENT 但这个用户没有部门 → 谓词集合为空 → 必须恒假（fail-closed）
            jdbcTemplate.update("INSERT INTO t_role_permission(role_id, permission_code, scope_override) "
                + "VALUES (?, 'form:data:read', 'DEPARTMENT')", emptyRole);
            assignRole(emptyUser, emptyRole);

            // admin 两单（一通过一进行中）、bob 一单（驳回）、受限账号自己一单（通过，用来验收窄）
            dataIds.add(insertSubmittedData(formId, adminId));
            dataIds.add(insertSubmittedData(formId, adminId));
            dataIds.add(insertSubmittedData(formId, bobId));
            dataIds.add(insertSubmittedData(formId, scopedUser));
            insertInstance(processId, dataIds.get(0), "APPROVED", adminId, started, started.plusHours(2));
            insertInstance(processId, dataIds.get(1), "RUNNING", adminId, started, null);
            insertInstance(processId, dataIds.get(2), "REJECTED", bobId, started, started.plusHours(4));
            insertInstance(processId, dataIds.get(3), "APPROVED", scopedUser, started,
                started.plusHours(2));

            LocalDate from = started.toLocalDate();
            LocalDate to = LocalDate.now();
            // 一律按这张表单收窄：同一个容器里还留着别的用例造的数据，不筛就等着顺序一变换个数字。
            java.util.List<Long> onlyThisForm = java.util.List.of(formId);
            setPrincipal(adminId);
            ReportService.ApprovalSummary all = reportService.summary(from, to, onlyThisForm, null, 0);

            assertThat(all.totals().started()).isEqualTo(4);
            assertThat(all.totals().approved()).isEqualTo(2);
            assertThat(all.totals().rejected()).isEqualTo(1);
            assertThat(all.totals().running()).isEqualTo(1);
            // 通过率分母只算已决：2 / (2 + 1)
            assertThat(all.totals().approvalRate()).isEqualTo(66.7);
            // 平均耗时只算已终态且有完成时间的：2h、4h、2h → 2.666…→ 2.7（进行中那条不算）
            assertThat(all.totals().avgDurationHours()).isEqualTo(2.7);
            assertThat(all.byForm()).hasSize(1);
            assertThat(all.byForm().get(0).formName()).isEqualTo("Integration form");
            assertThat(all.byForm().get(0).started()).isEqualTo(4);
            // 每一条日期都有值：缺数据的日期补 0，否则折线断成几截
            assertThat(all.byDay()).hasSize((int) java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1);
            assertThat(all.byDay().get(0).date()).isEqualTo(from.toString());
            assertThat(all.byDay().stream().filter(day -> day.started() > 0).findFirst().orElseThrow()
                .started()).isEqualTo(4);

            // 受限（本人）：只看得到自己那一单——范围必须真的收窄，不能等于全量
            setPrincipal(scopedUser);
            ReportService.ApprovalSummary own = reportService.summary(from, to, onlyThisForm, null, 0);
            assertThat(own.totals().started()).isEqualTo(1);
            assertThat(own.totals().approved()).isEqualTo(1);
            assertThat(own.totals().approvalRate()).isEqualTo(100.0);
            assertThat(own.byForm()).hasSize(1);
            assertThat(own.byForm().get(0).started()).isEqualTo(1);
            assertThat(own.byDay().stream().mapToLong(ReportService.DayRow::started).sum()).isEqualTo(1);

            // 空范围：恒假，一条都不该漏出来
            setPrincipal(emptyUser);
            ReportService.ApprovalSummary none = reportService.summary(from, to, onlyThisForm, null, 0);
            assertThat(none.totals().started()).isZero();
            assertThat(none.byForm()).isEmpty();
            assertThat(none.byDepartment()).isEmpty();
        } finally {
            PrincipalHolder.clear();
            // 顺序要紧：实例 → 表单数据 → 表单定义 → 角色与用户（created_by 有外键）。
            jdbcTemplate.update("DELETE FROM t_process_instance WHERE proc_def_id = ?", processId);
            for (Long dataId : dataIds) {
                jdbcTemplate.update("DELETE FROM t_form_data WHERE id = ?", dataId);
            }
            jdbcTemplate.update("DELETE FROM t_process_definition WHERE id = ?", processId);
            jdbcTemplate.update("DELETE FROM t_form_definition WHERE id = ?", formId);
            jdbcTemplate.update("DELETE FROM t_user_role WHERE user_id IN (?, ?)", scopedUser, emptyUser);
            jdbcTemplate.update("DELETE FROM t_role WHERE id IN (?, ?)", scopedRole, emptyRole);
            jdbcTemplate.update("DELETE FROM t_user WHERE id IN (?, ?)", scopedUser, emptyUser);
            authorizationService.evict(scopedUser);
            authorizationService.evict(emptyUser);
        }
    }

    /** 报表只看实例的状态/时间/发起人/发起部门，直接插一行比走引擎快也更可控。 */
    private long insertInstance(long processDefId, long formDataId, String status, long startedBy,
                                OffsetDateTime startedAt, OffsetDateTime finishedAt) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO t_process_instance(proc_def_id, form_data_id, status, started_by,
                                           started_at, finished_at)
            VALUES (?, ?, ?, ?, ?, ?) RETURNING id
            """, Long.class, processDefId, formDataId, status, startedBy, startedAt, finishedAt);
    }

    @Test
    void submitterKeywordFiltersByPersonAndNeverWidensToEverything() {
        long adminId = userId("admin");
        long formId = insertForm("PUBLISHED", VALID_SCHEMA);
        long mine = insertSubmittedData(formId, adminId);
        setPrincipal(adminId);
        try {
            // 姓名命中（种子里 admin 的 display_name 是 "AntFlow Admin"）
            assertThat(formDataService.adminPage(1, 20, formId, null, null, "AntFlow").getRecords())
                .extracting(com.antflow.form.runtime.FormData::getId).contains(mine);
            // 工号也命中
            assertThat(formDataService.adminPage(1, 20, formId, null, null, "000001").getRecords())
                .extracting(com.antflow.form.runtime.FormData::getId).contains(mine);
            // 查不到人 → **零条**。这条是安全语义：若实现改成"先查 id 集合、集合空就省略条件"，
            // 这里会退化成"返回范围内的全部记录"，等于把关键字筛选变成越权放大镜。
            assertThat(formDataService.adminPage(1, 20, formId, null, null, "查无此人").getRecords())
                .isEmpty();
            // LIKE 通配符被转义：敲一个 % 不该匹配到所有人。
            assertThat(formDataService.adminPage(1, 20, formId, null, null, "%").getRecords())
                .isEmpty();
        } finally {
            PrincipalHolder.clear();
        }
    }

    /**
     * 台账按**每条记录自己的表单版本**解释：表单升版改了标签与选项之后，旧单据仍显示当时的标签与
     * 当时的选项名，新单据显示新的。用真库跑是因为这件事整个就是那段 COALESCE 的 join 链——单测把
     * mapper mock 掉只能断言"调用了 selectSchemas"。
     */
    @Test
    void ledgerExplainsEachRecordByItsOwnFormVersion() {
        long adminId = userId("admin");
        String v1 = "[{\"id\":\"craft\",\"type\":\"select\",\"label\":\"工艺项\","
            + "\"props\":{\"options\":[{\"value\":\"option_1\",\"label\":\"车削\"}]}}]";
        String v2 = "[{\"id\":\"craft\",\"type\":\"select\",\"label\":\"工艺\","
            + "\"props\":{\"options\":[{\"value\":\"option_1\",\"label\":\"镗削\"}]}}]";
        long formId = insertForm("PUBLISHED", v1);
        long oldData = submittedSelectData(formId, 1, adminId);
        // 升版：当前定义改成 v2，同时把 v1 的 schema 快照留在版本表里（这正是 COALESCE 的第二栏）。
        jdbcTemplate.update("""
            INSERT INTO t_form_definition_version(form_definition_id, version_no, schema, checksum)
            VALUES (?, 1, ?::jsonb, 'v1')
            """, formId, v1);
        jdbcTemplate.update("UPDATE t_form_definition SET schema = ?::jsonb, version = 2 WHERE id = ?",
            v2, formId);
        long newData = submittedSelectData(formId, 2, adminId);
        setPrincipal(adminId);
        try {
            var rows = formDataService.adminPage(1, 50, formId, null, null).getRecords();

            assertThat(fieldValuesOf(rows, oldData)).containsExactly(
                new com.antflow.form.runtime.FormData.FieldValue("craft", "工艺项", "option_1", "车削", "车削"));
            assertThat(fieldValuesOf(rows, newData)).containsExactly(
                new com.antflow.form.runtime.FormData.FieldValue("craft", "工艺", "option_1", "镗削", "镗削"));
        } finally {
            PrincipalHolder.clear();
            jdbcTemplate.update("DELETE FROM t_form_data WHERE id IN (?, ?)", oldData, newData);
            jdbcTemplate.update("DELETE FROM t_form_definition_version WHERE form_definition_id = ?", formId);
            jdbcTemplate.update("DELETE FROM t_form_definition WHERE id = ?", formId);
        }
    }

    private long submittedSelectData(long formId, int formDefVersion, long creatorId) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO t_form_data(form_def_id, form_def_version, business_no, data, status, created_by)
            VALUES (?, ?, lpad(nextval('seq_business_no')::text, 12, '0'),
                    '{"craft":"option_1"}'::jsonb, 'SUBMITTED', ?)
            RETURNING id
            """, Long.class, formId, formDefVersion, creatorId);
    }

    private static List<com.antflow.form.runtime.FormData.FieldValue> fieldValuesOf(
        List<com.antflow.form.runtime.FormData> rows, long dataId) {
        return rows.stream().filter(row -> row.getId() == dataId).findFirst().orElseThrow()
            .getFieldValues();
    }

    @Test
    void exportRowsAndCountAreNarrowedByTheCallersScope() {
        // 导出必须与列表吃同一层行级范围（DataPermissionPolicyHandler 是按 mapper 语句 id
        // 显式开启的，count 走 selectPage → 同样被覆盖）。这里就把"会不会导出得比看得到更多"钉住。
        long adminId = userId("admin");
        long bobId = userId("bob");
        long formId = insertForm("PUBLISHED", VALID_SCHEMA);
        long scopedUser = insertUser("export-scoped");
        long scopedRole = insertRole("export_scoped");
        long mine = insertSubmittedData(formId, scopedUser);
        long theirs = insertSubmittedData(formId, bobId);
        try {
            // 行级范围跟着**读**能力走，所以受限角色两个能力都要有（只有导出权限会被挡在 403，
            // 见下面那条断言）。
            jdbcTemplate.update("INSERT INTO t_role_permission(role_id, permission_code, scope_override) "
                + "VALUES (?, 'form:data:read', 'SELF'), (?, 'form:data:export', 'SELF')",
                scopedRole, scopedRole);
            assignRole(scopedUser, scopedRole);

            setPrincipal(adminId);
            assertThat(formDataService.exportRows(formId, null, null, null, null))
                .extracting(com.antflow.form.runtime.FormData::getId)
                .contains(mine, theirs);
            assertThat(formDataService.countForExport(formId, null, null, null, null)).isEqualTo(2);
            // 预览计数必须跟着**时间范围**走：少了它，"预览 2 行、实际导出 0 行"就出现了。
            assertThat(formDataService.countForExport(formId, null, null,
                java.time.OffsetDateTime.now().plusDays(1), null)).isZero();

            // 受限（本人）：只拿得到自己那条，预览的行数也必须是 1——提示的数字与实际导出必须一致
            setPrincipal(scopedUser);
            assertThat(formDataService.exportRows(formId, null, null, null, null))
                .extracting(com.antflow.form.runtime.FormData::getId)
                .containsExactly(mine);
            assertThat(formDataService.countForExport(formId, null, null, null, null)).isEqualTo(1);

            // 提交人关键字同样不能把范围放大：查不到人 → 一条都不导
            assertThat(formDataService.exportRows(formId, null, "查无此人", null, null)).isEmpty();
            assertThat(formDataService.countForExport(formId, null, "查无此人", null, null)).isZero();

            // 只有导出权限（没有读）：控制器会直接 403，而不是给一个空文件
            long exportOnly = insertUser("export-only");
            long exportOnlyRole = insertRole("export_only");
            try {
                jdbcTemplate.update("INSERT INTO t_role_permission(role_id, permission_code, "
                    + "scope_override) VALUES (?, 'form:data:export', 'SELF')", exportOnlyRole);
                assignRole(exportOnly, exportOnlyRole);
                setPrincipal(exportOnly);
                assertThatThrownBy(() -> authorizationService.requirePermission(
                    PermissionCodes.FORM_DATA_READ))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            } finally {
                jdbcTemplate.update("DELETE FROM t_user_role WHERE user_id = ?", exportOnly);
                jdbcTemplate.update("DELETE FROM t_role WHERE id = ?", exportOnlyRole);
                jdbcTemplate.update("DELETE FROM t_user WHERE id = ?", exportOnly);
                authorizationService.evict(exportOnly);
            }
        } finally {
            PrincipalHolder.clear();
            jdbcTemplate.update("DELETE FROM t_form_data WHERE id IN (?, ?)", mine, theirs);
            jdbcTemplate.update("DELETE FROM t_form_definition WHERE id = ?", formId);
            jdbcTemplate.update("DELETE FROM t_user_role WHERE user_id = ?", scopedUser);
            jdbcTemplate.update("DELETE FROM t_role WHERE id = ?", scopedRole);
            jdbcTemplate.update("DELETE FROM t_user WHERE id = ?", scopedUser);
            authorizationService.evict(scopedUser);
        }
    }

    @Test
    void allScopeUserSearchAlsoReturnsMembersWithoutADepartment() {
        // ALL 范围不能被"按部门列举"收窄：manageableDepartments 只返回**存在**的部门 id，
        // 拿它做 dept_id IN (...) 会把 dept_id IS NULL 的成员（种子里 admin/bob 就是）挡在外面，
        // 而单条的 inCurrentDataScope 对 ALL 是放行的——两条路径口径不一致，通讯录搜人时会直接看出来。
        long userId = insertUser("all-scope-people-" + UUID.randomUUID());
        long roleId = insertRole("all_scope_people_" + UUID.randomUUID().toString().replace("-", ""));
        long deptless = jdbcTemplate.queryForObject(
            "SELECT id FROM t_user WHERE dept_id IS NULL ORDER BY id LIMIT 1", Long.class);
        assertThat(deptless).as("库里得有一个没有部门的账号，否则这条用例测不到东西").isNotNull();
        try {
            assignRole(userId, roleId);
            jdbcTemplate.update("INSERT INTO t_role_permission(role_id, permission_code, scope_override) "
                + "VALUES (?, 'org:user:read', 'ALL')", roleId);
            setPrincipal(userId);

            assertThat(userService.listAuthorizedPage(null, null, false, 1, 100).getRecords())
                .extracting(User::getId)
                .contains(deptless);
        } finally {
            PrincipalHolder.clear();
        }
    }

    /**
     * 搜部门名也要出人，且是**命中部门及其全部下级**的成员。用真库跑是因为整件事就是一条 ltree 子查询：
     * mock 掉 JdbcTemplate 只能断言拼进去的字符串，`<@` 写错、path 没带上级这类错照样"通过"。
     */
    @Test
    void userKeywordSearchMatchesDepartmentNameIncludingDescendants() {
        long adminId = userId("admin");
        long companyId = jdbcTemplate.queryForObject(
            "SELECT id FROM t_company ORDER BY id LIMIT 1", Long.class);
        String token = UUID.randomUUID().toString().replace("-", "");
        String keyword = "zk" + token.substring(0, 8);
        String otherTag = "zo" + token.substring(8, 16);
        String rootPath = "ds" + token.substring(16, 26);
        // 只让**父部门**的名字带上关键词：子部门的人能被搜出来，才说明走的是"含下级"而不是同名。
        long parentDept = jdbcTemplate.queryForObject("""
            INSERT INTO t_department(company_id, path, name) VALUES (?, CAST(? AS ltree), ?)
            RETURNING id
            """, Long.class, companyId, rootPath, "检索总部" + keyword);
        long childDept = jdbcTemplate.queryForObject("""
            INSERT INTO t_department(company_id, path, name) VALUES (?, CAST(? AS ltree), ?)
            RETURNING id
            """, Long.class, companyId, rootPath + ".a", "研发一组" + otherTag);
        long unrelatedDept = insertDepartment(companyId, "财务部" + otherTag);

        long childMember = insertUser("ds-child-" + token.substring(0, 6));
        long unrelatedMember = insertUser("ds-unrelated-" + token.substring(0, 6));
        long nameMember = insertUser("ds-by-name-" + token.substring(0, 6));
        long scopedUser = insertUser("ds-scoped-" + token.substring(0, 6));
        long scopedRole = insertRole("ds_scoped_" + token.substring(0, 8));
        jdbcTemplate.update("UPDATE t_user SET dept_id = ? WHERE id = ?", childDept, childMember);
        jdbcTemplate.update("UPDATE t_user SET dept_id = ? WHERE id = ?",
            unrelatedDept, unrelatedMember);
        jdbcTemplate.update("UPDATE t_user SET display_name = ? WHERE id = ?",
            "员工" + keyword, nameMember);
        try {
            PrincipalHolder.set(new PrincipalHolder.Principal(adminId, "admin", List.of("admin")));
            Set<Long> found = userService.listAuthorizedPage(keyword, null, false, 1, 50).getRecords()
                .stream().map(User::getId).collect(java.util.stream.Collectors.toSet());
            assertThat(found).contains(childMember, nameMember);
            assertThat(found).doesNotContain(unrelatedMember);

            // 新增的 OR 分支不能把数据范围冲掉：受限账号搜同一个词，命中的部门成员一个都不该漏进来。
            jdbcTemplate.update("UPDATE t_user SET display_name = ? WHERE id = ?",
                "受限" + keyword, scopedUser);
            jdbcTemplate.update("INSERT INTO t_role_permission(role_id, permission_code, scope_override)"
                + " VALUES (?, 'org:user:read', 'SELF')", scopedRole);
            assignRole(scopedUser, scopedRole);
            setPrincipal(scopedUser);
            assertThat(userService.listAuthorizedPage(keyword, null, false, 1, 50).getRecords())
                .extracting(User::getId).containsExactly(scopedUser);
        } finally {
            PrincipalHolder.clear();
            jdbcTemplate.update("DELETE FROM t_user_role WHERE user_id = ?", scopedUser);
            jdbcTemplate.update("DELETE FROM t_role WHERE id = ?", scopedRole);
            jdbcTemplate.update("DELETE FROM t_user WHERE id IN (?, ?, ?, ?)",
                childMember, unrelatedMember, nameMember, scopedUser);
            jdbcTemplate.update("DELETE FROM t_department WHERE id IN (?, ?, ?)",
                childDept, parentDept, unrelatedDept);
            authorizationService.evict(scopedUser);
        }
    }

    @Test
    void wecomLoginOverrideAndDepartmentLeaderOrderingExecuteAgainstPostgres() {
        long adminId = userId("admin");
        long companyId = jdbcTemplate.queryForObject(
            "SELECT id FROM t_company ORDER BY id LIMIT 1", Long.class);
        long departmentId = insertDepartment(companyId, "企微账号测试部");
        long leaderId = insertUser("zz-wecom-leader-" + UUID.randomUUID());
        long memberId = insertUser("aa-wecom-member-" + UUID.randomUUID());
        UUID sessionId = UUID.randomUUID();
        jdbcTemplate.update("UPDATE t_user SET dept_id = ?, status = 'DISABLED' WHERE id IN (?, ?)",
            departmentId, leaderId, memberId);
        jdbcTemplate.update("""
            INSERT INTO t_wecom_user_mapping(
                company_id, wecom_user_id, user_id, wecom_status, directory_present)
            VALUES (?, ?, ?, 4, true), (?, ?, ?, 4, true)
            """, companyId, "leader-" + leaderId, leaderId,
            companyId, "member-" + memberId, memberId);
        jdbcTemplate.update("""
            INSERT INTO t_department_leader(department_id, user_id) VALUES (?, ?)
            """, departmentId, leaderId);
        jdbcTemplate.update("""
            INSERT INTO t_auth_session(id, user_id, refresh_token_hash, csrf_token_hash,
                                       device_name, expires_at)
            VALUES (?, ?, ?, ?, 'test', now() + interval '1 hour')
            """, sessionId, leaderId, UUID.randomUUID().toString(), UUID.randomUUID().toString());

        PrincipalHolder.set(new PrincipalHolder.Principal(adminId, "admin", List.of("admin")));
        try {
            WecomService wecomTarget = AopTestUtils.getTargetObject(wecomService);
            List<User> users = userService.listAuthorizedPage(
                null, departmentId, false, 1, 20).getRecords();
            assertThat(users).extracting(User::getId).containsExactly(leaderId, memberId);
            assertThat(users.get(0).getDepartmentLeader()).isTrue();
            assertThat(users.get(0).getWecomStatus()).isEqualTo(4);

            assertThat(userService.setWecomLoginAccess(leaderId, true).getStatus())
                .isEqualTo("ACTIVE");
            assertThat(jdbcTemplate.queryForObject("""
                SELECT login_enabled_override FROM t_wecom_user_mapping WHERE user_id = ?
                """, Boolean.class, leaderId)).isTrue();
            ReflectionTestUtils.invokeMethod(wecomTarget, "applyLoginStatus",
                companyId, List.of(leaderId));
            assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM t_user WHERE id = ?", String.class, leaderId))
                .isEqualTo("ACTIVE");

            userService.setWecomLoginAccess(leaderId, false);
            assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM t_user WHERE id = ?", String.class, leaderId))
                .isEqualTo("DISABLED");
            assertThat(jdbcTemplate.queryForObject(
                "SELECT revoked_at IS NOT NULL FROM t_auth_session WHERE id = ?",
                Boolean.class, sessionId)).isTrue();

            UUID hardDisabledSessionId = UUID.randomUUID();
            jdbcTemplate.update("""
                UPDATE t_wecom_user_mapping
                SET wecom_status = 2, login_enabled_override = true
                WHERE user_id = ?
                """, leaderId);
            jdbcTemplate.update("UPDATE t_user SET status = 'ACTIVE' WHERE id = ?", leaderId);
            jdbcTemplate.update("""
                INSERT INTO t_auth_session(id, user_id, refresh_token_hash, csrf_token_hash,
                                           device_name, expires_at)
                VALUES (?, ?, ?, ?, 'test', now() + interval '1 hour')
                """, hardDisabledSessionId, leaderId, UUID.randomUUID().toString(),
                UUID.randomUUID().toString());
            ReflectionTestUtils.invokeMethod(wecomTarget, "applyLoginStatus",
                companyId, List.of(leaderId));
            assertThat(jdbcTemplate.queryForMap("""
                SELECT user_row.status, session.revoked_at IS NOT NULL AS revoked
                FROM t_user user_row
                JOIN t_auth_session session ON session.user_id = user_row.id
                WHERE user_row.id = ? AND session.id = ?
                """, leaderId, hardDisabledSessionId))
                .containsEntry("status", "DISABLED")
                .containsEntry("revoked", true);
        } finally {
            PrincipalHolder.clear();
            jdbcTemplate.update("DELETE FROM t_user WHERE id IN (?, ?)", leaderId, memberId);
            jdbcTemplate.update("DELETE FROM t_department WHERE id = ?", departmentId);
        }
    }

    @Test
    void workplaceAuthorizationQueryExecutesAgainstPostgres() {
        long adminId = userId("admin");
        OffsetDateTime now = OffsetDateTime.now();
        assertThat(processInstanceMapper.selectWorkplaceStatusCounts(adminId, true, true, true,
            now.minusDays(1), now.plusDays(1))).isNotNull();
    }

    @Test
    void authorizationSnapshotUsesTheMigratedCapabilitySchema() {
        long adminId = userId("admin");
        authorizationService.evict(adminId);

        assertThat(authorizationService.principalForRequest(adminId, null))
            .hasValueSatisfying(principal -> {
                assertThat(principal.roles()).contains("admin");
                assertThat(principal.permissions()).contains(PermissionCodes.CONSOLE_ACCESS);
            });
    }

    @Test
    void desktopWorkflowPagesFilterPermissionsBeforeLimitAndCount() {
        long viewerId = insertUser("page_viewer_" + UUID.randomUUID());
        long ownerId = insertUser("page_owner_" + UUID.randomUUID());
        long formId = insertForm("PUBLISHED", VALID_SCHEMA);
        long processId = insertProcess(formId, "PUBLISHED", approvalFlow(ownerId));
        long visibleDataId = insertSubmittedData(formId, ownerId);
        long invisibleDataId = insertSubmittedData(formId, ownerId);
        long visibleInstanceId = jdbcTemplate.queryForObject("""
            INSERT INTO t_process_instance(proc_def_id, form_data_id, status, current_node_id,
                                           started_by, started_at)
            VALUES (?, ?, 'RUNNING', 'approved-node', ?, now() - interval '1 hour')
            RETURNING id
            """, Long.class, processId, visibleDataId, ownerId);
        jdbcTemplate.update("""
            INSERT INTO t_process_instance(proc_def_id, form_data_id, status, current_node_id,
                                           started_by, started_at)
            VALUES (?, ?, 'RUNNING', 'hidden-node', ?, now())
            """, processId, invisibleDataId, ownerId);
        jdbcTemplate.update("""
            INSERT INTO t_task(proc_inst_id, node_id, assignee_id, approved_by, status,
                               approval_mode, task_type, approved_at)
            VALUES (?, 'approved-node', ?, ?, 'APPROVED', 'OR_SIGN', 'APPROVAL', now())
            """, visibleInstanceId, ownerId, viewerId);

        assertThat(processInstanceMapper.selectInstancePage(viewerId, false, true, false,
            "authorized", null, ownerId, null, null, null, 1, 0))
            .extracting(com.antflow.task.ProcessInstance::getId)
            .containsExactly(visibleInstanceId);
        assertThat(processInstanceMapper.countInstancePage(viewerId, false, true, false,
            "authorized", null, ownerId, null, null, null)).isEqualTo(1L);

        long mineDataId = insertSubmittedData(formId, viewerId);
        long reworkDataId = insertSubmittedData(formId, viewerId);
        long mineInstanceId = jdbcTemplate.queryForObject("""
            INSERT INTO t_process_instance(proc_def_id, form_data_id, status, current_node_id,
                                           started_by, started_at)
            VALUES (?, ?, 'RUNNING', NULL, ?, now()) RETURNING id
            """, Long.class, processId, mineDataId, viewerId);
        jdbcTemplate.update("""
            INSERT INTO t_process_instance(proc_def_id, form_data_id, status, current_node_id,
                                           started_by, started_at)
            VALUES (?, ?, 'RUNNING', '__rework__', ?, now() + interval '1 minute')
            """, processId, reworkDataId, viewerId);
        assertThat(processInstanceMapper.selectInstancePage(viewerId, false, true, false,
            "mine", null, null, null, null, null, 10, 0))
            .extracting(com.antflow.task.ProcessInstance::getId)
            .containsExactly(mineInstanceId);
        assertThat(processInstanceMapper.countInstancePage(viewerId, false, true, false,
            "mine", null, null, null, null, null)).isEqualTo(1L);

        jdbcTemplate.update("""
            INSERT INTO t_task(proc_inst_id, node_id, assignee_id, status, approval_mode, task_type)
            VALUES (?, 'pending', ?, 'PENDING', 'OR_SIGN', 'APPROVAL'),
                   (?, 'done', ?, 'APPROVED', 'OR_SIGN', 'APPROVAL'),
                   (?, '__rework__', ?, 'RESUBMITTED', 'OR_SIGN', 'REWORK')
            """, mineInstanceId, viewerId, mineInstanceId, viewerId,
            mineInstanceId, viewerId);
        assertThat(taskMapper.selectTaskPage(viewerId, "pending", null, 20, 0))
            .extracting(com.antflow.task.TaskEntity::getNodeId).containsExactly("pending");
        assertThat(taskMapper.countTaskPage(viewerId, "pending", null)).isEqualTo(1L);
        assertThat(taskMapper.selectTaskPage(viewerId, "done", null, 20, 0))
            .extracting(com.antflow.task.TaskEntity::getStatus)
            .containsExactlyInAnyOrder("APPROVED", "RESUBMITTED");
        assertThat(taskMapper.countTaskPage(viewerId, "done", "APPROVED")).isEqualTo(1L);
    }

    @Test
    void desktopRecordSearchUsesBusinessFieldsDateBoundsAndTheOriginalVisibilityPredicate() {
        long viewerId = insertUser("record_viewer_" + UUID.randomUUID());
        long applicantId = insertUser("record_applicant_" + UUID.randomUUID());
        long companyId = jdbcTemplate.queryForObject(
            "SELECT id FROM t_company ORDER BY id LIMIT 1", Long.class);
        long departmentId = insertDepartment(companyId, "记录查询测试部门");
        long formId = insertForm("PUBLISHED", VALID_SCHEMA);
        long processId = insertProcess(formId, "PUBLISHED", approvalFlow(viewerId));
        OffsetDateTime from = OffsetDateTime.parse("2026-09-01T00:00:00+08:00");
        OffsetDateTime to = from.plusDays(1);
        List<Long> instanceIds = new java.util.ArrayList<>();
        try {
            jdbcTemplate.update("UPDATE t_form_definition SET name = '记录查询采购申请' WHERE id = ?",
                formId);
            jdbcTemplate.update("UPDATE t_user SET display_name = '记录查询申请人' WHERE id = ?",
                applicantId);
            for (OffsetDateTime time : List.of(from, from.minusSeconds(1), to, from.plusHours(1))) {
                long dataId = insertSubmittedData(formId, applicantId);
                instanceIds.add(jdbcTemplate.queryForObject("""
                    INSERT INTO t_process_instance(proc_def_id, form_data_id, status,
                        current_node_id, started_by, started_dept_id, started_at)
                    VALUES (?, ?, 'RUNNING', 'record_manager', ?, ?, ?) RETURNING id
                    """, Long.class, processId, dataId, applicantId, departmentId, time));
            }
            for (long instanceId : instanceIds.subList(0, 3)) {
                jdbcTemplate.update("""
                    INSERT INTO t_task(proc_inst_id, node_id, assignee_id, status,
                        approval_mode, task_type)
                    VALUES (?, 'record_manager', ?, 'PENDING', 'OR_SIGN', 'APPROVAL')
                    """, instanceId, viewerId);
            }
            long visibleId = instanceIds.get(0);
            String businessNo = jdbcTemplate.queryForObject("""
                SELECT data.business_no FROM t_form_data data
                JOIN t_process_instance pi ON pi.form_data_id = data.id WHERE pi.id = ?
                """, String.class, visibleId);
            String formCode = jdbcTemplate.queryForObject(
                "SELECT code FROM t_form_definition WHERE id = ?", String.class, formId);
            var applicant = jdbcTemplate.queryForMap(
                "SELECT username, employee_no FROM t_user WHERE id = ?", applicantId);

            for (String keyword : List.of("记录查询采购", formCode, businessNo, "记录查询申请人",
                String.valueOf(applicant.get("username")), String.valueOf(applicant.get("employee_no")),
                "记录查询测试部门", "record_manager", String.valueOf(visibleId), "#" + visibleId)) {
                var records = processInstanceMapper.selectInstancePage(viewerId, false, true, false,
                    "authorized", "RUNNING", null, keyword, from, to, 20, 0);
                assertThat(records).as("keyword %s", keyword)
                    .extracting(com.antflow.task.ProcessInstance::getId).containsExactly(visibleId);
                assertThat(processInstanceMapper.countInstancePage(viewerId, false, true, false,
                    "authorized", "RUNNING", null, keyword, from, to)).isEqualTo(records.size());
                assertThat(records.get(0).getFormName()).isEqualTo("记录查询采购申请");
                assertThat(records.get(0).getFormCode()).isEqualTo(formCode);
                assertThat(records.get(0).getBusinessNo()).isEqualTo(businessNo);
                assertThat(records.get(0).getApplicantName()).isEqualTo("记录查询申请人");
                assertThat(records.get(0).getApplicantEmployeeNo())
                    .isEqualTo(applicant.get("employee_no"));
                assertThat(records.get(0).getApplicantDepartment()).isEqualTo("记录查询测试部门");
            }

            jdbcTemplate.update("UPDATE t_process_instance SET current_node_id = '__rework__' WHERE id = ?",
                visibleId);
            assertThat(processInstanceMapper.selectInstancePage(viewerId, false, true, false,
                "authorized", "REWORK", null, null, from, to, 20, 0))
                .extracting(com.antflow.task.ProcessInstance::getId).containsExactly(visibleId);
            assertThat(processInstanceMapper.countInstancePage(viewerId, false, true, false,
                "authorized", "REWORK", null, null, from, to)).isEqualTo(1L);
            assertThat(processInstanceMapper.selectInstancePage(viewerId, false, false, false,
                "authorized", null, null, "记录查询", from, to, 20, 0)).isEmpty();
        } finally {
            instanceIds.forEach(id -> jdbcTemplate.update("DELETE FROM t_task WHERE proc_inst_id = ?", id));
            instanceIds.forEach(id -> jdbcTemplate.update("DELETE FROM t_process_instance WHERE id = ?", id));
            jdbcTemplate.update("DELETE FROM t_form_data WHERE form_def_id = ?", formId);
            jdbcTemplate.update("DELETE FROM t_process_definition WHERE id = ?", processId);
            jdbcTemplate.update("DELETE FROM t_form_definition WHERE id = ?", formId);
            jdbcTemplate.update("DELETE FROM t_user WHERE id IN (?, ?)", viewerId, applicantId);
            jdbcTemplate.update("DELETE FROM t_department WHERE id = ?", departmentId);
        }
    }

    @Test
    void formGrantCandidatesArePagedAndSelectedSubjectsHaveLabels() {
        long adminId = userId("admin");
        long formId = insertForm("DRAFT", VALID_SCHEMA);
        long companyId = jdbcTemplate.queryForObject(
            "SELECT id FROM t_company ORDER BY id LIMIT 1", Long.class);
        jdbcTemplate.update("""
            INSERT INTO t_department(company_id, path, name) VALUES (?, 'grant_test', '授权测试部')
            """, companyId);
        jdbcTemplate.update("""
            INSERT INTO t_form_resource_grant(form_def_id, subject_type, subject_id, granted_by)
            VALUES (?, 'USER', ?, ?)
            """, formId, adminId, adminId);
        PrincipalHolder.set(new PrincipalHolder.Principal(adminId, "admin", List.of("admin")));
        try {
            FormGrantService.GrantUserPage page = formGrantService.userCandidates(
                formId, 1, 20, "admin", null);
            assertThat(page.items()).extracting(FormGrantService.GrantUser::username)
                .contains("admin");
            assertThat(page.total()).isPositive();

            FormGrantService.FormGrantDto grant = formGrantService.get(formId);
            assertThat(grant.userIds()).contains(adminId);
            assertThat(grant.users()).extracting(FormGrantService.GrantUser::displayName)
                .isNotEmpty();
            assertThat(formGrantService.candidates(formId).departments()).isNotEmpty();
        } finally {
            PrincipalHolder.clear();
        }
    }

    @Test
    void departmentFormGrantIncludesDescendantsAndUserAssignmentsArePaged() {
        long adminId = userId("admin");
        long bobId = userId("bob");
        Long originalDepartment = jdbcTemplate.queryForObject(
            "SELECT dept_id FROM t_user WHERE id = ?", Long.class, bobId);
        long companyId = jdbcTemplate.queryForObject(
            "SELECT id FROM t_company ORDER BY id LIMIT 1", Long.class);
        long parent = insertDepartment(companyId, "授权父部门");
        String childPath = jdbcTemplate.queryForObject(
            "SELECT path::text || '.child' FROM t_department WHERE id = ?", String.class, parent);
        long child = jdbcTemplate.queryForObject("""
            INSERT INTO t_department(company_id, parent_id, path, name)
            VALUES (?, ?, CAST(? AS ltree), '授权子部门') RETURNING id
            """, Long.class, companyId, parent, childPath);
        long formId = insertForm("DRAFT", VALID_SCHEMA);
        try {
            jdbcTemplate.update("UPDATE t_user SET dept_id = ? WHERE id = ?", child, bobId);
            jdbcTemplate.update("""
                INSERT INTO t_form_resource_grant(form_def_id, subject_type, subject_id, granted_by)
                VALUES (?, 'DEPARTMENT', ?, ?)
                """, formId, parent, adminId);
            assertThat(authorizationService.hasFormGrant(formId, bobId)).isTrue();
            PrincipalHolder.set(new PrincipalHolder.Principal(adminId, "admin", List.of("admin")));
            RoleAdminService.UserRolePage page = roleAdminService.userAssignments(1, 2, null);
            assertThat(page.records()).hasSize(2);
            assertThat(page.total()).isGreaterThanOrEqualTo(2);
        } finally {
            PrincipalHolder.clear();
            jdbcTemplate.update("UPDATE t_user SET dept_id = ? WHERE id = ?", originalDepartment, bobId);
            jdbcTemplate.update("DELETE FROM t_form_resource_grant WHERE form_def_id = ?", formId);
            jdbcTemplate.update("DELETE FROM t_form_definition WHERE id = ?", formId);
            jdbcTemplate.update("DELETE FROM t_department WHERE id IN (?, ?)", child, parent);
        }
    }

    @Test
    void unreadCcMovesFromPendingToDoneAfterReadTimestamp() {
        long adminId = userId("admin");
        long bobId = userId("bob");
        long formId = insertForm("PUBLISHED", VALID_SCHEMA);
        long processId = insertProcess(formId, "PUBLISHED",
            "{\"id\":\"root\",\"type\":\"ROOT\",\"children\":null}");
        long formDataId = jdbcTemplate.queryForObject("""
            INSERT INTO t_form_data(form_def_id, form_def_version, business_no, data,
                                    status, created_by)
            VALUES (?, 1, lpad(nextval('seq_business_no')::text, 12, '0'),
                    '{}'::jsonb, 'SUBMITTED', ?)
            RETURNING id
            """, Long.class, formId, adminId);
        long instanceId = jdbcTemplate.queryForObject("""
            INSERT INTO t_process_instance(proc_def_id, process_def_version, process_snapshot,
                                           form_data_id, status, current_node_id, version,
                                           started_by)
            VALUES (?, 1, '{"id":"root","type":"ROOT"}'::jsonb, ?,
                    'APPROVED', 'cc1', 0, ?)
            RETURNING id
            """, Long.class, processId, formDataId, adminId);
        long taskId = jdbcTemplate.queryForObject("""
            INSERT INTO t_task(proc_inst_id, node_id, assignee_id, status, approval_mode,
                               task_type, version)
            VALUES (?, 'cc1', ?, 'CC', 'OR', 'APPROVAL', 0)
            RETURNING id
            """, Long.class, instanceId, bobId);

        assertThat(authorizationService.instanceVisibility(instanceId, bobId))
            .isEqualTo(AuthorizationService.InstanceVisibility.FULL);

        assertThat(mobileWorkflowMapper.selectTaskPage(bobId, "pending", null, null, 20, 0))
            .extracting(MobileWorkflowMapper.TaskRow::id).contains(taskId);
        assertThat(mobileWorkflowMapper.selectTaskPage(bobId, "done", null, null, 20, 0))
            .extracting(MobileWorkflowMapper.TaskRow::id).doesNotContain(taskId);

        jdbcTemplate.update("UPDATE t_task SET read_at = now() WHERE id = ?", taskId);

        assertThat(mobileWorkflowMapper.selectTaskPage(bobId, "pending", null, null, 20, 0))
            .extracting(MobileWorkflowMapper.TaskRow::id).doesNotContain(taskId);
        assertThat(mobileWorkflowMapper.selectTaskPage(bobId, "done", null, null, 20, 0))
            .extracting(MobileWorkflowMapper.TaskRow::id).contains(taskId);
    }

    @Test
    void v2CcUsesIndependentRecordsAndMobileUnionQueries() {
        long adminId = userId("admin");
        long bobId = userId("bob");
        StartedV2 started = startV2(ccThenApprovalFlow(bobId, adminId), adminId);
        long ccId = jdbcTemplate.queryForObject("""
            SELECT id FROM t_cc_record WHERE proc_inst_id = ? AND recipient_id = ?
            """, Long.class, started.instanceId(), bobId);

        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM t_task
            WHERE proc_inst_id = ? AND (task_type = 'CC' OR status = 'CC')
            """, Long.class, started.instanceId())).isZero();
        MobileWorkflowMapper.TaskRow pendingCc = mobileWorkflowMapper
            .selectTaskPage(bobId, "pending", null, null, 20, 0).stream()
            .filter(row -> Objects.equals(row.instanceId(), started.instanceId()))
            .findFirst().orElseThrow();
        assertThat(pendingCc.id()).isEqualTo(8_000_000_000_000_000L + ccId);
        assertThat(pendingCc.taskType()).isEqualTo("CC");
        assertThat(mobileWorkflowMapper.selectTaskDetail(pendingCc.id()).taskType()).isEqualTo("CC");

        assertThat(mobileWorkflowMapper.markCcRead(ccId, bobId)).isEqualTo(1);
        assertThat(mobileWorkflowMapper.selectTaskPage(bobId, "done", null, null, 20, 0))
            .extracting(MobileWorkflowMapper.TaskRow::id).contains(pendingCc.id());
    }

    @Test
    void v2CcPreservesRecordsButSendsOneNotificationPerRecipientPerRound() {
        long adminId = userId("admin");
        long bobId = userId("bob");
        StartedV2 started = startV2(twoCcThenApprovalFlow(bobId, adminId), adminId);

        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM t_cc_record WHERE proc_inst_id = ? AND recipient_id = ?
            """, Long.class, started.instanceId(), bobId)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM t_cc_notification_batch
            WHERE proc_inst_id = ? AND round_no = 1 AND recipient_id = ?
            """, Long.class, started.instanceId(), bobId)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM t_workflow_outbox
            WHERE aggregate_id = ? AND event_type = 'CC_ASSIGNED' AND recipient_id = ?
            """, Long.class, started.instanceId(), bobId)).isEqualTo(1L);
    }

    @Test
    void mobileNotificationsAreUserScopedAndReadIdempotently() {
        long adminId = userId("admin");
        long bobId = userId("bob");
        UUID eventId = jdbcTemplate.queryForObject("""
            INSERT INTO t_workflow_outbox(
                aggregate_type, aggregate_id, event_type, recipient_id, payload, status)
            VALUES ('PROCESS_INSTANCE', 31, 'APPROVAL_INVALIDATED', ?,
                    '{"instanceId":31,"taskId":41}'::jsonb, 'DELIVERED')
            RETURNING id
            """, UUID.class, adminId);
        long notificationId = jdbcTemplate.queryForObject("""
            INSERT INTO t_user_notification(event_id, user_id, event_type, title, payload)
            VALUES (?, ?, 'APPROVAL_INVALIDATED', '您的审批已作废',
                    '{"instanceId":31,"taskId":41}'::jsonb)
            RETURNING id
            """, Long.class, eventId, adminId);

        assertThat(mobileWorkflowMapper.selectNotifications(adminId, true, 20, 0))
            .extracting(MobileWorkflowMapper.NotificationRow::id)
            .contains(notificationId);
        assertThat(mobileWorkflowMapper.selectNotifications(bobId, false, 20, 0)).isEmpty();
        assertThat(mobileWorkflowMapper.markNotificationRead(notificationId, bobId)).isZero();
        assertThat(mobileWorkflowMapper.markNotificationRead(notificationId, adminId)).isEqualTo(1);
        assertThat(mobileWorkflowMapper.markNotificationRead(notificationId, adminId)).isEqualTo(1);
        assertThat(mobileWorkflowMapper.countUnreadNotifications(adminId)).isZero();
    }

    @Test
    void withdrawnInstanceOnlyReturnsToStartedListAfterReworkResubmission() {
        long adminId = userId("admin");
        long bobId = userId("bob");
        String flow = approvalFlow(bobId);
        long formId = insertForm("PUBLISHED", VALID_SCHEMA);
        long processId = insertProcess(formId, "PUBLISHED", flow);
        long formDataId = jdbcTemplate.queryForObject("""
            INSERT INTO t_form_data(form_def_id, form_def_version, business_no, data,
                                    status, created_by)
            VALUES (?, 1, lpad(nextval('seq_business_no')::text, 12, '0'),
                    '{}'::jsonb, 'SUBMITTED', ?)
            RETURNING id
            """, Long.class, formId, adminId);
        long instanceId = jdbcTemplate.queryForObject("""
            INSERT INTO t_process_instance(proc_def_id, process_def_version, process_snapshot,
                                           form_data_id, status, current_node_id, version,
                                           started_by)
            VALUES (?, 1, ?::jsonb, ?, 'RUNNING', 'a1', 0, ?)
            RETURNING id
            """, Long.class, processId, flow, formDataId, adminId);
        jdbcTemplate.update("""
            INSERT INTO t_task(proc_inst_id, node_id, assignee_id, status, approval_mode,
                               task_type, version)
            VALUES (?, 'a1', ?, 'PENDING', 'OR_SIGN', 'APPROVAL', 0)
            """, instanceId, bobId);
        long historicalFormDataId = jdbcTemplate.queryForObject("""
            INSERT INTO t_form_data(form_def_id, form_def_version, business_no, data,
                                    status, created_by)
            VALUES (?, 1, lpad(nextval('seq_business_no')::text, 12, '0'),
                    '{}'::jsonb, 'SUBMITTED', ?)
            RETURNING id
            """, Long.class, formId, adminId);
        long historicalInstanceId = jdbcTemplate.queryForObject("""
            INSERT INTO t_process_instance(proc_def_id, process_def_version, process_snapshot,
                                           form_data_id, status, current_node_id, version,
                                           started_by, finished_at)
            VALUES (?, 1, ?::jsonb, ?, 'APPROVED', NULL, 0, ?, now())
            RETURNING id
            """, Long.class, processId, flow, historicalFormDataId, adminId);
        Map<String, Object> identity = jdbcTemplate.queryForMap("""
            SELECT pi.form_data_id, data.business_no, pi.started_at
            FROM t_process_instance pi
            JOIN t_form_data data ON data.id = pi.form_data_id
            WHERE pi.id = ?
            """, instanceId);

        assertThat(mobileWorkflowMapper.selectInstancePage(adminId, null, null, 20, 0))
            .extracting(MobileWorkflowMapper.InstanceRow::id)
            .contains(instanceId, historicalInstanceId);

        processEngine.withdraw(instanceId, adminId);
        long reworkTaskId = jdbcTemplate.queryForObject("""
            SELECT id FROM t_task
            WHERE proc_inst_id = ? AND task_type = 'REWORK' AND status = 'PENDING'
            """, Long.class, instanceId);

        assertThat(mobileWorkflowMapper.selectInstancePage(adminId, null, null, 20, 0))
            .extracting(MobileWorkflowMapper.InstanceRow::id)
            .contains(historicalInstanceId)
            .doesNotContain(instanceId);
        assertThat(mobileWorkflowMapper.selectTaskPage(
            adminId, "pending", null, null, 20, 0))
            .extracting(MobileWorkflowMapper.TaskRow::id)
            .contains(reworkTaskId);

        processEngine.resubmitRework(reworkTaskId, adminId);

        assertThat(mobileWorkflowMapper.selectInstancePage(adminId, null, null, 20, 0))
            .extracting(MobileWorkflowMapper.InstanceRow::id)
            .contains(instanceId, historicalInstanceId);
        assertThat(jdbcTemplate.queryForMap("""
            SELECT pi.form_data_id, data.business_no, pi.started_at
            FROM t_process_instance pi
            JOIN t_form_data data ON data.id = pi.form_data_id
            WHERE pi.id = ?
            """, instanceId)).containsAllEntriesOf(identity);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT current_node_id FROM t_process_instance WHERE id = ?
            """, String.class, instanceId)).isEqualTo("a1");
    }

    @Test
    void concurrentParallelApprovalsCreateOneJoinTask() throws Exception {
        long adminId = userId("admin");
        long bobId = userId("bob");
        String flow = parallelFlow(adminId, bobId, adminId);
        long formId = insertForm("PUBLISHED", VALID_SCHEMA);
        long processId = insertProcess(formId, "PUBLISHED", flow);
        long formDataId = jdbcTemplate.queryForObject("""
            INSERT INTO t_form_data(form_def_id, form_def_version, business_no, data,
                                    status, created_by)
            VALUES (?, 2, lpad(nextval('seq_business_no')::text, 12, '0'),
                    '{}'::jsonb, 'SUBMITTED', ?)
            RETURNING id
            """, Long.class, formId, adminId);
        long instanceId = jdbcTemplate.queryForObject("""
            INSERT INTO t_process_instance(proc_def_id, process_def_version, process_snapshot,
                                           form_data_id, status, current_node_id, version,
                                           started_by)
            VALUES (?, 2, ?::jsonb, ?, 'RUNNING', 'p1', 0, ?)
            RETURNING id
            """, Long.class, processId, flow, formDataId, adminId);
        long firstTaskId = insertParallelTask(instanceId, "a1", adminId, "b1");
        long secondTaskId = insertParallelTask(instanceId, "a2", bobId, "b2");

        List<Throwable> outcomes = runConcurrently(adminId,
            () -> processEngine.approve(
                new CompleteCmd(firstTaskId, "APPROVE", "ok", null), adminId),
            () -> processEngine.approve(
                new CompleteCmd(secondTaskId, "APPROVE", "ok", null), bobId));

        assertThat(outcomes).allMatch(Objects::isNull);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM t_task
            WHERE proc_inst_id = ? AND node_id = 'a3' AND status = 'PENDING'
            """, Long.class, instanceId)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForList("""
            SELECT status FROM t_task WHERE id IN (?, ?) ORDER BY id
            """, String.class, firstTaskId, secondTaskId))
            .containsExactly("APPROVED", "APPROVED");
    }

    @Test
    void concurrentAdminDisableAndRoleRemovalLeaveAnActiveAdmin() throws Exception {
        long seedAdminId = userId("admin");
        long adminRoleId = roleId("admin");
        long userRoleId = roleId("employee");
        long firstAdminId = insertUser("concurrent-admin-a-" + UUID.randomUUID());
        long secondAdminId = insertUser("concurrent-admin-b-" + UUID.randomUUID());
        assignRole(firstAdminId, adminRoleId);
        assignRole(secondAdminId, adminRoleId);
        assignRole(firstAdminId, userRoleId);
        assignRole(secondAdminId, userRoleId);
        jdbcTemplate.update("UPDATE t_user SET status = 'DISABLED' WHERE id = ?", seedAdminId);

        try {
            List<Throwable> outcomes = runConcurrently(seedAdminId,
                () -> userService.update(firstAdminId, Map.of("status", "DISABLED")),
                () -> userService.setRoles(secondAdminId, List.of(userRoleId)));

            assertThat(outcomes.stream().filter(Objects::isNull).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(Objects::nonNull).toList())
                .singleElement()
                .isInstanceOfSatisfying(BizException.class, exception ->
                    assertThat(exception.getCode()).isEqualTo("LAST_ADMIN_PROTECTED"));
            assertThat(activeAdminCount()).isEqualTo(1L);
        } finally {
            jdbcTemplate.update("UPDATE t_user SET status = 'ACTIVE' WHERE id = ?", seedAdminId);
            jdbcTemplate.update("""
                INSERT INTO t_user_role(user_id, role_id) VALUES (?, ?)
                ON CONFLICT DO NOTHING
                """, seedAdminId, adminRoleId);
            jdbcTemplate.update("DELETE FROM t_user_role WHERE user_id IN (?, ?)",
                firstAdminId, secondAdminId);
            jdbcTemplate.update("DELETE FROM t_user WHERE id IN (?, ?)",
                firstAdminId, secondAdminId);
        }
    }

    @Test
    void concurrentReportingUpdatesCannotCreateCycle() throws Exception {
        long adminId = userId("admin");
        long companyId = jdbcTemplate.queryForObject(
            "SELECT id FROM t_company ORDER BY id LIMIT 1", Long.class);
        long departmentId = insertDepartment(companyId, "汇报并发测试部");
        long firstUserId = insertUser("reporting-a-" + UUID.randomUUID());
        long secondUserId = insertUser("reporting-b-" + UUID.randomUUID());
        jdbcTemplate.update("UPDATE t_user SET dept_id = ? WHERE id IN (?, ?)",
            departmentId, firstUserId, secondUserId);

        try {
            List<Throwable> outcomes = runConcurrently(adminId,
                () -> userService.update(firstUserId, Map.of("managerId", secondUserId)),
                () -> userService.update(secondUserId, Map.of("managerId", firstUserId)));

            assertThat(outcomes.stream().filter(Objects::isNull).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(Objects::nonNull).toList())
                .singleElement()
                .isInstanceOfSatisfying(BizException.class, exception ->
                    assertThat(exception.getCode()).isEqualTo("MANAGER_CYCLE"));
            assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_user
                WHERE id IN (?, ?) AND manager_id IS NOT NULL
                """, Long.class, firstUserId, secondUserId)).isEqualTo(1L);
        } finally {
            jdbcTemplate.update("UPDATE t_user SET manager_id = NULL WHERE id IN (?, ?)",
                firstUserId, secondUserId);
            jdbcTemplate.update("DELETE FROM t_user WHERE id IN (?, ?)", firstUserId, secondUserId);
            jdbcTemplate.update("DELETE FROM t_department WHERE id = ?", departmentId);
        }
    }

    @Test
    void missingNextReportingManagerRollsBackCurrentApproval() {
        long adminId = userId("admin");
        long companyId = jdbcTemplate.queryForObject(
            "SELECT id FROM t_company ORDER BY id LIMIT 1", Long.class);
        long departmentId = insertDepartment(companyId, "审批回滚测试部");
        long starterId = insertUser("reporting-starter-" + UUID.randomUUID());
        long firstManagerId = insertUser("reporting-manager-" + UUID.randomUUID());
        jdbcTemplate.update("UPDATE t_user SET dept_id = ? WHERE id IN (?, ?)",
            departmentId, starterId, firstManagerId);
        jdbcTemplate.update("UPDATE t_user SET manager_id = ? WHERE id = ?",
            firstManagerId, starterId);
        String flow = reportingManagerFlow(adminId, 2);
        long formId = insertForm("PUBLISHED", VALID_SCHEMA);
        long processId = insertProcess(formId, "PUBLISHED", flow);
        long formDataId = jdbcTemplate.queryForObject("""
            INSERT INTO t_form_data(form_def_id, form_def_version, business_no, data,
                                    status, created_by)
            VALUES (?, 1, lpad(nextval('seq_business_no')::text, 12, '0'),
                    '{}'::jsonb, 'SUBMITTED', ?)
            RETURNING id
            """, Long.class, formId, starterId);
        long instanceId = jdbcTemplate.queryForObject("""
            INSERT INTO t_process_instance(proc_def_id, process_def_version, process_snapshot,
                                           form_data_id, status, current_node_id, version,
                                           started_by)
            VALUES (?, 1, ?::jsonb, ?, 'RUNNING', 'a1', 0, ?)
            RETURNING id
            """, Long.class, processId, flow, formDataId, starterId);
        long taskId = jdbcTemplate.queryForObject("""
            INSERT INTO t_task(proc_inst_id, node_id, assignee_id, status, approval_mode,
                               task_type, version)
            VALUES (?, 'a1', ?, 'PENDING', 'OR', 'APPROVAL', 0)
            RETURNING id
            """, Long.class, instanceId, adminId);

        assertThatThrownBy(() -> processEngine.approve(
            new CompleteCmd(taskId, "APPROVE", "ok", null), adminId))
            .isInstanceOf(NoAssigneeFoundException.class)
            .hasMessageContaining("第 2 级直属上级");

        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM t_task WHERE id = ?", String.class, taskId))
            .isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM t_task WHERE proc_inst_id = ? AND node_id = 'a2'
            """, Long.class, instanceId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM t_task_history
            WHERE proc_inst_id = ? AND action = 'APPROVE'
            """, Long.class, instanceId)).isZero();
        assertThat(jdbcTemplate.queryForMap("""
            SELECT status, current_node_id FROM t_process_instance WHERE id = ?
            """, instanceId)).containsEntry("status", "RUNNING")
            .containsEntry("current_node_id", "a1");
    }

    @Test
    void auditInsertFailureRollsBackRealBusinessWrite() {
        String companyName = "rollback-company-" + UUID.randomUUID();

        assertThatThrownBy(() -> auditService.execute(
            () -> jdbcTemplate.update("INSERT INTO t_company(name) VALUES (?)", companyName),
            ignored -> auditService.success(null, "COMPANY", companyName,
                AuditService.RiskLevel.HIGH, Map.of(), Map.of())))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM t_company WHERE name = ?", Long.class, companyName))
            .isZero();
    }

    @Test
    void invalidProcessSecondStepRollsBackFormPublication() {
        long formId = insertForm("DRAFT", VALID_SCHEMA);
        long processId = insertProcess(formId, "DRAFT", "{}");

        assertThatThrownBy(() -> publishService.publish(formId, processId))
            .isInstanceOfSatisfying(BizException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("BAD_FLOW"));

        assertDefinitionState("t_form_definition", formId, "DRAFT", 1);
        assertDefinitionState("t_process_definition", processId, "DRAFT", 1);
    }

    @Test
    void publishAuditFailureRollsBackBothDefinitionsAndEarlierAudit() {
        long adminId = userId("admin");
        long formId = insertForm("DRAFT", VALID_SCHEMA);
        long processId = insertProcess(formId, "DRAFT", approvalFlow(adminId));
        jdbcTemplate.execute("""
            CREATE OR REPLACE FUNCTION antflow_test_fail_publish_audit()
            RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                IF NEW.action = 'workflow.definition.publish' THEN
                    RAISE EXCEPTION 'forced publish audit failure';
                END IF;
                RETURN NEW;
            END;
            $$
            """);
        jdbcTemplate.execute("""
            CREATE TRIGGER antflow_test_fail_publish_audit_trigger
            BEFORE INSERT ON t_audit_event
            FOR EACH ROW EXECUTE FUNCTION antflow_test_fail_publish_audit()
            """);

        try {
            assertThatThrownBy(() -> publishService.publish(formId, processId))
                .hasMessageContaining("forced publish audit failure");

            assertDefinitionState("t_form_definition", formId, "DRAFT", 1);
            assertDefinitionState("t_process_definition", processId, "DRAFT", 1);
            assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_audit_event
                WHERE (resource_type = 'FORM_DEFINITION' AND resource_id = ?)
                   OR (resource_type = 'PROCESS_DEFINITION' AND resource_id = ?)
                """, Long.class, String.valueOf(formId), String.valueOf(processId)))
                .isZero();
        } finally {
            jdbcTemplate.execute("""
                DROP TRIGGER IF EXISTS antflow_test_fail_publish_audit_trigger ON t_audit_event
                """);
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS antflow_test_fail_publish_audit()");
        }
    }

    private List<Throwable> runConcurrently(long principalUserId,
                                            ThrowingRunnable first,
                                            ThrowingRunnable second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Callable<Throwable> firstCall = concurrentCall(
            principalUserId, ready, start, first);
        Callable<Throwable> secondCall = concurrentCall(
            principalUserId, ready, start, second);
        Future<Throwable> firstResult = executor.submit(firstCall);
        Future<Throwable> secondResult = executor.submit(secondCall);
        try {
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return Arrays.asList(
                firstResult.get(20, TimeUnit.SECONDS),
                secondResult.get(20, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private long taskIdForAssignee(long instanceId, String nodeId, long assigneeId) {
        return jdbcTemplate.queryForObject("""
            SELECT id FROM t_task
            WHERE proc_inst_id = ? AND node_id = ? AND assignee_id = ? AND status = 'PENDING'
            """, Long.class, instanceId, nodeId, assigneeId);
    }

    private void assertOneSuccessOneTaskConflict(List<Throwable> outcomes) {
        assertThat(outcomes.stream().filter(Objects::isNull).count()).isEqualTo(1L);
        assertThat(outcomes.stream().filter(Objects::nonNull).toList())
            .singleElement()
            .isInstanceOfSatisfying(BizException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("TASK_NOT_PENDING"));
    }

    private long pendingTaskCount(long instanceId, String nodeId) {
        return jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM t_task
            WHERE proc_inst_id = ? AND node_id = ? AND status = 'PENDING'
            """, Long.class, instanceId, nodeId);
    }

    private long pendingReworkCount(long instanceId) {
        return jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM t_task
            WHERE proc_inst_id = ? AND task_type = 'REWORK' AND status = 'PENDING'
            """, Long.class, instanceId);
    }

    private long pendingReworkTaskId(long instanceId) {
        return jdbcTemplate.queryForObject("""
            SELECT id FROM t_task
            WHERE proc_inst_id = ? AND task_type = 'REWORK' AND status = 'PENDING'
            """, Long.class, instanceId);
    }

    private void rejectParallelAfterFirstApproval(long instanceId, long first, long second) {
        processEngine.approve(new CompleteCmd(
            taskIdForAssignee(instanceId, "a1", first), "APPROVE", "ok", null), first);
        processEngine.reject(new CompleteCmd(
            taskIdForAssignee(instanceId, "a2", second), "REJECT", "no", null), second);
    }

    private Callable<Throwable> concurrentCall(long principalUserId,
                                               CountDownLatch ready,
                                               CountDownLatch start,
                                               ThrowingRunnable operation) {
        return () -> {
            PrincipalHolder.set(new PrincipalHolder.Principal(
                principalUserId, "integration-admin", "Integration Admin",
                Set.of("admin"), Set.of(), 1L, null, null));
            ready.countDown();
            try {
                if (!start.await(10, TimeUnit.SECONDS)) {
                    return new IllegalStateException("concurrent start timed out");
                }
                operation.run();
                return null;
            } catch (Throwable throwable) {
                return throwable;
            } finally {
                PrincipalHolder.clear();
            }
        };
    }

    private long insertForm(String status, String schema) {
        return insertForm(status, schema, userId("admin"));
    }

    @Test
    void instanceReadScopeDoesNotDependOnFormUsageGrant() {
        long viewerId = insertUser("instance-scope-" + UUID.randomUUID());
        long roleId = insertRole("instance_scope_"
            + UUID.randomUUID().toString().replace("-", ""));
        long ownerId = userId("admin");
        long formId = insertForm("PUBLISHED", VALID_SCHEMA);
        long processId = insertProcess(formId, "PUBLISHED", approvalFlow(ownerId));
        long dataId = insertSubmittedData(formId, ownerId);
        long instanceId = jdbcTemplate.queryForObject("""
            INSERT INTO t_process_instance(proc_def_id, form_data_id, status, started_by)
            VALUES (?, ?, 'RUNNING', ?) RETURNING id
            """, Long.class, processId, dataId, ownerId);
        try {
            assignRole(viewerId, roleId);
            jdbcTemplate.update("""
                INSERT INTO t_role_permission(role_id, permission_code, scope_override)
                VALUES (?, 'workflow:instance:read', 'ALL')
                """, roleId);
            setPrincipal(viewerId);

            assertThat(authorizationService.instanceVisibility(instanceId, viewerId))
                .isEqualTo(AuthorizationService.InstanceVisibility.FULL);
            assertThat(processInstanceMapper.selectInstancePage(viewerId, false, false, true,
                "authorized", null, null, null, null, null, 20, 0))
                .extracting(com.antflow.task.ProcessInstance::getId).contains(instanceId);
        } finally {
            PrincipalHolder.clear();
            jdbcTemplate.update("DELETE FROM t_process_instance WHERE id = ?", instanceId);
            jdbcTemplate.update("DELETE FROM t_form_data WHERE id = ?", dataId);
            jdbcTemplate.update("DELETE FROM t_process_definition WHERE id = ?", processId);
            jdbcTemplate.update("DELETE FROM t_form_definition WHERE id = ?", formId);
            jdbcTemplate.update("DELETE FROM t_user_role WHERE user_id = ?", viewerId);
            jdbcTemplate.update("DELETE FROM t_role WHERE id = ?", roleId);
            jdbcTemplate.update("DELETE FROM t_user WHERE id = ?", viewerId);
            authorizationService.evict(viewerId);
        }
    }

    @Test
    void formMaintainersCannotBeEmptyAndConcurrentReplacementConflicts() throws Exception {
        long adminId = userId("admin");
        long bobId = userId("bob");
        long formId = formDefinitionService.saveDraft(null,
            "maintainer_" + UUID.randomUUID().toString().replace("-", ""),
            "Maintainer test", "", List.of(), Map.of(), adminId).getId();
        var staleDefinition = formDefinitionMapper.selectById(formId);
        PrincipalHolder.set(new PrincipalHolder.Principal(adminId, "admin", List.of("admin")));
        int version;
        try {
            version = formGrantService.getMaintainers(formId).version();
            assertThatThrownBy(() -> formGrantService.replaceMaintainers(formId,
                new FormGrantService.FormMaintainerWriteRequest(version, Set.of())))
                .isInstanceOfSatisfying(BizException.class, error ->
                    assertThat(error.getCode()).isEqualTo("FORM_MAINTAINER_REQUIRED"));
        } finally {
            PrincipalHolder.clear();
        }

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<Object>> futures = List.of(
            executor.submit(() -> replaceMaintainers(formId, version, Set.of(adminId), ready, start)),
            executor.submit(() -> replaceMaintainers(formId, version, Set.of(bobId), ready, start)));
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        try {
            List<Object> outcomes = List.of(futures.get(0).get(20, TimeUnit.SECONDS),
                futures.get(1).get(20, TimeUnit.SECONDS));
            assertThat(outcomes.stream()
                .filter(FormGrantService.FormMaintainerDto.class::isInstance).count())
                .isEqualTo(1L);
            assertThat(outcomes.stream().filter(BizException.class::isInstance)
                .map(BizException.class::cast).map(BizException::getCode))
                .containsExactly("FORM_MAINTAINER_VERSION_CONFLICT");
            assertThat(jdbcTemplate.queryForList("""
                SELECT user_id FROM t_form_maintainer WHERE form_def_id = ?
                """, Long.class, formId)).hasSize(1);
            staleDefinition.setName("Renamed after authorization update");
            formDefinitionMapper.updateById(staleDefinition);
            assertThat(jdbcTemplate.queryForObject("""
                SELECT authz_version FROM t_form_definition WHERE id = ?
                """, Integer.class, formId)).isEqualTo(version + 1);
        } finally {
            executor.shutdownNow();
            jdbcTemplate.update("DELETE FROM t_form_maintainer WHERE form_def_id = ?", formId);
            jdbcTemplate.update("DELETE FROM t_form_resource_grant WHERE form_def_id = ?", formId);
            jdbcTemplate.update("DELETE FROM t_form_definition WHERE id = ?", formId);
        }
    }

    private long insertForm(String status, String schema, long creatorId) {
        String code = "IT_" + UUID.randomUUID().toString().replace("-", "");
        return jdbcTemplate.queryForObject("""
            INSERT INTO t_form_definition(code, name, version, schema, settings, status,
                                          created_by, deleted)
            VALUES (?, 'Integration form', 1, ?::jsonb, '{}'::jsonb, ?, ?, 0)
            RETURNING id
            """, Long.class, code, schema, status, creatorId);
    }

    private long insertSubmittedData(long formId, long creatorId) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO t_form_data(form_def_id, form_def_version, business_no, data,
                                    status, created_by)
            VALUES (?, 1, lpad(nextval('seq_business_no')::text, 12, '0'),
                    '{}'::jsonb, 'SUBMITTED', ?)
            RETURNING id
            """, Long.class, formId, creatorId);
    }

    private long insertDraft(long formId, long creatorId) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO t_form_data(form_def_id, form_def_version, data, status, created_by)
            VALUES (?, 1, '{}'::jsonb, 'DRAFT', ?)
            RETURNING id
            """, Long.class, formId, creatorId);
    }

    private StartedV2 startV2(String flow, long starterId) {
        long formId = insertForm("DRAFT", VALID_SCHEMA);
        long processId = insertProcess(formId, "DRAFT", flow);
        String code = jdbcTemplate.queryForObject(
            "SELECT code FROM t_form_definition WHERE id = ?", String.class, formId);
        PrincipalHolder.set(new PrincipalHolder.Principal(starterId, "admin", List.of("admin")));
        try {
            publishService.publish(formId, processId);
            Map<String, Object> result = processEngine.start(
                new StartCmd(code, Map.of("subject", "v2"), Map.of()), starterId);
            return new StartedV2(((Number) result.get("instanceId")).longValue(),
                ((Number) result.get("formDataId")).longValue());
        } finally {
            PrincipalHolder.clear();
        }
    }

    private StartedV2 startLegacyV2(String legacyFlow, long starterId) {
        String publishableFlow = legacyFlow.replace(
            "\"mode\":\"SEQUENTIAL\"", "\"mode\":\"ALL\"");
        long formId = insertForm("DRAFT", VALID_SCHEMA);
        long processId = insertProcess(formId, "DRAFT", publishableFlow);
        String code = jdbcTemplate.queryForObject(
            "SELECT code FROM t_form_definition WHERE id = ?", String.class, formId);
        PrincipalHolder.set(new PrincipalHolder.Principal(starterId, "admin", List.of("admin")));
        try {
            publishService.publish(formId, processId);
            String compatibleLegacyFlow = strictFlow(legacyFlow);
            jdbcTemplate.update("""
                UPDATE t_process_definition_version
                SET process = ?::jsonb,
                    checksum = encode(digest(?::jsonb::text, 'sha256'), 'hex')
                WHERE process_definition_id = ?
                """, compatibleLegacyFlow, compatibleLegacyFlow, processId);
            Map<String, Object> result = processEngine.start(
                new StartCmd(code, Map.of("subject", "legacy-v2"), Map.of()), starterId);
            return new StartedV2(((Number) result.get("instanceId")).longValue(),
                ((Number) result.get("formDataId")).longValue());
        } finally {
            PrincipalHolder.clear();
        }
    }

    private long insertProcess(long formId, String status, String process) {
        String strictProcess = strictFlow(process);
        return jdbcTemplate.queryForObject("""
            INSERT INTO t_process_definition(form_def_id, version, process, status, created_by)
            VALUES (?, 1, ?::jsonb, ?, ?)
            RETURNING id
            """, Long.class, formId, strictProcess, status, userId("admin"));
    }

    private String strictFlow(String flow) {
        try {
            JsonNode parsed = flowJson.readTree(flow);
            if (!(parsed instanceof ObjectNode root) || !"ROOT".equals(root.path("type").asText())) {
                return flow;
            }
            props(root).put("fallbackPolicy", "NODE_REQUIRED");
            addFallbacks(root);
            return flowJson.writeValueAsString(root);
        } catch (Exception error) {
            throw new AssertionError("Unable to prepare test flow", error);
        }
    }

    private void addFallbacks(JsonNode node) {
        if (!(node instanceof ObjectNode object)) return;
        if ("APPROVAL".equals(object.path("type").asText())) {
            ObjectNode props = props(object);
            if (!props.path("fallbackAssignee").isObject()) {
                ObjectNode fallback = props.putObject("fallbackAssignee");
                fallback.put("type", "USER");
                fallback.putArray("ids").add(userId("admin"));
            }
        }
        object.path("branchs").forEach(this::addFallbacks);
        addFallbacks(object.path("children"));
    }

    private static ObjectNode props(ObjectNode node) {
        if (node.path("props") instanceof ObjectNode props) return props;
        ObjectNode props = new ObjectMapper().createObjectNode();
        node.set("props", props);
        return props;
    }

    private long insertParallelTask(long instanceId, String nodeId,
                                    long assigneeId, String branchId) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO t_task(proc_inst_id, node_id, assignee_id, status, approval_mode,
                               task_type, parallel_id, branch_id, version)
            VALUES (?, ?, ?, 'PENDING', 'OR_SIGN', 'APPROVAL', 'p1', ?, 0)
            RETURNING id
            """, Long.class, instanceId, nodeId, assigneeId, branchId);
    }

    private long insertUser(String username) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO t_user(employee_no, username, password_hash, display_name, status)
            VALUES (lpad(nextval('seq_employee_no')::text, 6, '0'), ?, 'unused', ?, 'ACTIVE')
            RETURNING id
            """, Long.class, username, username);
    }

    private long insertRole(String code) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO t_role(code, name, enabled, builtin, version)
            VALUES (?, ?, true, false, 0) RETURNING id
            """, Long.class, code, code);
    }

    private long insertDepartment(long companyId, String name) {
        String path = "test_" + UUID.randomUUID().toString().replace("-", "");
        return jdbcTemplate.queryForObject("""
            INSERT INTO t_department(company_id, path, name)
            VALUES (?, CAST(? AS ltree), ?)
            RETURNING id
            """, Long.class, companyId, path, name);
    }

    private void assignRole(long userId, long roleId) {
        jdbcTemplate.update("INSERT INTO t_user_role(user_id, role_id) VALUES (?, ?)",
            userId, roleId);
    }

    private void setPrincipal(long userId) {
        PrincipalHolder.set(authorizationService.principalForRequest(userId, null).orElseThrow());
    }

    /**
     * 监控概览要能在三种范围下都跑通。这条用**真库**跑，因为上一次的教训正是：单测把 JdbcTemplate
     * mock 掉、只断言 SQL 里 contains("1 = 1")，于是 `AND1 = 1`（Java 文本块吞掉行尾空格）照样"通过"，
     * 真机上 admin 一打开整页 500。
     */
    @Test
    void workflowMonitorOverviewRunsUnderEveryDataScope() {
        // 直接 new 一个控制器：这里验的是 SQL 拼装，不是 @PreAuthorize 那条门（测试里没有代理）。
        WorkflowMonitoringController controller =
            new WorkflowMonitoringController(jdbcTemplate, authorizationService);
        long adminId = userId("admin");
        long scopedUser = insertUser("monitor-scoped");
        long scopedRole = insertRole("monitor_scoped");
        long emptyUser = insertUser("monitor-empty");
        long emptyRole = insertRole("monitor_empty");
        try {
            jdbcTemplate.update("INSERT INTO t_role_permission(role_id, permission_code, scope_override) "
                + "VALUES (?, 'workflow:monitor:read', 'SELF')", scopedRole);
            assignRole(scopedUser, scopedRole);
            // DEPARTMENT 但这个用户没有部门 → 谓词集合为空 → 必须恒假（fail-closed），不能退化成不过滤
            jdbcTemplate.update("INSERT INTO t_role_permission(role_id, permission_code, scope_override) "
                + "VALUES (?, 'workflow:monitor:read', 'DEPARTMENT')", emptyRole);
            assignRole(emptyUser, emptyRole);

            // ① admin / unrestricted：谓词是 1 = 1
            setPrincipal(adminId);
            assertThat(controller.overview(50)).containsKeys("stuckInstances", "overdueTasks",
                "nodeRejectionRates", "fallbackBacklogs", "outbox");
            // ② 受限（本人）：谓词是 (instance.started_by = ?)，参数个数要与占位符对上
            setPrincipal(scopedUser);
            assertThat(controller.overview(50)).containsKeys("stuckInstances", "overdueTasks",
                "nodeRejectionRates", "fallbackBacklogs", "outbox");
            // ③ 空范围：谓词是 1 = 0
            setPrincipal(emptyUser);
            Map<String, Object> empty = controller.overview(50);
            assertThat(empty).containsKeys("stuckInstances", "overdueTasks", "nodeRejectionRates",
                "fallbackBacklogs", "outbox");
            assertThat((List<?>) empty.get("stuckInstances")).isEmpty();
            assertThat((Map<String, Object>) empty.get("outbox")).containsEntry("dead", 0L);
        } finally {
            PrincipalHolder.clear();
            jdbcTemplate.update("DELETE FROM t_user_role WHERE user_id IN (?, ?)", scopedUser, emptyUser);
            jdbcTemplate.update("DELETE FROM t_role WHERE id IN (?, ?)", scopedRole, emptyRole);
            jdbcTemplate.update("DELETE FROM t_user WHERE id IN (?, ?)", scopedUser, emptyUser);
            authorizationService.evict(scopedUser);
            authorizationService.evict(emptyUser);
        }
    }

    private Object replaceMenu(MenuService.MenuDocument request, CountDownLatch ready,
                               CountDownLatch start) throws InterruptedException {
        PrincipalHolder.set(new PrincipalHolder.Principal(1L, "admin", List.of("admin")));
        ready.countDown();
        start.await(10, TimeUnit.SECONDS);
        try {
            return menuService.replace(request);
        } catch (Throwable error) {
            return error;
        } finally {
            PrincipalHolder.clear();
        }
    }

    private Object replaceMaintainers(long formId, int version, Set<Long> userIds,
                                      CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        PrincipalHolder.set(new PrincipalHolder.Principal(1L, "admin", List.of("admin")));
        ready.countDown();
        start.await(10, TimeUnit.SECONDS);
        try {
            return formGrantService.replaceMaintainers(formId,
                new FormGrantService.FormMaintainerWriteRequest(version, userIds));
        } catch (Throwable error) {
            return error;
        } finally {
            PrincipalHolder.clear();
        }
    }

    private long userId(String username) {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM t_user WHERE username = ?", Long.class, username);
    }

    private long roleId(String code) {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM t_role WHERE code = ?", Long.class, code);
    }

    private long activeAdminCount() {
        return jdbcTemplate.queryForObject("""
            SELECT COUNT(DISTINCT u.id)
            FROM t_user u
            JOIN t_user_role ur ON ur.user_id = u.id
            JOIN t_role role ON role.id = ur.role_id
            WHERE u.status = 'ACTIVE' AND role.code = 'admin' AND role.enabled = true
            """, Long.class);
    }

    private void assertDefinitionState(String table, long id, String status, int version) {
        Map<String, Object> state = jdbcTemplate.queryForMap(
            "SELECT status, version FROM " + table + " WHERE id = ?", id);
        assertThat(state.get("status")).isEqualTo(status);
        assertThat(((Number) state.get("version")).intValue()).isEqualTo(version);
    }

    private static String parallelFlow(long firstAssignee, long secondAssignee,
                                       long joinAssignee) {
        return parallelFlow("ALL", firstAssignee, secondAssignee, joinAssignee);
    }

    private static String parallelFlow(String joinMode, long firstAssignee,
                                       long secondAssignee, long joinAssignee) {
        return """
            {"id":"root","type":"ROOT","children":{
              "id":"p1","type":"PARALLEL","props":{"joinMode":"%s"},"branchs":[
                {"id":"b1","type":"BRANCH","children":{
                  "id":"a1","type":"APPROVAL","props":{
                    "assignedType":"ASSIGN_USER","assignedUser":[%d],"mode":"OR"},
                  "children":null}},
                {"id":"b2","type":"BRANCH","children":{
                  "id":"a2","type":"APPROVAL","props":{
                    "assignedType":"ASSIGN_USER","assignedUser":[%d],"mode":"OR"},
                  "children":null}}
              ],"children":{
                "id":"a3","type":"APPROVAL","props":{
                  "assignedType":"ASSIGN_USER","assignedUser":[%d],"mode":"OR"},
                "children":null}}
            }
            """.formatted(joinMode, firstAssignee, secondAssignee, joinAssignee);
    }

    private static String approvalFlow(long assigneeId) {
        return """
            {"id":"root","type":"ROOT","children":{
              "id":"a1","type":"APPROVAL","props":{
                "assignedType":"ASSIGN_USER","assignedUser":[%d],"mode":"OR"},
              "children":null}}
            """.formatted(assigneeId);
    }

    private static String allSignFlow(long first, long second, long downstream) {
        return multiSignFlow("ALL", first, second, downstream);
    }

    private static String anySignFlow(long first, long second, long downstream) {
        return multiSignFlow("ANY", first, second, downstream);
    }

    private static String multiSignFlow(String mode, long first, long second, long downstream) {
        return """
            {"id":"root","type":"ROOT","children":{
              "id":"a1","type":"APPROVAL","props":{
                "assignedType":"ASSIGN_USER","assignedUser":[%d,%d],"mode":"%s"},
              "children":{"id":"a2","type":"APPROVAL","props":{
                "assignedType":"ASSIGN_USER","assignedUser":[%d],"mode":"ANY"},
                "children":null}}}
            """.formatted(first, second, mode, downstream);
    }

    private static String multiSignFlow(String mode, List<Long> approvers, Integer ratio,
                                        long downstream) {
        String ids = approvers.stream().map(String::valueOf)
            .collect(java.util.stream.Collectors.joining(","));
        String ratioProperty = ratio == null ? "" : ",\"ratio\":" + ratio;
        return """
            {"id":"root","type":"ROOT","children":{
              "id":"a1","type":"APPROVAL","props":{
                "assignedType":"ASSIGN_USER","assignedUser":[%s],"mode":"%s"%s},
              "children":{"id":"a2","type":"APPROVAL","props":{
                "assignedType":"ASSIGN_USER","assignedUser":[%d],"mode":"ANY"},
                "children":null}}}
            """.formatted(ids, mode, ratioProperty, downstream);
    }

    private static String approvalWithFallbackFlow(long unavailable, long fallback) {
        return """
            {"id":"root","type":"ROOT","children":{
              "id":"a1","type":"APPROVAL","props":{
                "assignedType":"ASSIGN_USER","assignedUser":[%d],"mode":"OR",
                "fallbackAssignee":{"type":"USER","ids":[%d]}},
              "children":null}}
            """.formatted(unavailable, fallback);
    }

    private static String timeoutFlow(long assigneeId) {
        return """
            {"id":"root","type":"ROOT","children":{
              "id":"a1","type":"APPROVAL","props":{
                "assignedType":"ASSIGN_USER","assignedUser":[%d],"mode":"OR",
                "timeoutPolicy":{"afterMinutes":10,"action":"REMIND"}},
              "children":null}}
            """.formatted(assigneeId);
    }

    private static String ccThenApprovalFlow(long recipientId, long approverId) {
        return """
            {"id":"root","type":"ROOT","children":{
              "id":"cc1","type":"CC","props":{"assignedUser":[%d]},
              "children":{"id":"a1","type":"APPROVAL","props":{
                "assignedType":"ASSIGN_USER","assignedUser":[%d],"mode":"OR"},
                "children":null}}}
            """.formatted(recipientId, approverId);
    }

    private static String twoCcThenApprovalFlow(long recipientId, long approverId) {
        return """
            {"id":"root","type":"ROOT","children":{
              "id":"cc1","type":"CC","props":{"assignedUser":[%d]},
              "children":{"id":"cc2","type":"CC","props":{"assignedUser":[%d]},
              "children":{"id":"a1","type":"APPROVAL","props":{
                "assignedType":"ASSIGN_USER","assignedUser":[%d],"mode":"OR"},
                "children":null}}}}
            """.formatted(recipientId, recipientId, approverId);
    }

    private static String parallelResubmitFlow(String strategy, long first, long second,
                                               long downstream) {
        return """
            {"id":"root","type":"ROOT","props":{"settings":{
              "resubmitStrategy":"%s"}},"children":{
              "id":"p1","type":"PARALLEL","props":{"joinMode":"ALL"},"branchs":[
                {"id":"b1","type":"BRANCH","children":{
                  "id":"a1","type":"APPROVAL","props":{
                    "assignedType":"ASSIGN_USER","assignedUser":[%d],"mode":"OR",
                    "formPerms":[{"fieldId":"subject","mode":"READONLY"}]},
                  "children":null}},
                {"id":"b2","type":"BRANCH","children":{
                  "id":"a2","type":"APPROVAL","props":{
                    "assignedType":"ASSIGN_USER","assignedUser":[%d],"mode":"OR"},
                  "children":null}}
              ],"children":{"id":"a3","type":"APPROVAL","props":{
                "assignedType":"ASSIGN_USER","assignedUser":[%d],"mode":"OR"},
                "children":null}}
            }
            """.formatted(strategy, first, second, downstream);
    }

    private static String twoApprovalFlow(long firstAssignee, long secondAssignee) {
        return """
            {"id":"root","type":"ROOT","children":{
              "id":"a1","type":"APPROVAL","props":{
                "assignedType":"ASSIGN_USER","assignedUser":[%d],"mode":"OR"},
              "children":{"id":"a2","type":"APPROVAL","props":{
                "assignedType":"ASSIGN_USER","assignedUser":[%d],"mode":"OR"},
                "children":null}}}
            """.formatted(firstAssignee, secondAssignee);
    }

    private static String threeApprovalFlow(long first, long second, long third) {
        return """
            {"id":"root","type":"ROOT","children":{
              "id":"a1","type":"APPROVAL","props":{
                "assignedType":"ASSIGN_USER","assignedUser":[%d],"mode":"OR"},
              "children":{"id":"a2","type":"APPROVAL","props":{
                "assignedType":"ASSIGN_USER","assignedUser":[%d],"mode":"OR"},
              "children":{"id":"a3","type":"APPROVAL","props":{
                "assignedType":"ASSIGN_USER","assignedUser":[%d],"mode":"OR",
                "rejectTargets":["a1"]},"children":null}}}}
            """.formatted(first, second, third);
    }

    private static String reportingManagerFlow(long firstAssignee, int managerLevel) {
        return """
            {"id":"root","type":"ROOT","children":{
              "id":"a1","type":"APPROVAL","props":{
                "assignedType":"ASSIGN_USER","assignedUser":[%d],"mode":"OR"},
              "children":{"id":"a2","type":"APPROVAL","props":{
                "assignedType":"DIRECT_MANAGER","manager":{"level":%d},"mode":"OR",
                "nobody":{"handler":"TO_PASS"}},"children":null}}}
            """.formatted(firstAssignee, managerLevel);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private record StartedV2(long instanceId, long formDataId) { }
}
