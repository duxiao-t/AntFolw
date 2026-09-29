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
  /** 姓名（后端已按 display_name→username 回落）。 */
  createdByName?: string;
  createdByEmployeeNo?: string;
  createdByDeptName?: string;
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
  // 的账号取定义会被拒（前置权限不足时是 403，不是 404），那不该让整页弹错误——
  // 只是下拉显示原始值、检查项显示条目数。skipErrorHandler 把这条可选请求从全局错误处理里摘出来。
  const definition = useQuery<{ schema?: string }>({
    queryKey: ['form-definition-for-ledger', formDefId],
    queryFn: () => request(`/api/forms/definitions/${formDefId}`, { skipErrorHandler: true }),
    enabled: Boolean(formDefId),
    retry: false,
    throwOnError: false,
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
        // 显示姓名（不是登录账号），并且筛选也改成按姓名/工号搜——列上写着人名、
        // 筛选框却要输入数字 id，那种割裂比不显示还难用。
        title: '提交人',
        dataIndex: 'createdByName',
        width: 140,
        // 搜索框发出去的参数名要与接口一致（列名是给人看的，参数名是给后端看的）。
        search: { transform: (value: string) => ({ submitterKeyword: value }) },
        render: (_, record) =>
          record.createdByName ?? (record.createdBy ? `用户 #${record.createdBy}` : '—'),
      },
      {
        title: '工号',
        dataIndex: 'createdByEmployeeNo',
        width: 110,
        search: false,
        render: (_, record) => record.createdByEmployeeNo || '—',
      },
      {
        title: '部门',
        dataIndex: 'createdByDeptName',
        width: 150,
        ellipsis: true,
        search: false,
        render: (_, record) => record.createdByDeptName
          || <span style={{ color: 'var(--af-color-muted)' }}>未设置部门</span>,
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
        submitterKeyword: params.submitterKeyword,
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
