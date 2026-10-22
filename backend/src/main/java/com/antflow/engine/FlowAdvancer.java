package com.antflow.engine;

import com.antflow.engine.dto.CompleteCmd;
import com.antflow.task.ProcessInstance;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 「继续推进流程」的回调口，由 {@link ProcessEngine} 实现；结算/自动化类只依赖这个接口。
 *
 * <p>为什么是接口而不是直接持有 ProcessEngine：ProcessEngine 必须持有这些协作者，
 * 协作者又要回调"落地节点"。直接写死一边就成了双向依赖（构造期还会撞上 Spring 的循环依赖）；
 * 接口把它切成单向：协作者 → FlowAdvancer ← ProcessEngine。
 *
 * <p>两条纪律：
 * <ol>
 *   <li>实现方**不能**在这些方法上加 {@code @Transactional}。它们总是从 ProcessEngine 的
 *       事务方法内部被调用，本来就在同一个事务里；再加一层会改变锁与回滚的语义。</li>
 *   <li>方法名沿用拆分前的 {@code resolveAndLand} / {@code advanceParallelBranch} 等原名，
 *       便于逐行比对"搬迁是否只改了可见性"。</li>
 * </ol>
 */
public interface FlowAdvancer {

    List<Long> resolveAndLand(JsonNode root, ProcessInstance instance, JsonNode formData,
                              long starterId, Map<String, List<Long>> selfSelected,
                              JsonNode fromNode);

    List<Long> resolveAndLandFromNode(JsonNode root, ProcessInstance instance,
                                      JsonNode formData, long starterId,
                                      Map<String, List<Long>> selfSelected,
                                      JsonNode targetNode);

    void advanceParallelBranch(JsonNode root, ProcessInstance instance, JsonNode formData,
                              String parallelId, String branchId, JsonNode completedNode);

    /**
     * 系统代批（超时自动通过）。force 恒为真——系统不是任务的审批人；
     * historyAction 用来把"系统代批"与"人工点通过"在审计里区分开（code-review A9）。
     */
    void forceApproveAsSystem(CompleteCmd cmd, long operatorId, String historyAction);
}
