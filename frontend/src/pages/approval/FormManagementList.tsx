import { PageContainer, ProTable, type ActionType } from '@ant-design/pro-components';
import { App, Button, Popconfirm, Space, Tag } from 'antd';
import { ApartmentOutlined, MinusCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { history, request } from '@umijs/max';
import { useRef } from 'react';

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
  const actionRef = useRef<ActionType | undefined>(undefined);
  const { message } = App.useApp();

  const handleDelete = async (record: FormDefinition) => {
    try {
      await request(`/api/forms/definitions/${record.id}`, { method: 'DELETE' });
      message.success('已删除');
      actionRef.current?.reload();
    } catch (error: any) {
      message.error(error?.message ?? '删除失败');
    }
  };

  const handleDisable = async (record: FormDefinition) => {
    try {
      await request(`/api/forms/definitions/${record.id}/disable`, { method: 'POST' });
      message.success('已停用');
      actionRef.current?.reload();
    } catch (error: any) {
      message.error(error?.message ?? '停用失败');
    }
  };

  return (
    <PageContainer title={false}>
      <ProTable<FormDefinition>
        actionRef={actionRef}
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
            width: 260,
            render: (_, record) => {
              const deprecated = record.status === 'DEPRECATED';
              return (
                <Space>
                  <a onClick={() => history.push(`/approval/forms/${record.id}/wizard?step=basic`)}>编辑</a>
                  <Popconfirm
                    title="确认停用该表单？"
                    description="停用后表单将不再可发起填报。"
                    okText="停用"
                    okButtonProps={{ danger: true }}
                    disabled={deprecated}
                    onConfirm={() => handleDisable(record)}
                  >
                    <a
                      style={{
                        color: deprecated ? 'rgba(0, 0, 0, 0.25)' : undefined,
                        cursor: deprecated ? 'not-allowed' : 'pointer',
                      }}
                    >
                      {deprecated ? '已停用' : '停用'}
                    </a>
                  </Popconfirm>
                  <Popconfirm
                    title="确认删除该表单？"
                    description="删除后列表不再展示，历史提交数据仍会保留。"
                    okText="删除"
                    okButtonProps={{ danger: true }}
                    onConfirm={() => handleDelete(record)}
                  >
                    <a style={{ color: '#ff4d4f' }}>删除</a>
                  </Popconfirm>
                  <a onClick={() => history.push(`/approval/form-data?formDefId=${record.id}`)}>数据</a>
                </Space>
              );
            },
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
