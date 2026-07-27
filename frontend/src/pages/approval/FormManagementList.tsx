import { PageContainer, ProTable } from '@ant-design/pro-components';
import { Button, Space, Tag } from 'antd';
import { ApartmentOutlined, MinusCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { history, request } from '@umijs/max';

type FormDefinition = {
  id: number;
  code: string;
  name: string;
  description?: string;
  status: 'DRAFT' | 'PUBLISHED' | 'DEPRECATED';
  version: number;
  settings?: Record<string, any> | string;
  updatedAt?: string;
};

type PageResult<T> = {
  records?: T[];
  total?: number;
};

const statusMap = {
  DRAFT: { color: 'default', text: '草稿' },
  PUBLISHED: { color: 'green', text: '已发布' },
  DEPRECATED: { color: 'red', text: '已停用' },
};

function parseJsonValue<T>(value: T | string | undefined, fallback: T): T {
  if (typeof value !== 'string') return value ?? fallback;
  try {
    return JSON.parse(value);
  } catch {
    return fallback;
  }
}

function isWorkflowEnabled(settings: FormDefinition['settings']) {
  return !!parseJsonValue<Record<string, any>>(settings, {}).workflowEnabled;
}

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
            title: '流程',
            dataIndex: 'settings',
            width: 120,
            render: (_, record) =>
              isWorkflowEnabled(record.settings) ? (
                <Tag color="blue" icon={<ApartmentOutlined />}>有流程</Tag>
              ) : (
                <Tag icon={<MinusCircleOutlined />}>无流程</Tag>
              ),
          },
          {
            title: '操作',
            key: 'op',
            width: 220,
            render: (_, record) => (
              <Space>
                <a onClick={() => history.push(`/approval/forms/${record.id}/wizard?step=basic`)}>编辑</a>
                <a onClick={() => history.push(`/approval/forms/${record.id}/wizard?step=publish`)}>发布</a>
                <a onClick={() => history.push(`/approval/form-data?formDefId=${record.id}`)}>数据</a>
              </Space>
            ),
          },
        ]}
        request={async (params) => {
          const result = await request<PageResult<FormDefinition>>(
            '/api/forms/definitions',
            {
              params: {
                page: params.current,
                size: params.pageSize,
              },
            },
          );
          return {
            data: result.records ?? [],
            success: true,
            total: result.total ?? 0,
          };
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
