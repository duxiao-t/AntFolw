import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { request, useAccess } from '@umijs/max';
import { App, Button, Form, Input, Modal } from 'antd';
import { useState } from 'react';

export default function CompanySettingsPage() {
  const access = useAccess();
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<{ name: string }>();
  const { message } = App.useApp();
  const qc = useQueryClient();
  const companies = useQuery({
    queryKey: ['companies'],
    queryFn: () => request<AntFlowAPI.Company[]>('/api/companies'),
  });
  const create = useMutation({
    mutationFn: (name: string) => request('/api/companies', { method: 'POST', data: { name } }),
    onSuccess: async () => {
      await qc.invalidateQueries({ queryKey: ['companies'] });
      message.success('公司已创建');
      setOpen(false);
      form.resetFields();
    },
  });
  const canCreate = typeof access.can === 'function' && access.can('system:company:manage');

  return (
    <PageContainer>
      {companies.isError && <Button onClick={() => companies.refetch()}>公司加载失败，点击重试</Button>}
      <ProTable<AntFlowAPI.Company>
        rowKey="id"
        headerTitle="公司管理"
        loading={companies.isFetching}
        dataSource={companies.data ?? []}
        search={false}
        options={false}
        columns={[
          { title: 'ID', dataIndex: 'id', width: 100 },
          { title: '公司名称', dataIndex: 'name' },
          { title: '创建时间', dataIndex: 'createdAt', valueType: 'dateTime' },
        ]}
        toolBarRender={() => canCreate ? [
          <Button key="create" type="primary" onClick={() => setOpen(true)}>新建公司</Button>,
        ] : []}
      />
      <Modal title="新建公司" open={open} confirmLoading={create.isPending}
        onOk={() => form.submit()} onCancel={() => { setOpen(false); form.resetFields(); }} destroyOnHidden>
        <Form form={form} layout="vertical" preserve={false} onFinish={async ({ name }) => {
          try {
            await create.mutateAsync(name.trim());
          } catch {
            // 请求层显示错误；失败保留输入。
          }
        }}>
          <Form.Item name="name" label="公司名称" rules={[
              { required: true, whitespace: true, message: '请输入公司名称' },
              { validator: (_, value) => !value || [...value.trim()].length <= 128
                ? Promise.resolve() : Promise.reject(new Error('公司名称不能超过 128 个字符')) },
            ]}><Input /></Form.Item>
        </Form>
      </Modal>
    </PageContainer>
  );
}
