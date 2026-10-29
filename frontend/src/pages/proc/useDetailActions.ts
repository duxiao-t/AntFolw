import { App } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { useState } from 'react';
import { fetchApprovalCommentPresets } from '../../components/ApprovalCommentEditor';
import type { FieldMode, SchemaNode } from '../../registry/types';
import { pickEditableValues } from './fieldPermissions';

/**
 * 流程操作层：同意 / 驳回 / 撤回 / 自动化重试 / 紧急介入，以及承载这些动作的弹窗状态。
 * 弹窗开关放在这里而不是弹窗组件里：头部按钮只负责"打开"，两边共用一个真相。
 */
export function useDetailActions({
  id,
  tasks,
  myPending,
  pendingTask,
  isStarter,
  hasEditableFields,
  editableValues,
  currentFormModes,
  formSchema,
}: {
  id?: string;
  tasks: any[];
  myPending?: any;
  pendingTask?: any;
  isStarter: boolean;
  hasEditableFields: boolean;
  editableValues: Record<string, any>;
  currentFormModes: Record<string, FieldMode>;
  formSchema: SchemaNode[];
}) {
  const { message, modal } = App.useApp();
  const qc = useQueryClient();

  const [rejectFor, setRejectFor] = useState<
    { taskId: number; targetNodeId: string | null } | null
  >(null);
  const [rejectComment, setRejectComment] = useState('');
  const [approveOpen, setApproveOpen] = useState(false);
  const [approveComment, setApproveComment] = useState('');
  const [withdrawOpen, setWithdrawOpen] = useState(false);
  const [overrideOpen, setOverrideOpen] = useState(false);
  const [overrideAction, setOverrideAction] = useState<'APPROVE' | 'REJECT'>('APPROVE');
  const [overrideTicket, setOverrideTicket] = useState('');
  const [overrideReason, setOverrideReason] = useState('');
  const [overrideTarget, setOverrideTarget] = useState<string | undefined>();

  const presetTaskId = approveOpen ? myPending?.id : rejectFor?.taskId;
  const presetsQuery = useQuery({
    queryKey: ['task-comment-presets', presetTaskId],
    queryFn: () => {
      if (presetTaskId == null) throw new Error('请选择审批任务');
      return fetchApprovalCommentPresets(presetTaskId);
    },
    enabled: presetTaskId != null,
    retry: 0,
  });

  const rejectTask = rejectFor
    ? tasks.find((task: any) => task.id === rejectFor.taskId)
    : undefined;
  const fixedRejectTarget = ['AND', 'ALL', 'RATIO'].includes(rejectTask?.approvalMode);

  function openApprove() {
    setApproveComment('');
    setApproveOpen(true);
  }

  function openReject(taskId: number) {
    setRejectFor({ taskId, targetNodeId: null });
  }

  function openWithdraw() {
    setWithdrawOpen(true);
  }

  function openOverride() {
    setOverrideOpen(true);
  }

  async function doApprove(taskId: number) {
    try {
      await request(`/api/tasks/${taskId}/approve`, {
        method: 'POST',
        data: {
          comment: approveComment.trim() || undefined,
          ...(hasEditableFields
            ? {
                data: pickEditableValues(
                  editableValues,
                  currentFormModes,
                  formSchema,
                ),
              }
            : {}),
        },
      });
      message.success('已同意');
      setApproveOpen(false);
      setApproveComment('');
      qc.invalidateQueries({ queryKey: ['instance', id] });
    } catch (e: any) {
      message.error(e?.message ?? '操作失败');
    }
  }

  async function doReject() {
    if (!rejectFor) return;
    const comment = rejectComment.trim();
    if (!comment) {
      message.error('请填写驳回原因');
      return;
    }
    try {
      await request(`/api/tasks/${rejectFor.taskId}/reject`, {
        method: 'POST',
        data: {
          comment,
          ...(!fixedRejectTarget && rejectFor.targetNodeId
            ? { rejectToNodeId: rejectFor.targetNodeId }
            : {}),
        },
      });
      message.success('已提交驳回意见');
      setRejectFor(null);
      setRejectComment('');
      qc.invalidateQueries({ queryKey: ['instance', id] });
    } catch (e: any) {
      message.error(e?.message ?? '操作失败');
    }
  }

  async function doWithdraw() {
    try {
      await request(`/api/instances/${id}/withdraw`, { method: 'POST' });
      message.success('已撤回，流程已进入待修改');
      setWithdrawOpen(false);
      qc.invalidateQueries({ queryKey: ['instance', id] });
    } catch (e: any) {
      message.error(e?.message ?? '撤回失败');
    }
  }

  async function doDiscard() {
    try {
      await request(`/api/instances/${id}/discard`, { method: 'POST' });
      message.success('申请已作废');
      qc.invalidateQueries({ queryKey: ['instance', id] });
      qc.invalidateQueries({ queryKey: ['tasks'] });
    } catch (e: any) {
      message.error(e?.message ?? '作废失败');
    }
  }

  function openDiscard() {
    modal.confirm({
      title: '作废该申请？',
      content: '作废后该申请将从你的列表与待办中移除，且不可恢复。',
      okText: '确认作废',
      okButtonProps: { danger: true },
      onOk: doDiscard,
    });
  }

  async function retryAutomationJob(jobId: number) {
    try {
      await request(`/api/instances/${id}/jobs/${jobId}/retry`, {
        method: 'POST',
      });
      message.success('自动化作业已重新入队');
      qc.invalidateQueries({ queryKey: ['instance', id] });
    } catch (error: any) {
      message.error(error?.message ?? '重试失败');
    }
  }

  async function submitOverride() {
    if (!pendingTask || !overrideTicket.trim() || !overrideReason.trim()) {
      message.error('请填写工单号和介入原因');
      return;
    }
    const execute = async () => {
      await request(`/api/tasks/${pendingTask.id}/override`, {
        method: 'POST',
        data: {
          action: overrideAction,
          ticketNo: overrideTicket.trim(),
          reason: overrideReason.trim(),
          rejectToNodeId: overrideAction === 'REJECT' ? overrideTarget : undefined,
        },
      });
      message.success(overrideAction === 'APPROVE' ? '已紧急同意' : '已紧急驳回');
      setOverrideOpen(false);
      setOverrideTicket('');
      setOverrideReason('');
      setOverrideTarget(undefined);
      qc.invalidateQueries({ queryKey: ['instance', id] });
    };
    if (isStarter) {
      modal.confirm({
        title: '介入本人发起的流程？',
        content: '该操作会记录为关键风险审计事件。',
        okText: '确认介入',
        okButtonProps: { danger: true },
        onOk: execute,
      });
      return;
    }
    await execute();
  }

  return {
    approveOpen,
    setApproveOpen,
    approveComment,
    setApproveComment,
    rejectFor,
    setRejectFor,
    rejectComment,
    setRejectComment,
    withdrawOpen,
    setWithdrawOpen,
    overrideOpen,
    setOverrideOpen,
    overrideAction,
    setOverrideAction,
    overrideTicket,
    setOverrideTicket,
    overrideReason,
    setOverrideReason,
    overrideTarget,
    setOverrideTarget,
    presets: presetsQuery.data,
    fixedRejectTarget,
    openApprove,
    openReject,
    openWithdraw,
    openDiscard,
    openOverride,
    doApprove,
    doReject,
    doWithdraw,
    doDiscard,
    retryAutomationJob,
    submitOverride,
  };
}

export type DetailActions = ReturnType<typeof useDetailActions>;
