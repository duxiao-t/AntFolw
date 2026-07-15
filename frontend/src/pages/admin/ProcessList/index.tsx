import { ProTable } from '@ant-design/pro-components';
import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate } from '@umijs/max';
import { request } from '@umijs/max';
import { Button, Space } from 'antd';

export default function ProcessListPage() {
  const navigate = useNavigate();
  const { data } = useQuery({
    queryKey: ['processes'],
    queryFn: () => request<any[]>('/api/processes/definitions').then((r: any) => r ?? []),
  });
  return (
    <ProTable
      rowKey="id"
      dataSource={data ?? []}
      search={false}
      columns={[
        { title: 'ID', dataIndex: 'id' },
        { title: 'form_def_id', dataIndex: 'formDefId' },
        { title: '版本', dataIndex: 'version' },
        { title: '状态', dataIndex: 'status',
          valueEnum: { DRAFT: { text: '草稿', status: 'Default' }, PUBLISHED: { text: '已发布', status: 'Success' } } },
        { title: '创建', dataIndex: 'createdAt' },
        { title: '操作', render: (_, pd: any) => (
          <Space>
            <Button size="small" onClick={() => navigate(`/designer/process/${pd.formDefId}`)}>编辑流程</Button>
          </Space>
        ) },
      ]}
      toolBarRender={() => [
        <Link to="/admin/forms" key="forms">
          <Button>先选择表单</Button>
        </Link>,
      ]}
    />
  );
}
