package com.antflow.engine;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code t_process_instance} 的**唯一**原始 SQL 写入入口。
 *
 * <p>为什么单独成类：拆分后 {@code recallApproval} 需要把实例的"当前节点"指回被追回的节点。
 * 如果这条 UPDATE 跟着 {@code InstanceLifecycleOps} 走、而别处又各自写实例表，"谁在写主表"
 * 就再也说不清（V2 是新实例的唯一运行时，主表被写坏无法靠重放修复）。收口到一个只有一条 SQL
 * 的类之后，核验方式退化成一句 grep：`UPDATE t_process_instance` 只应命中本文件。
 *
 * <p>**不要**把 {@code processInstanceMapper.updateById} 也塞进来：那是既有 MyBatis 路径，
 * 由 13 个协作者各自调用（status/finishedAt 等字段），搬到此处会改变更新范围（整行覆盖 vs 单列），
 * 属于行为变更，不在本次拆分范围。
 *
 * <p>**不加 {@code @Transactional}**：事务边界在调用方（{@code ProcessEngine.recallApproval}）。
 */
final class ProcessInstanceWriter {

    private final JdbcTemplate jdbc;

    ProcessInstanceWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 把实例的当前节点指回指定节点（追回审批用），并推进乐观锁版本号。 */
    void repointCurrentNode(long procInstId, String nodeId, Long nodeInstanceId) {
        jdbc.update("""
            UPDATE t_process_instance SET current_node_id = ?, current_node_instance_id = ?,
                                          version = version + 1
            WHERE id = ?
            """, nodeId, nodeInstanceId, procInstId);
    }
}
