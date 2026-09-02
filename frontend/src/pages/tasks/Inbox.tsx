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
          { title: '节点', dataIndex: 'nodeId' },
          { title: '流程实例', dataIndex: 'procInstId' },
          { title: '创建', dataIndex: 'createdAt' },
          {
            title: '操作',
            render: (_, t: any) => (
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
            ),
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
