import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { request, useLocation } from '@umijs/max';
import { Button, Modal, Typography } from 'antd';
import { useRef, useState } from 'react';

type FormDataRecord = {
  id: number;
  formDefId: number;
  formDefVersion: number;
  data?: unknown;
  status: 'DRAFT' | 'SUBMITTED';
  createdBy?: number;
  createdAt?: string;
};

type PageResult<T> = {
  records?: T[];
  total?: number;
};

const statusValueEnum = {
  DRAFT: { text: '草稿', status: 'Default' },
  SUBMITTED: { text: '已提交', status: 'Success' },
} as const;

export default function AdminFormDataPage() {
  const actionRef = useRef<ActionType | undefined>(undefined);
  const location = useLocation();
  const [current, setCurrent] = useState<FormDataRecord | null>(null);
  const searchParams = new URLSearchParams(location.search);
  const initialFormDefId = searchParams.get('formDefId') ?? undefined;

  const columns: ProColumns<FormDataRecord>[] = [
    { title: 'ID', dataIndex: 'id', search: false, width: 80 },
    {
      title: '表单 ID',
      dataIndex: 'formDefId',
      valueType: 'digit',
      initialValue: initialFormDefId,
      width: 100,
    },
    {
      title: '表单版本',
      dataIndex: 'formDefVersion',
      search: false,
      width: 100,
    },
    {
      title: '状态',
      dataIndex: 'status',
      valueEnum: statusValueEnum,
      width: 110,
    },
    {
      title: '提交人',
      dataIndex: 'createdBy',
      valueType: 'digit',
      width: 100,
    },
    {
      title: '提交时间',
      dataIndex: 'createdAt',
      search: false,
      valueType: 'dateTime',
      width: 180,
    },
    {
      title: '提交数据',
      dataIndex: 'data',
      search: false,
      ellipsis: true,
      renderText: (value) =>
        typeof value === 'string' ? value : JSON.stringify(value ?? {}),
    },
    {
      title: '操作',
      valueType: 'option',
      width: 90,
      render: (_, record) => (
        <Button size="small" onClick={() => setCurrent(record)}>
          查看
        </Button>
      ),
    },
  ];

  return (
    <>
      <ProTable
        actionRef={actionRef}
        rowKey="id"
        columns={columns}
        pagination={{ defaultPageSize: 20 }}
        request={async (params) => {
          const result = await request<PageResult<FormDataRecord>>(
            '/api/forms/data/admin',
            {
              params: {
                page: params.current,
                size: params.pageSize,
                formDefId: params.formDefId,
                status: params.status,
                createdBy: params.createdBy,
              },
            },
          );
          return {
            data: result.records ?? [],
            total: result.total ?? 0,
            success: true,
          };
        }}
      />
      <Modal
        title={`提交数据 #${current?.id ?? ''}`}
        open={!!current}
        footer={null}
        onCancel={() => setCurrent(null)}
        width={720}
      >
        <Typography.Paragraph>
          <pre style={{ maxHeight: 520, overflow: 'auto' }}>
            {JSON.stringify(current?.data ?? {}, null, 2)}
          </pre>
        </Typography.Paragraph>
      </Modal>
    </>
  );
}
