import { ProTable, ModalForm, ProFormText, ProFormSelect } from '@ant-design/pro-components';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { Button, message } from 'antd';

export default function DepartmentPage() {
  const qc = useQueryClient();
  const cos = useQuery({
    queryKey: ['companies'],
    queryFn: () => request<any[]>('/api/companies').then((r: any) => r ?? []),
  });
  const tree = useQuery({
    queryKey: ['departments-tree'],
    queryFn: () => request<any[]>('/api/departments', { params: { companyId: 1 } }).then((r: any) => r ?? []),
  });
  const create = useMutation({
    mutationFn: (body: any) => request('/api/departments', { method: 'POST', data: body }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['departments-tree'] }); message.success('已创建'); },
  });
  return (
    <ProTable
      rowKey="id"
      dataSource={tree.data ?? []}
      search={false}
      columns={[
        { title: 'ID', dataIndex: 'id' },
        { title: '部门名', dataIndex: 'name' },
        { title: '层级路径 (ltree)', dataIndex: 'path' },
        { title: 'Leader', dataIndex: 'leaderId' },
      ]}
      toolBarRender={() => [
        <ModalForm
          key="new"
          title="新建部门"
          trigger={<Button type="primary">新建部门</Button>}
          onFinish={async (vals: any) => {
            await create.mutateAsync({ ...vals, parentId: vals.parentId || null });
            return true;
          }}
        >
          <ProFormSelect name="companyId" label="公司" required
            options={(cos.data ?? []).map((c: any) => ({ value: c.id, label: c.name }))} />
          <ProFormSelect name="parentId" label="父部门（顶层时不选）"
            options={(tree.data ?? []).map((d: any) => ({ value: d.id, label: d.name }))} />
          <ProFormText name="name" label="部门名" rules={[{ required: true }]} />
        </ModalForm>,
      ]}
    />
  );
}
