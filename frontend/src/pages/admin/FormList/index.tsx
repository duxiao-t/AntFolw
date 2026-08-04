import type { ActionType, ProColumns } from '@ant-design/pro-components';
import {
  ModalForm,
  ProFormText,
  ProFormTextArea,
  ProTable,
} from '@ant-design/pro-components';
import { useNavigate } from '@umijs/max';
import { request } from '@umijs/max';
import { App, Button, Popconfirm, Space } from 'antd';
import { useRef } from 'react';

type FormDefinition = {
  id: number;
  code: string;
  name: string;
  description?: string;
  version: number;
  schema?: unknown;
  settings?: unknown;
  status: 'DRAFT' | 'PUBLISHED' | 'DEPRECATED';
  createdAt?: string;
  updatedAt?: string;
};

type PageResult<T> = {
  records?: T[];
  total?: number;
};

const statusValueEnum = {
  DRAFT: { text: '草稿', status: 'Default' },
  PUBLISHED: { text: '已发布', status: 'Success' },
  DEPRECATED: { text: '已停用', status: 'Warning' },
} as const;

const defaultMobileSettings = {
  platforms: ['h5'],
  layout: {
    mode: 'mobile',
    labelPlacement: 'top',
  },
};

export default function FormListPage() {
  const navigate = useNavigate();
  const { message } = App.useApp();
  const actionRef = useRef<ActionType | undefined>(undefined);

  const reload = () => actionRef.current?.reload();

  const createForm = async (values: Partial<FormDefinition>) => {
    await request('/api/forms/definitions', {
      method: 'POST',
      data: {
        code: values.code,
        name: values.name,
        description: values.description,
        schema: [],
        settings: defaultMobileSettings,
      },
    });
    message.success('已创建草稿');
    reload();
  };

  const updateForm = async (id: number, values: Partial<FormDefinition>) => {
    await request(`/api/forms/definitions/${id}`, {
      method: 'PUT',
      data: {
        name: values.name,
        description: values.description ?? '',
      },
    });
    message.success('已更新');
    reload();
  };

  const publishForm = async (id: number) => {
    await request(`/api/forms/definitions/${id}/publish`, { method: 'POST' });
    message.success('已发布');
    reload();
  };

  const disableForm = async (id: number) => {
    await request(`/api/forms/definitions/${id}/disable`, { method: 'POST' });
    message.success('已停用');
    reload();
  };

  const deleteForm = async (id: number) => {
    await request(`/api/forms/definitions/${id}`, { method: 'DELETE' });
    message.success('已删除');
    reload();
  };

  const columns: ProColumns<FormDefinition>[] = [
    { title: 'ID', dataIndex: 'id', search: false, width: 80 },
    {
      title: '关键词',
      dataIndex: 'keyword',
      hideInTable: true,
      fieldProps: { placeholder: '表单名称 / code' },
    },
    { title: 'code', dataIndex: 'code', search: false, copyable: true },
    { title: '名称', dataIndex: 'name', search: false },
    {
      title: '描述',
      dataIndex: 'description',
      search: false,
      ellipsis: true,
    },
    { title: '版本', dataIndex: 'version', search: false, width: 80 },
    {
      title: '状态',
      dataIndex: 'status',
      valueEnum: statusValueEnum,
      width: 110,
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      search: false,
      valueType: 'dateTime',
      width: 180,
    },
    {
      title: '操作',
      valueType: 'option',
      width: 360,
      render: (_, fd) => (
        <Space wrap>
          <ModalForm
            title="编辑表单"
            trigger={<Button size="small">编辑</Button>}
            initialValues={fd}
            modalProps={{ destroyOnHidden: true }}
            onFinish={async (vals) => {
              await updateForm(fd.id, vals);
              return true;
            }}
          >
            <ProFormText
              name="name"
              label="表单名称"
              rules={[{ required: true, message: '请输入表单名称' }]}
            />
            <ProFormTextArea
              name="description"
              label="描述"
              fieldProps={{ maxLength: 500, showCount: true }}
            />
          </ModalForm>
          <Button
            size="small"
            onClick={() => navigate(`/approval/forms/${fd.id}/wizard?step=designer`)}
          >
            组件配置
          </Button>
          <Button
            size="small"
            onClick={() => navigate(`/approval/forms/${fd.id}/wizard?step=process`)}
          >
            流程
          </Button>
          <Button
            size="small"
            disabled={fd.status !== 'DRAFT'}
            onClick={() => publishForm(fd.id)}
          >
            发布
          </Button>
          <Button
            size="small"
            disabled={fd.status === 'DEPRECATED'}
            onClick={() => disableForm(fd.id)}
          >
            停用
          </Button>
          <Button
            size="small"
            onClick={() => navigate(`/admin/form-data?formDefId=${fd.id}`)}
          >
            数据
          </Button>
          <Popconfirm
            title="确认删除该表单？"
            description="删除后列表不再展示，历史提交数据仍会保留。"
            onConfirm={() => deleteForm(fd.id)}
          >
            <Button size="small" danger>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <ProTable
      actionRef={actionRef}
      rowKey="id"
      columns={columns}
      pagination={{ defaultPageSize: 20 }}
      request={async (params) => {
        const result = await request<PageResult<FormDefinition>>(
          '/api/forms/definitions',
          {
            params: {
              page: params.current,
              size: params.pageSize,
              keyword: params.keyword,
              status: params.status,
            },
          },
        );
        return {
          data: result.records ?? [],
          total: result.total ?? 0,
          success: true,
        };
      }}
      toolBarRender={() => [
        <ModalForm
          key="new"
          title="新建表单"
          trigger={<Button type="primary">新建表单</Button>}
          modalProps={{ destroyOnHidden: true }}
          onFinish={async (vals) => {
            await createForm(vals);
            return true;
          }}
        >
          <ProFormText
            name="code"
            label="表单 code"
            tooltip="作为 H5 填写链接的一部分，创建后不可编辑"
            rules={[
              { required: true, message: '请输入表单 code' },
              {
                pattern: /^[a-zA-Z][a-zA-Z0-9_-]{1,63}$/,
                message: '需以字母开头，支持字母、数字、下划线、中划线，2-64 位',
              },
            ]}
          />
          <ProFormText
            name="name"
            label="表单名称"
            rules={[{ required: true, message: '请输入表单名称' }]}
          />
          <ProFormTextArea
            name="description"
            label="描述"
            fieldProps={{ maxLength: 500, showCount: true }}
          />
        </ModalForm>,
      ]}
    />
  );
}
