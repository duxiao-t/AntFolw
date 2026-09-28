import { ProTable } from '@ant-design/pro-components';
import { Link } from '@umijs/max';
import { request } from '@umijs/max';

export default function DonePage() {
  return (
    <ProTable
      rowKey="id"
      request={async (params) => {
        const result = await request<WorkflowPage<any>>('/api/tasks', {
          params: { view: 'done', page: params.current, size: params.pageSize },
        });
        return { data: result.records, total: result.total, success: true };
      }}
      pagination={{ defaultPageSize: 20 }}
      search={false}
      columns={[
        { title: 'ID', dataIndex: 'id' },
        // nodeId 是内部标识：后端已按实例快照补了 nodeName，优先用它。
        { title: '节点', dataIndex: 'nodeName', render: (_: unknown, row: any) => row.nodeName ?? row.nodeId },
        { title: '流程', dataIndex: 'procInstId' },
        { title: '状态', dataIndex: 'status',
          valueEnum: {
            APPROVED: { text: '同意' },
            REJECTED: { text: '驳回' },
            RESUBMITTED: { text: '已重提' },
          } },
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

type WorkflowPage<T> = { records: T[]; total: number; page: number; size: number };
