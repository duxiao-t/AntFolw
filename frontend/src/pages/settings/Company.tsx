import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { request, useAccess } from '@umijs/max';
import { App, Button, Descriptions, Form, Input, Modal, Popconfirm, Skeleton, Space } from 'antd';
import { useState } from 'react';

type Company = AntFlowAPI.Company;
/** 新建与编辑共用一个弹窗：只差标题与请求方法。 */
type EditorState = { mode: 'create' } | { mode: 'edit'; company: Company } | null;

/**
 * 名称规则与后端 `CompanyController.validatedName` 对齐（trim 后按**码点**计数 ≤128）。
 * 前端先拦一道，是为了让"只多打了空格""超长输入"这类可预判的错误不必白跑一次请求。
 */
const NAME_RULES = [
  { required: true, whitespace: true, message: '请输入公司名称' },
  {
    validator: (_: unknown, value?: string) =>
      !value || [...value.trim()].length <= 128
        ? Promise.resolve()
        : Promise.reject(new Error('公司名称不能超过 128 个字符')),
  },
];

export default function CompanySettingsPage() {
  const access = useAccess();
  const { message } = App.useApp();
  const qc = useQueryClient();
  const [editor, setEditor] = useState<EditorState>(null);
  const [detailId, setDetailId] = useState<number | null>(null);
  const [form] = Form.useForm<{ name: string }>();

  const companies = useQuery({
    queryKey: ['companies'],
    queryFn: () => request<Company[]>('/api/companies'),
  });

  // 详情单独取一次：列表可能已被别的管理员改过，弹窗显示的是当前值。
  // retry: 0 —— 失败就展示错误态，不在这里自动重试，避免弹窗里长时间空白。
  const detail = useQuery({
    queryKey: ['company', detailId],
    queryFn: () => request<Company>(`/api/companies/${detailId}`),
    enabled: detailId != null,
    retry: 0,
  });

  // 新建与编辑共用一次提交：由 editor.mode 决定 PUT（改名）还是 POST（新建），成功后刷新列表并关窗。
  // 名称在提交前 trim —— 后端也会 trim，但前端先做能让结果与用户所见一致（成功提示里不会再出现多余空格）。
  const save = useMutation({
    mutationFn: async ({ name }: { name: string }) => {
      const body = { name: name.trim() };
      return editor?.mode === 'edit'
        ? request(`/api/companies/${editor.company.id}`, { method: 'PUT', data: body })
        : request('/api/companies', { method: 'POST', data: body });
    },
    onSuccess: async () => {
      await qc.invalidateQueries({ queryKey: ['companies'] });
      message.success(editor?.mode === 'edit' ? '公司已更新' : '公司已创建');
      setEditor(null);
      form.resetFields();
    },
    // 失败由请求层提示；保留输入，用户可直接改后重试。
  });

  // 停用 = 软删除。后端可能拒绝（最后一家公司 / 仍有部门），拒绝原因由请求层统一弹提示，
  // 所以这里不做本地预判、失败也不清状态——用户能直接看到"为什么停不掉"并去处理关联数据。
  const deactivate = useMutation({
    mutationFn: (id: number) => request(`/api/companies/${id}`, { method: 'DELETE' }),
    onSuccess: async () => {
      await qc.invalidateQueries({ queryKey: ['companies'] });
      message.success('公司已停用');
    },
  });

  const canManage = typeof access.can === 'function' && access.can('system:company:manage');

  return (
    <PageContainer>
      {companies.isError && <Button onClick={() => companies.refetch()}>公司加载失败，点击重试</Button>}
      <ProTable<Company>
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
          ...(canManage
            ? [{
                title: '操作',
                dataIndex: 'actions',
                valueType: 'option' as const,
                width: 180,
                render: (_: unknown, row: Company) => {
                  // 生成的契约类型里 id 可选；没有 id 的行做不了任何操作，直接不渲染按钮。
                  const id = row.id;
                  if (id == null) return null;
                  return (
                    <Space size="small">
                      <Button type="link" size="small" onClick={() => setDetailId(id)}>查看</Button>
                      <Button
                        type="link"
                        size="small"
                        onClick={() => {
                          setEditor({ mode: 'edit', company: row });
                          form.setFieldsValue({ name: row.name ?? '' });
                        }}
                      >
                        编辑
                      </Button>
                      <Popconfirm
                        title="停用这家公司？"
                        description="停用后不再出现在列表与选择器中，历史数据保留；公司下仍有部门时无法停用。"
                        okText="停用"
                        cancelText="取消"
                        onConfirm={() => deactivate.mutate(id)}
                      >
                        <Button type="link" size="small" danger>停用</Button>
                      </Popconfirm>
                    </Space>
                  );
                },
              }]
            : []),
        ]}
        toolBarRender={() => canManage ? [
          <Button key="create" type="primary" onClick={() => setEditor({ mode: 'create' })}>新建公司</Button>,
        ] : []}
      />

      <Modal
        title={editor?.mode === 'edit' ? '编辑公司' : '新建公司'}
        open={editor != null}
        confirmLoading={save.isPending}
        onOk={() => form.submit()}
        onCancel={() => { setEditor(null); form.resetFields(); }}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" preserve={false} onFinish={async (values) => {
          try {
            await save.mutateAsync(values);
          } catch {
            // 请求层显示错误；失败保留输入。
          }
        }}>
          <Form.Item name="name" label="公司名称" rules={NAME_RULES}><Input /></Form.Item>
        </Form>
      </Modal>

      <Modal
        title="公司详情"
        open={detailId != null}
        onCancel={() => setDetailId(null)}
        footer={<Button onClick={() => setDetailId(null)}>关闭</Button>}
        destroyOnHidden
      >
        {detail.isError ? (
          <span>详情加载失败，请关闭后重试。</span>
        ) : detail.isPending ? (
          <Skeleton active paragraph={{ rows: 3 }} />
        ) : (
          <Descriptions
            column={1}
            size="small"
            items={[
              { key: 'id', label: '公司 ID', children: detail.data?.id ?? '—' },
              { key: 'name', label: '公司名称', children: detail.data?.name ?? '—' },
              { key: 'createdAt', label: '创建时间', children: detail.data?.createdAt ?? '—' },
            ]}
          />
        )}
      </Modal>
    </PageContainer>
  );
}
