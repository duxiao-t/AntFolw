import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useQuery } from '@tanstack/react-query';
import { Link, request, useLocation } from '@umijs/max';
import { Drawer, Typography } from 'antd';
import { useCallback, useMemo, useRef, useState } from 'react';
import {
  cellTextFor,
  detailTextFor,
  fieldColumns,
  fieldMetas,
  type FieldMeta,
  type FormDataFieldValue,
} from './fieldValues';

type FormDataRecord = {
  id: number;
  formDefId: number;
  formDefVersion: number;
  businessNo?: string | null;
  data?: unknown;
  status: 'DRAFT' | 'SUBMITTED';
  createdBy?: number;
  createdByUsername?: string;
  fieldValues?: FormDataFieldValue[];
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
  // 台账的字段列由本页数据推导——这个接口不回 schema，取不到字段清单与类型。
  const [fields, setFields] = useState<Array<{ id: string; label: string }>>([]);
  const searchParams = new URLSearchParams(location.search);
  const initialFormDefId = searchParams.get('formDefId') ?? undefined;
  const [formDefId, setFormDefId] = useState<string | undefined>(initialFormDefId);

  // 字段类型与选项只能从表单定义里拿（数据接口不给）。取不到就退化：只有 form:data:read
  // 的账号取定义会 404，那不该让整页报错——只是下拉显示原始值、检查项显示条目数。
  const definition = useQuery<{ schema?: string }>({
    queryKey: ['form-definition-for-ledger', formDefId],
    queryFn: () => request(`/api/forms/definitions/${formDefId}`),
    enabled: Boolean(formDefId),
    retry: false,
  });
  const metas = useMemo<Map<string, FieldMeta>>(() => {
    if (!definition.data?.schema) return new Map();
    try {
      return fieldMetas(JSON.parse(definition.data.schema));
    } catch {
      return new Map();
    }
  }, [definition.data?.schema]);

  const columns = useMemo<ProColumns<FormDataRecord>[]>(() => {
    const meta: ProColumns<FormDataRecord>[] = [
      {
        title: '业务单号',
        dataIndex: 'businessNo',
        search: false,
        width: 150,
        render: (_, record) => record.businessNo || '—',
      },
      {
        title: '提交人',
        dataIndex: 'createdBy',
        valueType: 'digit',
        width: 120,
        render: (_, record) =>
          record.createdByUsername ?? (record.createdBy ? `用户 #${record.createdBy}` : '—'),
      },
      {
        title: '提交时间',
        dataIndex: 'createdAt',
        search: false,
        valueType: 'dateTime',
        width: 170,
      },
      {
        title: '状态',
        dataIndex: 'status',
        valueEnum: statusValueEnum,
        width: 100,
      },
    ];
    const fieldCols: ProColumns<FormDataRecord>[] = fields.map((field) => ({
      title: field.label,
      dataIndex: ['fieldValues', field.id],
      search: false,
      ellipsis: true,
      width: 180,
      render: (_, record) => {
        const hit = record.fieldValues?.find((item) => item.fieldId === field.id);
        const text = cellTextFor(hit?.value, metas.get(field.id));
        return text || '—';
      },
    }));
    return [
      // 表单 ID 只在筛选里用：从「表单管理 → 数据」进来时它已经被带上了。
      {
        title: '表单 ID',
        dataIndex: 'formDefId',
        valueType: 'digit',
        initialValue: initialFormDefId,
        hideInTable: true,
      },
      ...meta,
      ...fieldCols,
    ];
  }, [fields, initialFormDefId, metas]);

  const loadRecords = useCallback(async (params: Record<string, any>) => {
    const result = await request<PageResult<FormDataRecord>>('/api/forms/data/admin', {
      params: {
        page: params.current,
        size: params.pageSize,
        formDefId: params.formDefId,
        status: params.status,
        createdBy: params.createdBy,
      },
    });
    const records = result.records ?? [];
    setFields(fieldColumns(records.map((record) => record.fieldValues ?? [])));
    // 筛选里改了表单 ID 时，字段字典也要跟着换。
    setFormDefId(params.formDefId ? String(params.formDefId) : undefined);
    return { data: records, total: result.total ?? 0, success: true };
  }, []);

  return (
    <PageContainer
      title={false}
      breadcrumb={{
        items: [
          { title: <Link to="/approval/forms">表单管理</Link> },
          { title: '提交数据' },
        ],
      }}
    >
      <ProTable
        actionRef={actionRef}
        rowKey="id"
        columns={columns}
        pagination={{ defaultPageSize: 20 }}
        scroll={{ x: 'max-content' }}
        onRow={(record) => ({ onClick: () => setCurrent(record), style: { cursor: 'pointer' } })}
        request={loadRecords}
      />
      <Drawer
        title={`提交数据 #${current?.id ?? ''}`}
        open={!!current}
        onClose={() => setCurrent(null)}
        width={640}
      >
        <dl style={{ margin: 0 }}>
          {(current?.fieldValues ?? []).map((field) => (
            <div
              key={field.fieldId}
              style={{ padding: '10px 0', borderTop: '1px solid var(--af-color-line)' }}
            >
              <dt style={{ fontSize: 12, color: 'var(--af-color-muted)' }}>
                {field.fieldName || field.fieldId}
              </dt>
              <dd style={{ margin: '2px 0 0' }}>
                <Typography.Text style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
                  {detailTextFor(field.value, metas.get(field.fieldId))}
                </Typography.Text>
              </dd>
            </div>
          ))}
        </dl>
      </Drawer>
    </PageContainer>
  );
}
