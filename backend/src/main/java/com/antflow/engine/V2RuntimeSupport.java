package com.antflow.engine;

import com.antflow.form.runtime.FormData;
import com.antflow.process.DefinitionVersionRepository;
import com.antflow.task.ProcessInstance;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * V2 运行时的共享底座：V2 判定、事务守卫、版本树/表单 Schema 读取、outbox、节点行锁、表单修订。
 *
 * <p>为什么要有这一层：{@code WorkflowRuntimeV2} 拆成多个协作者后，{@code outbox}、{@code lockNode}
 * 这类"每条写入路径都要用"的工具如果留在某一簇，另外几簇就必须反向依赖那一簇。下沉到这里之后，
 * 依赖方向是单向的（各簇 → 底座）。
 *
 * <p>**不加 {@code @Transactional}**：这些方法全部跑在调用方（{@code ProcessEngine} 的
 * approve/reject/start 等）的事务里，自己开事务会把"任务 + 节点 + outbox"切成两段提交。
 */
final class V2RuntimeSupport {

    private final JdbcTemplate jdbc;
    private final DefinitionVersionRepository versions;

    V2RuntimeSupport(JdbcTemplate jdbc, DefinitionVersionRepository versions) {
        this.jdbc = jdbc;
        this.versions = versions;
    }

    /** {@code engine_version == 2} 的实例才走 V2 运行时；V1 是只读兼容层。 */
    static boolean isV2(ProcessInstance instance) {
        return instance != null && Objects.equals(instance.getEngineVersion(), 2);
    }

    /**
     * 写入口的事务守卫。
     *
     * <p>V2 运行时会一口气写多张表（节点实例、参与者、任务、outbox、作业），**必须**由调用方开启事务。
     * 原来只靠"调用方恰好都加了 @Transactional"这一约定：一旦有人从调度器或 controller 直接调用，
     * 多语句会逐条自动提交，留下半成品状态且无任何报错（见 code-review A6）。
     * 与其给全类硬加 @Transactional（会和调用方事务嵌套、语义纠缠），不如在入口快速失败。
     */
    static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                "WorkflowRuntimeV2 的写操作必须在事务内调用：请给调用方补 @Transactional");
        }
    }

    String processTree(ProcessInstance instance) {
        if (!isV2(instance) || instance.getProcessDefinitionVersionId() == null) {
            return instance.getProcessSnapshot();
        }
        String process = jdbc.query("""
            SELECT process::text FROM t_process_definition_version WHERE id = ?
            """, rs -> rs.next() ? rs.getString(1) : null,
            instance.getProcessDefinitionVersionId());
        if (process == null) throw new BizException("VERSION_NOT_FOUND", "process version not found");
        return process;
    }

    String formSchema(ProcessInstance instance) {
        if (!isV2(instance) || instance.getCurrentFormRevisionId() == null) return null;
        return jdbc.query("""
            SELECT version.schema::text
            FROM t_form_data_revision revision
            JOIN t_form_definition_version version
              ON version.id = revision.form_definition_version_id
            WHERE revision.id = ?
            """, rs -> rs.next() ? rs.getString(1) : null,
            instance.getCurrentFormRevisionId());
    }

    long createRevision(FormData data, String status, String reason, long actorId) {
        return versions.createRevision(data, status, reason, actorId);
    }

    void outbox(long instanceId, Long recipientId, String type, Long taskId) {
        outbox(instanceId, recipientId, type, taskId, null);
    }

    /** 带轮次的 outbox，只给抄送批次用（{@code CC_ASSIGNED} 需要按轮次去重）。 */
    void outbox(long instanceId, Long recipientId, String type, Long taskId, Integer roundNo) {
        jdbc.update("""
            INSERT INTO t_workflow_outbox(
                aggregate_type, aggregate_id, event_type, recipient_id, payload)
            VALUES ('PROCESS_INSTANCE', ?, ?, ?,
                    jsonb_strip_nulls(jsonb_build_object(
                        'instanceId', ?::bigint, 'taskId', ?::bigint, 'roundNo', ?::int)))
            """, instanceId, type, recipientId, instanceId, taskId, roundNo);
    }

    /** 节点实例行锁；与 {@code cancelPending} 的"先锁后改"顺序配合，避免并发重复取消。 */
    void lockNode(long nodeInstanceId) {
        jdbc.queryForObject("SELECT id FROM t_process_node_instance WHERE id = ? FOR UPDATE",
            Long.class, nodeInstanceId);
    }
}
