import { ProTable, ModalForm, ProFormText } from '@ant-design/pro-components';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { Button, message } from 'antd';

export default function CompanyPage() {
  const qc = useQueryClient();
  const { data } = useQuery({
    queryKey: ['companies'],
    queryFn: () => request<any[]>('/api/companies').then((r: any) => r ?? []),
  });
  const create = useMutation({
    mutationFn: (body: any) => request('/api/companies', { method: 'POST', data: body }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['companies'] }); message.success('已创建'); },
  });
  return (
    <ProTable
      rowKey="id"
      dataSource={data ?? []}
      search={false}
      columns={[
        { title: 'ID', dataIndex: 'id' },
        { title: '公司名', dataIndex: 'name' },
        { title: '创建时间', dataIndex: 'createdAt' },
      ]}
      toolBarRender={() => [
        <ModalForm
          key="new"
          title="新建公司"
          trigger={<Button type="primary">新建公司</Button>}
          onFinish={async (vals: any) => { await create.mutateAsync(vals); return true; }}
        >
          <ProFormText name="name" label="公司名" rules={[{ required: true }]} />
        </ModalForm>,
      ]}
    />
  );
}
