package com.antflow.integration;

import com.antflow.audit.AuditService;
import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.FormGrantService;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.RoleAdminService;
import com.antflow.engine.BizException;
import com.antflow.engine.NoAssigneeFoundException;
import com.antflow.engine.ProcessEngine;
import com.antflow.engine.dto.CompleteCmd;
import com.antflow.engine.dto.StartCmd;
import com.antflow.form.FormProcessPublishService;
import com.antflow.form.FormDefinitionMapper;
import com.antflow.integration.wecom.WecomService;
import com.antflow.mobile.workflow.MobileWorkflowMapper;
import com.antflow.org.UserService;
import com.antflow.task.ProcessInstanceMapper;
import com.antflow.task.TaskMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
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
    "antflow.automation.recovery-interval-ms=3600000",
    "antflow.outbox.poll-interval-ms=3600000"
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
    @Autowired private ProcessInstanceMapper processInstanceMapper;
    @Autowired private TaskMapper taskMapper;
    @Autowired private FormGrantService formGrantService;
    @Autowired private MobileWorkflowMapper mobileWorkflowMapper;
    @Autowired private AuthorizationService authorizationService;
    @Autowired private RoleAdminService roleAdminService;
    @Autowired private FormDefinitionMapper formDefinitionMapper;
    @Autowired private WecomService wecomService;

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
    void v2UsesNodeFallbackThenAdministratorAndFreezesDelegationOnTask() {
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

        StartedV2 adminFallback = startV2(approvalFlow(unavailable), adminId);
        assertThat(jdbcTemplate.queryForList("""
            SELECT DISTINCT role.code FROM t_task task
            JOIN t_user_role user_role ON user_role.user_id = task.assignee_id
            JOIN t_role role ON role.id = user_role.role_id
            WHERE task.proc_inst_id = ? AND task.status = 'PENDING'
            """, String.class, adminFallback.instanceId())).contains("admin");

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
    void workplaceAuthorizationQueriesExecuteAgainstPostgres() {
        long adminId = userId("admin");
        assertThat(processInstanceMapper.selectWorkplaceRecent(adminId, true, true, true, 8))
            .isNotNull();
        OffsetDateTime now = OffsetDateTime.now();
        assertThat(processInstanceMapper.selectWorkplaceStatusCounts(adminId, true, true, true,
            now.minusDays(1), now.plusDays(1))).isNotNull();
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
            "authorized", null, ownerId, null, 1, 0))
            .extracting(com.antflow.task.ProcessInstance::getId)
            .containsExactly(visibleInstanceId);
        assertThat(processInstanceMapper.countInstancePage(viewerId, false, true, false,
            "authorized", null, ownerId, null)).isEqualTo(1L);

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
            "mine", null, null, null, 10, 0))
            .extracting(com.antflow.task.ProcessInstance::getId)
            .containsExactly(mineInstanceId);
        assertThat(processInstanceMapper.countInstancePage(viewerId, false, true, false,
            "mine", null, null, null)).isEqualTo(1L);

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
        String code = "IT_" + UUID.randomUUID().toString().replace("-", "");
        return jdbcTemplate.queryForObject("""
            INSERT INTO t_form_definition(code, name, version, schema, settings, status,
                                          created_by, deleted)
            VALUES (?, 'Integration form', 1, ?::jsonb, '{}'::jsonb, ?, ?, 0)
            RETURNING id
            """, Long.class, code, schema, status, userId("admin"));
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
            jdbcTemplate.update("""
                UPDATE t_process_definition_version
                SET process = ?::jsonb,
                    checksum = encode(digest(?::jsonb::text, 'sha256'), 'hex')
                WHERE process_definition_id = ?
                """, legacyFlow, legacyFlow, processId);
            Map<String, Object> result = processEngine.start(
                new StartCmd(code, Map.of("subject", "legacy-v2"), Map.of()), starterId);
            return new StartedV2(((Number) result.get("instanceId")).longValue(),
                ((Number) result.get("formDataId")).longValue());
        } finally {
            PrincipalHolder.clear();
        }
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
