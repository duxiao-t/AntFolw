import { ProTable } from '@ant-design/pro-components';
import type { ActionType } from '@ant-design/pro-components';
import { Button, Modal, message } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useNavigate } from '@umijs/max';
import { request } from '@umijs/max';
import {
  ApprovalCommentEditor,
  fetchApprovalCommentPresets,
} from '../../components/ApprovalCommentEditor';
import { TASKS_CHANGED_EVENT } from '../../components/WorkflowEventsSubscriber';

export default function Inbox() {
  const navigate = useNavigate();
  const actionRef = useRef<ActionType | undefined>(undefined);
  const [pending, setPending] = useState<{ id: number; action: 'approve' | 'reject' } | null>(null);
  const [comment, setComment] = useState('');
  useEffect(() => {
    const reload = () => actionRef.current?.reload();
    window.addEventListener(TASKS_CHANGED_EVENT, reload);
    return () => window.removeEventListener(TASKS_CHANGED_EVENT, reload);
  }, []);
  const presetsQuery = useQuery({
    queryKey: ['task-comment-presets', pending?.id],
    queryFn: () => {
      if (!pending) throw new Error('请选择审批操作');
      return fetchApprovalCommentPresets(pending.id);
    },
    enabled: pending != null,
    retry: 0,
  });
  const act = useMutation({
    mutationFn: () => {
      if (!pending) {
        throw new Error('No pending task action selected');
      }
      return request(`/api/tasks/${pending.id}/${pending.action}`, {
        method: 'POST',
        data: { comment: comment.trim() || undefined },
      });
    },
    onSuccess: () => {
      actionRef.current?.reload();
      message.success('已完成');
      setPending(null);
      setComment('');
    },
  });

  const open = (id: number, action: 'approve' | 'reject') => {
    setPending({ id, action });
    setComment('');
  };

  const discard = (procInstId: number) => {
    Modal.confirm({
      title: '作废该申请？',
      content: '作废后该申请将从你的列表与待办中移除，且不可恢复。',
      okText: '确认作废',
      okButtonProps: { danger: true },
      onOk: async () => {
        await request(`/api/instances/${procInstId}/discard`, { method: 'POST' });
        message.success('申请已作废');
        actionRef.current?.reload();
      },
    });
  };

  return (
    <>
      <ProTable
        actionRef={actionRef}
        rowKey="id"
        request={async (params) => {
          const result = await request<WorkflowPage<any>>('/api/tasks', {
            params: {
              view: 'pending',
              page: params.current,
              size: params.pageSize,
            },
          });
          return { data: result.records, total: result.total, success: true };
        }}
        pagination={{ defaultPageSize: 20 }}
        search={false}
        columns={[
          { title: 'ID', dataIndex: 'id' },
          // nodeId 是内部标识：后端已按实例快照补了 nodeName，优先用它。
          { title: '节点', dataIndex: 'nodeName', render: (_: unknown, row: any) => row.nodeName ?? row.nodeId },
          { title: '流程实例', dataIndex: 'procInstId' },
          { title: '创建', dataIndex: 'createdAt' },
          {
            title: '操作',
            render: (_, t: any) => {
              // 「待修改」待办（REWORK）没有可审批的决策，只能查看原流程或作废；
              // 若照旧渲染同意/驳回，点下去会打到一条 REWORK 任务上并报错。
              if (t.taskType === 'REWORK') {
                return (
                  <>
                    <Button size="small" onClick={() => navigate(`/proc/${t.procInstId}`)}>
                      查看流程
                    </Button>{' '}
                    <Button size="small" danger onClick={() => discard(t.procInstId)}>
                      作废
                    </Button>
                  </>
                );
              }
              return (
                <>
                  <Button size="small" type="primary" onClick={() => open(t.id, 'approve')}>
                    同意
                  </Button>{' '}
                  <Button
                    size="small"
                    danger
                    disabled={!!t.parallelId}
                    title={t.parallelId ? '并行审批节点不允许驳回' : undefined}
                    onClick={() => open(t.id, 'reject')}
                  >
                    驳回
                  </Button>{' '}
                  <Button
                    size="small"
                    onClick={() => navigate(`/proc/${t.procInstId}`)}
                  >
                    查看流程
                  </Button>
                </>
              );
            },
          },
        ]}
      />
      <Modal
        open={!!pending}
        title={pending?.action === 'approve' ? '审批意见（可选）' : '驳回原因（必填）'}
        okText="确定"
        cancelText="取消"
        confirmLoading={act.isPending}
        onCancel={() => setPending(null)}
        onOk={() => {
          if (pending?.action === 'reject' && !comment.trim()) {
            message.error('请填写驳回原因');
            return Promise.resolve();
          }
          return act.mutateAsync();
        }}
      >
        <ApprovalCommentEditor
          action={pending?.action ?? 'approve'}
          presets={presetsQuery.data}
          rows={3}
          value={comment}
          onChange={setComment}
        />
      </Modal>
    </>
  );
}

type WorkflowPage<T> = { records: T[]; total: number; page: number; size: number };
