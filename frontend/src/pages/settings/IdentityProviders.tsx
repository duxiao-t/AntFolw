import { KeyOutlined, PlusOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import type { ProColumns } from '@ant-design/pro-components';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { App, Button, Drawer, Form, Input, Select, Space, Switch, Tag, Typography } from 'antd';
import { useEffect, useState } from 'react';

type Provider = {
  id: number;
  code: string;
  displayName: string;
  issuerUri: string;
  clientId: string;
  clientAuthMethod: 'BASIC' | 'POST';
  scopes: string;
  matchClaim: string;
  matchField: 'username' | 'email' | 'employeeNo';
  enabled: boolean;
  secretConfigured: boolean;
};
type ProviderForm = Omit<Provider, 'id' | 'secretConfigured'> & { clientSecret?: string };
type Binding = { id: number; subject: string; userId: number; username: string; displayName: string; lastLoginAt: string };

export default function IdentityProvidersPage() {
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<ProviderForm>();
  const [editing, setEditing] = useState<Provider>();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [bindingProvider, setBindingProvider] = useState<Provider>();
  const providers = useQuery({
    queryKey: ['identity-providers'],
    queryFn: () => request<Provider[]>('/api/security/identity-providers'),
  });
  const bindings = useQuery({
    queryKey: ['identity-provider-bindings', bindingProvider?.id],
    queryFn: () => request<Binding[]>(`/api/security/identity-providers/${bindingProvider?.id}/bindings`),
    enabled: bindingProvider !== undefined,
  });

  useEffect(() => {
    if (!drawerOpen) return;
    form.setFieldsValue(editing ? { ...editing, clientSecret: undefined } : {
      code: '', displayName: '', issuerUri: '', clientId: '', clientSecret: '',
      clientAuthMethod: 'BASIC', scopes: 'openid profile email',
      matchClaim: 'preferred_username', matchField: 'username', enabled: true,
    });
  }, [drawerOpen, editing, form]);

  const save = useMutation({
    mutationFn: (values: ProviderForm) => request<Provider>(editing
      ? `/api/security/identity-providers/${editing.id}` : '/api/security/identity-providers', {
      method: editing ? 'PUT' : 'POST', data: values,
    }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['identity-providers'] });
      setDrawerOpen(false);
      message.success(editing ? '身份提供方已更新' : '身份提供方已创建');
    },
  });
  const remove = useMutation({
    mutationFn: (id: number) => request(`/api/security/identity-providers/${id}`, { method: 'DELETE' }),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['identity-providers'] }),
  });
  const unbind = useMutation({
    mutationFn: ({ providerId, bindingId }: { providerId: number; bindingId: number }) =>
      request(`/api/security/identity-providers/${providerId}/bindings/${bindingId}`, { method: 'DELETE' }),
    onSuccess: () => void bindings.refetch(),
  });

  const columns: ProColumns<Provider>[] = [
    { title: '提供方', dataIndex: 'displayName', render: (_, row) => <Space orientation="vertical" size={0}>
      <Typography.Text strong>{row.displayName}</Typography.Text>
      <Typography.Text type="secondary">{row.code}</Typography.Text>
    </Space> },
    { title: 'Issuer', dataIndex: 'issuerUri', ellipsis: true },
    { title: '匹配本地用户', dataIndex: 'matchField', renderText: (value, row) => `${value} ← ${row.matchClaim}` },
    { title: '状态', dataIndex: 'enabled', render: (_, row) => <Tag color={row.enabled ? 'success' : 'default'}>{row.enabled ? '已启用' : '已停用'}</Tag> },
    { title: '操作', valueType: 'option', render: (_, row) => [
      <a key="edit" onClick={() => { setEditing(row); setDrawerOpen(true); }}>编辑</a>,
      <a key="bindings" onClick={() => setBindingProvider(row)}>绑定</a>,
      <a key="remove" onClick={() => modal.confirm({ title: `删除 ${row.displayName}？`,
        content: '删除后该提供方的身份绑定一并移除，用户仍可使用本地账号登录。', okText: '删除',
        okButtonProps: { danger: true }, onOk: () => remove.mutateAsync(row.id) })}>删除</a>,
    ] },
  ];

  return (
    <PageContainer title="身份提供方" subTitle="连接标准 OIDC，并将外部身份绑定到已有本地用户">
      <ProTable<Provider>
        rowKey="id" columns={columns} dataSource={providers.data ?? []} loading={providers.isLoading}
        search={false} pagination={false}
        toolBarRender={() => [<Button key="create" type="primary" icon={<PlusOutlined />}
          onClick={() => { setEditing(undefined); setDrawerOpen(true); }}>添加 OIDC 提供方</Button>]}
      />
      <Drawer title={editing ? `编辑 ${editing.displayName}` : '添加 OIDC 提供方'} width={560}
        open={drawerOpen} destroyOnHidden onClose={() => setDrawerOpen(false)}
        footer={<Space><Button onClick={() => setDrawerOpen(false)}>取消</Button><Button type="primary"
          loading={save.isPending} onClick={() => form.submit()}>保存并验证连接</Button></Space>}>
        <Form form={form} layout="vertical" requiredMark="optional" onFinish={(values) => save.mutate(values)}>
          <Form.Item name="displayName" label="显示名称" rules={[{ required: true, whitespace: true }]}><Input /></Form.Item>
          <Form.Item name="code" label="提供方代码" rules={[{ required: true, pattern: /^[a-z0-9][a-z0-9_-]{1,63}$/ }]}><Input disabled={editing !== undefined} /></Form.Item>
          <Form.Item name="issuerUri" label="Issuer 地址" extra="生产环境必须使用 HTTPS，主机需加入 ANTFLOW_OIDC_ALLOWED_HOSTS"
            rules={[{ required: true, type: 'url' }]}><Input prefix={<SafetyCertificateOutlined />} placeholder="https://id.example.com" /></Form.Item>
          <Form.Item name="clientId" label="Client ID" rules={[{ required: true, whitespace: true }]}><Input /></Form.Item>
          <Form.Item name="clientSecret" label="Client Secret" rules={editing ? [] : [{ required: true }]}
            extra={editing?.secretConfigured ? '已安全配置，留空表示不修改' : '保存后不会回显'}>
            <Input.Password prefix={<KeyOutlined />} autoComplete="new-password" />
          </Form.Item>
          <Form.Item name="clientAuthMethod" label="Token 端点认证方式"><Select options={[
            { value: 'BASIC', label: 'client_secret_basic' }, { value: 'POST', label: 'client_secret_post' },
          ]} /></Form.Item>
          <Form.Item name="scopes" label="Scopes"><Input /></Form.Item>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 16 }}>
            <Form.Item name="matchClaim" label="外部 Claim" rules={[{ required: true }]}><Input /></Form.Item>
            <Form.Item name="matchField" label="本地字段"><Select options={[
              { value: 'username', label: '用户名' }, { value: 'email', label: '邮箱（要求已验证）' },
              { value: 'employeeNo', label: '员工编号' },
            ]} /></Form.Item>
          </div>
          <Form.Item name="enabled" label="允许登录" valuePropName="checked"><Switch /></Form.Item>
        </Form>
      </Drawer>
      <Drawer title={`${bindingProvider?.displayName ?? ''} · 身份绑定`} width={620}
        open={bindingProvider !== undefined} onClose={() => setBindingProvider(undefined)}>
        <ProTable<Binding> rowKey="id" search={false} pagination={false} loading={bindings.isLoading}
          dataSource={bindings.data ?? []} columns={[
            { title: '本地用户', render: (_, row) => `${row.displayName}（${row.username}）` },
            { title: '外部 Subject', dataIndex: 'subject', ellipsis: true },
            { title: '操作', valueType: 'option', render: (_, row) => <a onClick={() => {
              if (bindingProvider) unbind.mutate({ providerId: bindingProvider.id, bindingId: row.id });
            }}>解绑</a> },
          ]} />
      </Drawer>
    </PageContainer>
  );
}
