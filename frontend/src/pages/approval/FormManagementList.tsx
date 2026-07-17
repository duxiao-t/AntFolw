import { PageContainer, ProTable } from '@ant-design/pro-components';
import { Button, Space, Tag } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { history, request } from '@umijs/max';

type FormDefinition = {
  id: number;
  code: string;
  name: string;
  status: 'DRAFT' | 'PUBLISHED' | 'DEPRECATED';
  version: number;
  updatedAt?: string;
};

const statusMap = {
  DRAFT: { color: 'default', text: '草稿' },
  PUBLISHED: { color: 'green', text: '已发布' },
  DEPRECATED: { color: 'red', text: '已停用' },
};

export default function FormManagementList() {
  return (
    <PageContainer title={false}>
      <ProTable<FormDefinition>
        rowKey="id"
        columns={[
          { title: '表单名称', dataIndex: 'name' },
          { title: '表单编码', dataIndex: 'code' },
          { title: '版本', dataIndex: 'version', width: 80 },
          {
            title: '状态',
            dataIndex: 'status',
            width: 120,
            render: (_, record) => {
              const status = statusMap[record.status] ?? { color: 'default', text: record.status };
              return <Tag color={status.color}>{status.text}</Tag>;
            },
          },
          {
            title: '操作',
            key: 'op',
            width: 180,
            render: (_, record) => (
              <Space>
                <a onClick={() => history.push(`/approval/forms/${record.id}/wizard?step=basic`)}>编辑</a>
                <a onClick={() => history.push(`/approval/forms/${record.id}/wizard?step=publish`)}>发布</a>
              </Space>
            ),
          },
        ]}
        request={async () => {
          const list = await request<FormDefinition[]>('/api/forms/definitions');
          return { data: list ?? [], success: true, total: list?.length ?? 0 };
        }}
        search={false}
        options={false}
        toolBarRender={() => [
          <Button key="new" type="primary" icon={<PlusOutlined />} onClick={() => history.push('/approval/forms/new')}>
            新建表单
          </Button>,
        ]}
      />
    </PageContainer>
  );
}
