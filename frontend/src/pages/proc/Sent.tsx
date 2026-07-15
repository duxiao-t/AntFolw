import { ProTable } from '@ant-design/pro-components';
import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate } from '@umijs/max';
import { request } from '@umijs/max';
import { Button } from 'antd';

export default function SentPage() {
  const navigate = useNavigate();
  const { data } = useQuery({
    queryKey: ['instances-mine'],
    queryFn: () => request<any[]>('/api/instances').then((r: any) => r ?? []),
  });
  return (
    <ProTable
      rowKey="id"
      dataSource={data ?? []}
      search={false}
      columns={[
        { title: 'ID', dataIndex: 'id' },
        { title: '流程定义', dataIndex: 'procDefId' },
        { title: '状态', dataIndex: 'status',
          valueEnum: {
            RUNNING: { text: '审批中', status: 'Processing' },
            APPROVED: { text: '已通过', status: 'Success' },
            REJECTED: { text: '已驳回', status: 'Error' },
            WITHDRAWN: { text: '已撤回', status: 'Default' },
          } },
        { title: '当前节点', dataIndex: 'currentNodeId' },
        { title: '发起时间', dataIndex: 'startedAt' },
        { title: '完成时间', dataIndex: 'finishedAt' },
        { title: '操作', render: (_, i: any) => (
          <Button size="small" onClick={() => navigate('/proc/' + i.id)}>查看</Button>
        ) },
      ]}
      toolBarRender={() => [
        <Link to="/runtime/list" key="list">
          <Button>查看已提交表单</Button>
        </Link>,
      ]}
    />
  );
}
