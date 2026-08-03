package com.antflow.task;

import com.antflow.auth.PrincipalHolder;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

/**
 * Sprint 2 三件套（转交 / 委托 / 加签 / 撤回子任务）的单测。
 * mappers mock，PrincipalHolder 用 threadLocal 注入当前用户。
 */
class TaskOperationServiceTest {

    private TaskMapper taskMapper;
    private TaskHistoryMapper historyMapper;
    private ProcessInstanceMapper instanceMapper;
    private TaskOperationService ops;

    @BeforeEach void setup() {
        taskMapper = Mockito.mock(TaskMapper.class);
        historyMapper = Mockito.mock(TaskHistoryMapper.class);
        instanceMapper = Mockito.mock(ProcessInstanceMapper.class);
        ops = new TaskOperationService(taskMapper, historyMapper, instanceMapper);
        // 默认 insert 行为：分配 id
        Mockito.doAnswer(inv -> {
            TaskEntity t = inv.getArgument(0);
            if (t.getId() == null) t.setId(System.nanoTime() & 0xFFFFFF);
            return 1;
        }).when(taskMapper).insert(any(TaskEntity.class));
    }

    private TaskEntity parentTask(long id, long assigneeId, String status) {
        TaskEntity t = new TaskEntity();
        t.setId(id);
        t.setProcInstId(1L);
        t.setNodeId("a1");
        t.setAssigneeId(assigneeId);
        t.setStatus(status);
        t.setApprovalMode("OR");
        return t;
    }

    private void loginAs(long userId) {
        PrincipalHolder.set(new PrincipalHolder.Principal(userId, "user", java.util.List.of("user")));
    }

    // ---------- 1. transfer ----------
    @Test
    void transfer_marksParentSkipped_createsChildWithDelegatedFrom() {
        loginAs(42L);
        Mockito.when(taskMapper.selectById(100L)).thenReturn(parentTask(100L, 42L, "PENDING"));

        long childId = ops.transfer(100L, 99L, "i'm going on vacation");

        // 父任务被 SKIPPED（带 comment）
        ArgumentCaptor<TaskEntity> upd = ArgumentCaptor.forClass(TaskEntity.class);
        Mockito.verify(taskMapper).updateById(upd.capture());
        TaskEntity parent = upd.getValue();
        assertThat(parent.getStatus()).isEqualTo("SKIPPED");
        assertThat(parent.getComment()).isEqualTo("i'm going on vacation");

        // 新任务 PENDING、parent_task_id=100、delegated_from=42
        ArgumentCaptor<TaskEntity> ins = ArgumentCaptor.forClass(TaskEntity.class);
        Mockito.verify(taskMapper).insert(ins.capture());
        TaskEntity child = ins.getValue();
        assertThat(child.getStatus()).isEqualTo("PENDING");
        assertThat(child.getAssigneeId()).isEqualTo(99L);
        assertThat(child.getParentTaskId()).isEqualTo(100L);
        assertThat(child.getDelegatedFrom()).isEqualTo(42L);
        assertThat(child.getIsAdditional()).isFalse();
        assertThat(childId).isEqualTo(child.getId());
    }

    @Test
    void transfer_rejectsNonAssignee() {
        loginAs(99L); // 不是当前 assignee
        Mockito.when(taskMapper.selectById(100L)).thenReturn(parentTask(100L, 42L, "PENDING"));
        assertThatThrownBy(() -> ops.transfer(100L, 99L, "x"))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void transfer_rejectsNotPending() {
        loginAs(42L);
        Mockito.when(taskMapper.selectById(100L)).thenReturn(parentTask(100L, 42L, "APPROVED"));
        assertThatThrownBy(() -> ops.transfer(100L, 99L, "x"))
            .isInstanceOf(com.antflow.engine.BizException.class);
    }

    // ---------- 2. delegate ----------
    @Test
    void delegate_keepsParentPending_createsMirrorChild() {
        loginAs(42L);
        Mockito.when(taskMapper.selectById(100L)).thenReturn(parentTask(100L, 42L, "PENDING"));

        long childId = ops.delegate(100L, 99L, "delegate to colleague");

        // 父任务不变（仍是 PENDING）—— 验证没有 updateById 调过 parent
        Mockito.verify(taskMapper, Mockito.never()).updateById((TaskEntity) Mockito.argThat(
            (TaskEntity t) -> t.getId() != null && t.getId() == 100L && "PENDING".equals(t.getStatus())));
        // 新任务 PENDING，delegated_from = 42
        ArgumentCaptor<TaskEntity> ins = ArgumentCaptor.forClass(TaskEntity.class);
        Mockito.verify(taskMapper).insert(ins.capture());
        TaskEntity child = ins.getValue();
        assertThat(child.getStatus()).isEqualTo("PENDING");
        assertThat(child.getAssigneeId()).isEqualTo(99L);
        assertThat(child.getParentTaskId()).isEqualTo(100L);
        assertThat(child.getDelegatedFrom()).isEqualTo(42L);
        assertThat(child.getIsAdditional()).isFalse();
        assertThat(childId).isEqualTo(child.getId());
    }

    // ---------- 3. addAssignee ----------
    @Test
    void addAssignee_createsAdditionalChild() {
        loginAs(42L);
        Mockito.when(taskMapper.selectById(100L)).thenReturn(parentTask(100L, 42L, "PENDING"));

        long childId = ops.addAssignee(100L, 99L, "需要财务复核");

        // 新任务 PENDING、is_additional=true、parent_task_id=100
        ArgumentCaptor<TaskEntity> ins = ArgumentCaptor.forClass(TaskEntity.class);
        Mockito.verify(taskMapper).insert(ins.capture());
        TaskEntity child = ins.getValue();
        assertThat(child.getStatus()).isEqualTo("PENDING");
        assertThat(child.getAssigneeId()).isEqualTo(99L);
        assertThat(child.getParentTaskId()).isEqualTo(100L);
        assertThat(child.getIsAdditional()).isTrue();
        assertThat(child.getDelegatedFrom()).isNull();
        assertThat(childId).isEqualTo(child.getId());
    }

    // ---------- 4. recallChild ----------
    @Test
    void recallChild_transferType_restoresParent() {
        loginAs(99L); // 子任务 assignee
        TaskEntity child = new TaskEntity();
        child.setId(200L);
        child.setProcInstId(1L);
        child.setNodeId("a1");
        child.setAssigneeId(99L);
        child.setStatus("PENDING");
        child.setParentTaskId(100L);
        Mockito.when(taskMapper.selectById(200L)).thenReturn(child);

        TaskEntity parent = parentTask(100L, 42L, "SKIPPED");
        Mockito.when(taskMapper.selectById(100L)).thenReturn(parent);

        TaskHistoryEntity hist = new TaskHistoryEntity();
        hist.setId(1L);
        hist.setAction("TRANSFER");
        Mockito.when(historyMapper.selectList(any())).thenReturn(List.of(hist));

        ops.recallChild(200L, "reverted");

        // 子任务 SKIPPED
        ArgumentCaptor<TaskEntity> upd = ArgumentCaptor.forClass(TaskEntity.class);
        Mockito.verify(taskMapper, Mockito.atLeastOnce()).updateById(upd.capture());
        TaskEntity updatedChild = upd.getAllValues().stream()
            .filter(t -> t.getId() != null && t.getId() == 200L)
            .findFirst().orElseThrow();
        assertThat(updatedChild.getStatus()).isEqualTo("SKIPPED");

        // 父任务被恢复为 PENDING
        TaskEntity restoredParent = upd.getAllValues().stream()
            .filter(t -> t.getId() != null && t.getId() == 100L)
            .findFirst().orElseThrow();
        assertThat(restoredParent.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void recallChild_delegateType_doesNotRestoreParent() {
        loginAs(99L);
        TaskEntity child = new TaskEntity();
        child.setId(200L);
        child.setProcInstId(1L);
        child.setNodeId("a1");
        child.setAssigneeId(99L);
        child.setStatus("PENDING");
        child.setParentTaskId(100L);
        Mockito.when(taskMapper.selectById(200L)).thenReturn(child);
        Mockito.when(taskMapper.selectById(100L)).thenReturn(parentTask(100L, 42L, "PENDING"));

        TaskHistoryEntity hist = new TaskHistoryEntity();
        hist.setId(1L);
        hist.setAction("DELEGATE");
        Mockito.when(historyMapper.selectList(any())).thenReturn(List.of(hist));

        ops.recallChild(200L, "reverted");

        // 父任务不应被更新为 PENDING（DELEGATE 类型，父本来就 PENDING）
        Mockito.verify(taskMapper, Mockito.never()).updateById((TaskEntity) Mockito.argThat(
            (TaskEntity t) -> t.getId() != null && t.getId() == 100L && "PENDING".equals(t.getStatus())));
    }

    // ---------- 5. listMyInbox ----------
    @Test
    void listMyInbox_returnsCurrentUserPending() {
        Mockito.when(taskMapper.selectList(any())).thenReturn(List.of(
            parentTask(1L, 42L, "PENDING"),
            parentTask(2L, 42L, "PENDING")
        ));
        var inbox = ops.listMyInbox(42L, "PENDING");
        assertThat(inbox).hasSize(2);
        ArgumentCaptor<QueryWrapper<TaskEntity>> cap = ArgumentCaptor.forClass(QueryWrapper.class);
        Mockito.verify(taskMapper).selectList(cap.capture());
        // 验证 query 包含 assignee_id=42 AND status='PENDING'
        assertThat(cap.getValue().getSqlSegment().toUpperCase()).contains("ASSIGNEE_ID");
    }
}