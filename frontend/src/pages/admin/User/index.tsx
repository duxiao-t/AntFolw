import { ProTable, ModalForm, ProFormText, ProFormSelect } from '@ant-design/pro-components';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { Button, message } from 'antd';

export default function UserPage() {
  const qc = useQueryClient();
  const users = useQuery({
    queryKey: ['users'],
    queryFn: () => request<any[]>('/api/users').then((r: any) => r ?? []),
  });
  const roles = useQuery({
    queryKey: ['roles'],
    queryFn: () => request<any[]>('/api/roles').then((r: any) => r ?? []),
  });
  const create = useMutation({
    mutationFn: (body: any) => request('/api/users', { method: 'POST', data: body }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['users'] }); message.success('已创建'); },
  });
  return (
    <ProTable
      rowKey="id"
      dataSource={users.data ?? []}
      search={false}
      columns={[
        { title: 'ID', dataIndex: 'id' },
        { title: '用户名', dataIndex: 'username' },
        { title: '显示名', dataIndex: 'displayName' },
        { title: '邮箱', dataIndex: 'email' },
        { title: '状态', dataIndex: 'status' },
      ]}
      toolBarRender={() => [
        <ModalForm
          key="new"
          title="新建用户"
          trigger={<Button type="primary">新建用户</Button>}
          onFinish={async (vals: any) => {
            await create.mutateAsync({
              username: vals.username,
              displayName: vals.displayName,
              email: vals.email,
              deptId: vals.deptId,
              roleIds: vals.roleIds,
            });
            return true;
          }}
        >
          <ProFormText name="username" label="用户名" rules={[{ required: true }]} />
          <ProFormText name="displayName" label="显示名" rules={[{ required: true }]} />
          <ProFormText name="email" label="邮箱" />
          <ProFormSelect name="roleIds" label="角色" mode="multiple"
            options={(roles.data ?? []).map((r: any) => ({ value: r.id, label: r.name }))} />
          <p style={{ color: '#888' }}>注：默认密码为 ant.design，仅用于 MVP 演示。</p>
        </ModalForm>,
      ]}
    />
  );
}
