package com.antflow.integration;

import com.antflow.audit.AuditService;
import com.antflow.auth.PrincipalHolder;
import com.antflow.engine.BizException;
import com.antflow.engine.ProcessEngine;
import com.antflow.engine.dto.CompleteCmd;
import com.antflow.form.FormProcessPublishService;
import com.antflow.org.UserService;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(properties = {
    "antflow.audit.archive-cron=-",
    "antflow.automation.poll-interval-ms=3600000",
    "antflow.automation.recovery-interval-ms=3600000"
})
class PostgresTransactionalIntegrityIntegrationTest {
    private static final String VALID_SCHEMA =
        "[{\"id\":\"subject\",\"type\":\"text\",\"label\":\"Subject\"}]";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("antflow")
            .withUsername("antflow")
            .withPassword("antflow");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> {
            String url = POSTGRES.getJdbcUrl();
            return url + (url.contains("?") ? "&" : "?") + "stringtype=unspecified";
        });
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ProcessEngine processEngine;
    @Autowired private UserService userService;
    @Autowired private AuditService auditService;
    @Autowired private FormProcessPublishService publishService;

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
        long userRoleId = roleId("user");
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
        String code = "IT_" + UUID.randomUUID().toString().replace("-", "");
        return jdbcTemplate.queryForObject("""
            INSERT INTO t_form_definition(code, name, version, schema, settings, status,
                                          created_by, deleted)
            VALUES (?, 'Integration form', 1, ?::jsonb, '{}'::jsonb, ?, ?, 0)
            RETURNING id
            """, Long.class, code, schema, status, userId("admin"));
    }

    private long insertProcess(long formId, String status, String process) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO t_process_definition(form_def_id, version, process, status, created_by)
            VALUES (?, 1, ?::jsonb, ?, ?)
            RETURNING id
            """, Long.class, formId, process, status, userId("admin"));
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

    private void assignRole(long userId, long roleId) {
        jdbcTemplate.update("INSERT INTO t_user_role(user_id, role_id) VALUES (?, ?)",
            userId, roleId);
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
        return """
            {"id":"root","type":"ROOT","children":{
              "id":"p1","type":"PARALLEL","branchs":[
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
            """.formatted(firstAssignee, secondAssignee, joinAssignee);
    }

    private static String approvalFlow(long assigneeId) {
        return """
            {"id":"root","type":"ROOT","children":{
              "id":"a1","type":"APPROVAL","props":{
                "assignedType":"ASSIGN_USER","assignedUser":[%d],"mode":"OR"},
              "children":null}}
            """.formatted(assigneeId);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
