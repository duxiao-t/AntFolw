import { PageContainer, ProTable } from '@ant-design/pro-components';
import { Tag } from 'antd';
import { request, history } from '@umijs/max';

const STATUS_MAP: Record<string, { color: string; text: string }> = {
  RUNNING: { color: 'processing', text: '进行中' },
  APPROVED: { color: 'success', text: '已通过' },
  REJECTED: { color: 'error', text: '已驳回' },
  WITHDRAWN: { color: 'default', text: '已撤回' },
};

export default function RecordListPage() {
  return (
    <PageContainer>
      <ProTable
        rowKey="id"
        columns={[
          { title: '实例ID', dataIndex: 'id', key: 'id', width: 80 },
          { title: '状态', dataIndex: 'status', key: 'status', width: 100,
            render: (_, r: any) => {
              const m = STATUS_MAP[r.status] ?? { color: 'default', text: r.status };
              return <Tag color={m.color}>{m.text}</Tag>;
            } },
          { title: '发起人ID', dataIndex: 'startedBy', key: 'startedBy', width: 100 },
          { title: '发起时间', dataIndex: 'startedAt', key: 'startedAt', width: 180 },
          { title: '完成时间', dataIndex: 'finishedAt', key: 'finishedAt', width: 180 },
        ]}
        request={async (params: any) => {
          const list = await request('/api/instances', { params });
          return { data: list, success: true, total: list?.length ?? 0 };
        }}
        search={{ labelWidth: 'auto' }}
        onRow={(record: any) => ({
          onClick: () => history.push(`/proc/${record.id}`),
          style: { cursor: 'pointer' },
        })}
      />
    </PageContainer>
  );
}
