package com.antflow.task;

import com.antflow.auth.PrincipalHolder;
import com.antflow.engine.BizException;
import com.antflow.engine.ProcessEngine;
import com.antflow.engine.dto.CompleteCmd;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 任务操作服务 — Sprint 2 阶段三件套：转交 / 委托 / 加签。
 *
 * <p>三者都通过"创建子任务 + 关闭父任务 + 写历史"实现：
 * <ul>
 *   <li>{@link #transfer}: 审批人把任务转给另一个人（被转的人实际审批，原 assignee 仅审计）。
 *       parent_task_id 指向原任务；delegated_from = 原 assignee；status=PENDING。</li>
 *   <li>{@link #delegate}: 申请人把"还没处理"的任务委托给其他人审批，原始任务不动；
 *       被委托人通过 delegated_from 字段区分。原任务不动 — 委托是"镜像任务"。</li>
 *   <li>{@link #addAssignee}: 审批人主动加签（前/后加签），与原任务一起决定本节点结果。
 *       新任务 parent_task_id = 原任务，is_additional = true；与原任务一起 OR/AND。</li>
 * </ul>
 *
 * <p>设计权衡：
 * <ul>
 *   <li>三个操作都用统一的"建子任务 + 写历史"模式，未来扩展（比如 cc-to-self、催办）只加方法。</li>
 *   <li>不引入 task-level workflow state machine — 当前实现是"乐观：父任务立刻 SKIPPED，
 *       子任务继承原 mode"。后续若需要"并行父 + 子"再升级为 state machine。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class TaskOperationService {

    private final TaskMapper taskMapper;
    private final TaskHistoryMapper historyMapper;
    private final ProcessInstanceMapper instanceMapper;
    private final ProcessEngine engine;

    /**
     * 转交：把 taskId 转给 targetUserId。taskId 必须 PENDING；operator 必须是当前 assignee。
     * 父任务变 SKIPPED；新任务 PENDING，delegated_from = operator。
     */
    @Transactional
    public Long transfer(long taskId, long targetUserId, String comment) {
        var p = PrincipalHolder.current().orElseThrow();
        TaskEntity parent = taskMapper.selectById(taskId);
        if (parent == null || !"PENDING".equals(parent.getStatus())) {
            throw new BizException("TASK_NOT_PENDING", "task not pending");
        }
        if (!java.util.Objects.equals(parent.getAssigneeId(), p.userId())) {
            throw new AccessDeniedException("only current assignee can transfer");
        }
        // 关父任务
        parent.setStatus("SKIPPED");
        parent.setComment(comment);
        taskMapper.updateById(parent);
        // 建子任务：assignee = targetUserId，parent_task_id = parent.id，delegated_from = 原 assignee
        TaskEntity child = new TaskEntity();
        child.setProcInstId(parent.getProcInstId());
        child.setNodeId(parent.getNodeId());
        child.setAssigneeId(targetUserId);
        child.setStatus("PENDING");
        child.setApprovalMode(parent.getApprovalMode());
        child.setParentTaskId(parent.getId());
        child.setDelegatedFrom(parent.getAssigneeId());
        child.setIsAdditional(false);
        taskMapper.insert(child);
        // 写历史
        historyMapper.insert(historyRow(parent.getProcInstId(),
            parent.getId(), child.getId(), parent.getNodeId(), parent.getNodeId(),
            "TRANSFER", p.userId(), comment));
        return child.getId();
    }

    /**
     * 委托：把 taskId 委托给 targetUserId。operator 必须是当前 assignee（审批人请假，
     * 把活转给同事）。与转交的区别：原任务不关 — 委托是"镜像任务"，原任务等批准后由被委托人
     * 完成。原 assignee 也会收到最终结果通知（通过 delegated_from 反查）。
     */
    @Transactional
    public Long delegate(long taskId, long targetUserId, String comment) {
        var p = PrincipalHolder.current().orElseThrow();
        TaskEntity parent = taskMapper.selectById(taskId);
        if (parent == null || !"PENDING".equals(parent.getStatus())) {
            throw new BizException("TASK_NOT_PENDING", "task not pending");
        }
        if (!java.util.Objects.equals(parent.getAssigneeId(), p.userId())) {
            throw new AccessDeniedException("only current assignee can delegate");
        }
        TaskEntity child = new TaskEntity();
        child.setProcInstId(parent.getProcInstId());
        child.setNodeId(parent.getNodeId());
        child.setAssigneeId(targetUserId);
        child.setStatus("PENDING");
        child.setApprovalMode(parent.getApprovalMode());
        child.setParentTaskId(parent.getId());
        child.setDelegatedFrom(parent.getAssigneeId());
        child.setIsAdditional(false);
        taskMapper.insert(child);
        historyMapper.insert(historyRow(parent.getProcInstId(),
            parent.getId(), child.getId(), parent.getNodeId(), parent.getNodeId(),
            "DELEGATE", p.userId(), comment));
        return child.getId();
    }

    /**
     * 加签：审批人在自己审批的同时拉别人一起审（前/后加签）。
     * 新任务 is_additional=true；原任务不动，由 approve 流程合并判定。
     */
    @Transactional
    public Long addAssignee(long taskId, long targetUserId, String comment) {
        var p = PrincipalHolder.current().orElseThrow();
        TaskEntity parent = taskMapper.selectById(taskId);
        if (parent == null || !"PENDING".equals(parent.getStatus())) {
            throw new BizException("TASK_NOT_PENDING", "task not pending");
        }
        if (!java.util.Objects.equals(parent.getAssigneeId(), p.userId())) {
            throw new AccessDeniedException("only current assignee can add reviewer");
        }
        TaskEntity child = new TaskEntity();
        child.setProcInstId(parent.getProcInstId());
        child.setNodeId(parent.getNodeId());
        child.setAssigneeId(targetUserId);
        child.setStatus("PENDING");
        child.setApprovalMode(parent.getApprovalMode());
        child.setParentTaskId(parent.getId());
        child.setIsAdditional(true);
        taskMapper.insert(child);
        historyMapper.insert(historyRow(parent.getProcInstId(),
            parent.getId(), child.getId(), parent.getNodeId(), parent.getNodeId(),
            "ADD_ASSIGNEE", p.userId(), comment));
        return child.getId();
    }

    /** 撤回转交/委托/加签产生的子任务。operator 必须是子任务 assignee。 */
    @Transactional
    public void recallChild(long childTaskId, String comment) {
        var p = PrincipalHolder.current().orElseThrow();
        TaskEntity child = taskMapper.selectById(childTaskId);
        if (child == null || !"PENDING".equals(child.getStatus())) {
            throw new BizException("TASK_NOT_PENDING", "child task not pending");
        }
        if (!java.util.Objects.equals(child.getAssigneeId(), p.userId())) {
            throw new AccessDeniedException("only child assignee can recall");
        }
        child.setStatus("SKIPPED");
        child.setComment(comment);
        taskMapper.updateById(child);

        // TRANSFER 类型：父任务已被 SKIPPED，恢复父任务
        // DELEGATE/ADD_ASSIGNEE 类型：父任务原状，无须恢复
        TaskHistoryEntity latestTrans = null;
        var hists = historyMapper.selectList(new QueryWrapper<TaskHistoryEntity>()
            .eq("task_id", child.getId())
            .in("action", "TRANSFER", "DELEGATE", "ADD_ASSIGNEE")
            .orderByDesc("id"));
        if (!hists.isEmpty()) {
            latestTrans = hists.get(0);
        }
        if ("TRANSFER".equals(latestTrans != null ? latestTrans.getAction() : null)) {
            TaskEntity parent = taskMapper.selectById(child.getParentTaskId());
            if (parent != null && "SKIPPED".equals(parent.getStatus())) {
                parent.setStatus("PENDING");
                parent.setComment(null);
                taskMapper.updateById(parent);
            }
        }
        historyMapper.insert(historyRow(child.getProcInstId(),
            child.getId(), null, child.getNodeId(), child.getNodeId(),
            "RECALL_CHILD", p.userId(), comment));
    }

    /**
     * 列出某个 assignee 当前 PENDING 的任务（含转交/委托/加签产生的子任务）。
     */
    public List<TaskEntity> listMyInbox(long userId, String status) {
        var q = new QueryWrapper<TaskEntity>()
            .eq("assignee_id", userId)
            .eq("status", status == null ? "PENDING" : status)
            .orderByDesc("created_at");
        return taskMapper.selectList(q);
    }

    /** 列出某父任务的所有子任务（用于详情页展示转交/加签链路）。 */
    public List<TaskEntity> listChildren(long parentTaskId) {
        return taskMapper.selectList(new QueryWrapper<TaskEntity>()
            .eq("parent_task_id", parentTaskId)
            .orderByAsc("created_at"));
    }

    private static TaskHistoryEntity historyRow(Long instId, Long taskId, Long childId,
                                                String fromNode, String toNode,
                                                String action, Long op, String comment) {
        var h = new TaskHistoryEntity();
        h.setProcInstId(instId);
        h.setTaskId(taskId);
        h.setFromNodeId(fromNode);
        h.setToNodeId(toNode);
        h.setAction(action);
        h.setOperatorId(op);
        h.setComment(comment);
        return h;
    }
}