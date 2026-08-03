import { ProTable } from '@ant-design/pro-components';
import { useQuery } from '@tanstack/react-query';
import { Link } from '@umijs/max';
import { request } from '@umijs/max';

export default function DonePage() {
  const { data } = useQuery({
    queryKey: ['tasks-done'],
    queryFn: () =>
      Promise.all([
        request<any[]>('/api/tasks?status=APPROVED').then((r: any) => r ?? []),
        request<any[]>('/api/tasks?status=REJECTED').then((r: any) => r ?? []),
        request<any[]>('/api/tasks?status=SKIPPED').then((r: any) => r ?? []),
      ]).then(([a, b, c]) => [...a, ...b, ...c]),
  });
  return (
    <ProTable
      rowKey="id"
      dataSource={data ?? []}
      search={false}
      columns={[
        { title: 'ID', dataIndex: 'id' },
        { title: '节点', dataIndex: 'nodeId' },
        { title: '流程', dataIndex: 'procInstId' },
        { title: '状态', dataIndex: 'status',
          valueEnum: { APPROVED: { text: '同意' }, REJECTED: { text: '驳回' }, SKIPPED: { text: '跳过' } } },
        { title: '审批时间', dataIndex: 'approvedAt' },
        { title: '意见', dataIndex: 'comment' },
      ]}
      expandable={{
        expandedRowRender: (rec: any) => (
          <Link to={`/proc/${rec.procInstId}`}>查看流程详情</Link>
        ),
      }}
    />
  );
}
