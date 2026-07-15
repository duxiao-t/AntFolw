import { ProTable } from '@ant-design/pro-components';
import { Button, Modal, Input, message } from 'antd';
import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from '@umijs/max';
import { request } from '@umijs/max';

export default function Inbox() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const { data } = useQuery({
    queryKey: ['inbox'],
    queryFn: () => request<any[]>('/api/tasks?status=PENDING').then((r: any) => r ?? []),
  });
  const [pending, setPending] = useState<{ id: number; action: 'approve' | 'reject' } | null>(null);
  const [comment, setComment] = useState('');
  const act = useMutation({
    mutationFn: () =>
      request(`/api/tasks/${pending!.id}/${pending!.action}`, {
        method: 'POST',
        data: { comment },
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['inbox'] });
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
        rowKey="id"
        dataSource={data ?? []}
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
                <Button size="small" danger onClick={() => open(t.id, 'reject')}>
                  驳回
                </Button>{' '}
                <Button
                  size="small"
                  onClick={() => navigate('/proc/' + t.procInstId)}
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
        <Input.TextArea
          rows={3}
          value={comment}
          onChange={(e) => setComment(e.target.value)}
          placeholder={pending?.action === 'approve' ? '可填意见' : '请说明驳回原因'}
        />
      </Modal>
    </>
  );
}
