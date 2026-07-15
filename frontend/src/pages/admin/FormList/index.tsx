import { ProTable } from '@ant-design/pro-components';
import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate } from '@umijs/max';
import { request } from '@umijs/max';
import { Button, Space } from 'antd';

export default function FormListPage() {
  const navigate = useNavigate();
  const { data } = useQuery({
    queryKey: ['forms'],
    queryFn: () => request<any[]>('/api/forms/definitions').then((r: any) => r ?? []),
  });
  return (
    <ProTable
      rowKey="id"
      dataSource={data ?? []}
      search={false}
      columns={[
        { title: 'ID', dataIndex: 'id' },
        { title: 'code', dataIndex: 'code' },
        { title: '名称', dataIndex: 'name' },
        { title: '版本', dataIndex: 'version' },
        { title: '状态', dataIndex: 'status',
          valueEnum: { DRAFT: { text: '草稿', status: 'Default' }, PUBLISHED: { text: '已发布', status: 'Success' } } },
        { title: '创建', dataIndex: 'createdAt' },
        { title: '操作', render: (_, fd: any) => (
          <Space>
            <Button size="small" onClick={() => navigate(`/designer/form/${fd.id}`)}>编辑</Button>
            <Button size="small" onClick={() => navigate(`/designer/process/${fd.id}`)}>流程</Button>
          </Space>
        ) },
      ]}
      toolBarRender={() => [
        <Link to="/designer/form/new" key="new">
          <Button type="primary">新建表单</Button>
        </Link>,
      ]}
    />
  );
}
