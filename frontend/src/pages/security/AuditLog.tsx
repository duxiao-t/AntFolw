import { DownloadOutlined, FileSearchOutlined } from '@ant-design/icons';
import { PageContainer, ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components';
import { App, Button, Descriptions, Drawer, Space, Table, Tabs, Tag, Typography } from 'antd';
import { request, useModel } from '@umijs/max';
import { useRef, useState } from 'react';

type AuditEvent = {
  id: number;
  occurredAt: string;
  requestId: string;
  actorUserId?: number;
  actorUsername?: string;
  actorDisplayName?: string;
  action: string;
  resourceType?: string;
  resourceId?: string;
  result: 'SUCCESS' | 'DENIED' | 'FAILURE';
  riskLevel: 'NORMAL' | 'HIGH' | 'CRITICAL';
  clientIp?: string;
  userAgent?: string;
  failureCode?: string;
  fieldDiff: Record<string, unknown>;
  metadata: Record<string, unknown>;
};

type AuditPage = { records: AuditEvent[]; total: number };

type Archive = {
  id: string;
  rangeStart: string;
  rangeEnd: string;
  eventCount: number;
  objectKey: string;
  keyId: string;
  sha256: string;
  status: 'READY' | 'FAILED';
  errorMessage?: string;
  verifiedAt?: string;
};

const resultColor = { SUCCESS: 'green', DENIED: 'orange', FAILURE: 'red' };
const riskColor = { NORMAL: 'default', HIGH: 'orange', CRITICAL: 'red' };
const resultLabel = { SUCCESS: '成功', DENIED: '拒绝', FAILURE: '失败' };
const riskLabel = { NORMAL: '普通', HIGH: '高风险', CRITICAL: '关键' };
const actionLabel: Record<string, string> = {
  'org.user.create': '创建用户',
  'org.user.update': '编辑用户',
  'org.user.delete': '删除用户',
  'org.user.password.reset': '重置用户密码',
  'security.role.create': '创建角色',
  'security.role.update': '编辑角色',
  'security.role.delete': '删除角色',
  'security.user_role.update': '更新用户角色',
};

function downloadBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = name;
  anchor.click();
  URL.revokeObjectURL(url);
}

export default function AuditLogPage() {
  const actionRef = useRef<ActionType | undefined>(undefined);
  const exportParams = useRef<Record<string, unknown>>({});
  const { message } = App.useApp();
  const { initialState } = useModel('@@initialState');
  const [detail, setDetail] = useState<AuditEvent | null>(null);
  const [archives, setArchives] = useState<Archive[]>([]);
  const currentUser = initialState?.currentUser as any;
  const isAdmin = (currentUser?.roles ?? []).includes('admin');
  const permissions: string[] = currentUser?.permissions ?? [];
  const canExport = isAdmin || permissions.includes('security.audit.export');
  const canDownloadArchive = isAdmin
    || permissions.includes('security.audit.archive.download');

  const exportEvents = async () => {
    const blob = await request<Blob>('/api/audit/export', {
      responseType: 'blob',
      params: exportParams.current,
    });
    downloadBlob(blob, 'antflow-audit.ndjson');
    message.success('审计日志已导出');
  };

  const downloadArchive = async (archive: Archive) => {
    const blob = await request<Blob>(`/api/audit/archives/${archive.id}/download`, {
      responseType: 'blob',
    });
    downloadBlob(blob, archive.objectKey.split('/').at(-1) ?? 'audit-archive.enc');
  };

  const columns: ProColumns<AuditEvent>[] = [
    {
      title: '时间',
      dataIndex: 'occurredAt',
      valueType: 'dateTime',
      width: 180,
      search: false,
    },
    {
      title: '时间范围',
      dataIndex: 'timeRange',
      valueType: 'dateTimeRange',
      hideInTable: true,
      search: {
        transform: (value) => ({ from: value?.[0], to: value?.[1] }),
      },
    },
    {
      title: '操作者',
      dataIndex: 'operatorId',
      width: 150,
      render: (_, event) => event.actorDisplayName || event.actorUsername || (event.actorUserId ? `#${event.actorUserId}` : '系统'),
    },
    { title: '动作', dataIndex: 'action', width: 220, copyable: true, render: (_, event) => actionLabel[event.action] ?? event.action },
    {
      title: '资源',
      dataIndex: 'resourceType',
      width: 190,
      render: (_, event) => event.resourceType ? `${event.resourceType}${event.resourceId ? ` · ${event.resourceId}` : ''}` : '—',
    },
    {
      title: '结果',
      dataIndex: 'result',
      valueType: 'select',
      width: 100,
      valueEnum: {
        SUCCESS: { text: '成功' },
        DENIED: { text: '拒绝' },
        FAILURE: { text: '失败' },
      },
      render: (_, event) => <Tag color={resultColor[event.result]}>{resultLabel[event.result]}</Tag>,
    },
    {
      title: '风险',
      dataIndex: 'riskLevel',
      valueType: 'select',
      width: 100,
      valueEnum: {
        NORMAL: { text: '普通' },
        HIGH: { text: '高' },
        CRITICAL: { text: '关键' },
      },
      render: (_, event) => <Tag color={riskColor[event.riskLevel]}>{riskLabel[event.riskLevel]}</Tag>,
    },
    { title: 'IP', dataIndex: 'ip', width: 140, render: (_, event) => event.clientIp ?? '—' },
    {
      title: '详情',
      search: false,
      width: 70,
      fixed: 'right',
      render: (_, event) => (
        <Button type="text" icon={<FileSearchOutlined />} aria-label="查看审计详情" onClick={() => setDetail(event)} />
      ),
    },
  ];

  return (
    <PageContainer title={false}>
      <Tabs className="security-audit-tabs"
        onChange={(key) => {
          if (key === 'archives') request<Archive[]>('/api/audit/archives').then(setArchives);
        }}
        items={[
          {
            key: 'events',
            label: '在线日志',
            children: (
              <ProTable<AuditEvent>
                actionRef={actionRef}
                rowKey="id"
                columns={columns}
                scroll={{ x: 1280 }}
                options={false}
                toolBarRender={() => canExport ? [
                  <Button key="export" icon={<DownloadOutlined />} onClick={exportEvents}>导出当前范围</Button>,
                ] : []}
                request={async (params) => {
                  exportParams.current = { ...params, current: undefined, pageSize: undefined };
                  const result = await request<AuditPage>('/api/audit/events', {
                    params: {
                      ...params,
                      page: params.current,
                      size: params.pageSize,
                    },
                  });
                  return { data: result.records, success: true, total: result.total };
                }}
              />
            ),
          },
          {
            key: 'archives',
            label: '归档',
            children: (
              <Table<Archive>
                rowKey="id"
                dataSource={archives}
                pagination={false}
                columns={[
                  { title: '时间范围', render: (_, row) => `${row.rangeStart} 至 ${row.rangeEnd}` },
                  { title: '事件数', dataIndex: 'eventCount', width: 100 },
                  { title: '密钥', dataIndex: 'keyId', width: 140 },
                  { title: 'SHA-256', dataIndex: 'sha256', ellipsis: true },
                  { title: '状态', dataIndex: 'status', width: 100, render: (value) => <Tag color={value === 'READY' ? 'green' : 'red'}>{value}</Tag> },
                  {
                    title: '下载',
                    width: 80,
                    render: (_, row) => canDownloadArchive && row.status === 'READY' ? (
                      <Button type="text" icon={<DownloadOutlined />} aria-label="下载审计归档" onClick={() => downloadArchive(row)} />
                    ) : null,
                  },
                ]}
              />
            ),
          },
        ]}
      />

      <Drawer title="审计事件详情" open={!!detail} size={720} onClose={() => setDetail(null)}>
        {detail && (
          <Space orientation="vertical" size={20} style={{ width: '100%' }}>
            <Descriptions
              bordered
              size="small"
              column={2}
              items={[
                { key: 'id', label: '事件 ID', children: detail.id },
                { key: 'time', label: 'UTC 时间', children: detail.occurredAt },
                { key: 'request', label: '请求 ID', children: detail.requestId, span: 2 },
                { key: 'actor', label: '操作者', children: detail.actorDisplayName || detail.actorUsername || '系统' },
                { key: 'ip', label: '客户端 IP', children: detail.clientIp ?? '—' },
                { key: 'action', label: '动作', children: detail.action, span: 2 },
                { key: 'resource', label: '资源', children: `${detail.resourceType ?? '—'} · ${detail.resourceId ?? '—'}`, span: 2 },
                { key: 'result', label: '结果', children: detail.result },
                { key: 'risk', label: '风险', children: detail.riskLevel },
                { key: 'failure', label: '失败码', children: detail.failureCode ?? '—', span: 2 },
              ]}
            />
            <div>
              <Typography.Title level={5}>字段差异</Typography.Title>
              <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', padding: 12, background: '#f5f5f5', borderRadius: 6 }}>{JSON.stringify(detail.fieldDiff, null, 2)}</pre>
            </div>
            <div>
              <Typography.Title level={5}>扩展元数据</Typography.Title>
              <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', padding: 12, background: '#f5f5f5', borderRadius: 6 }}>{JSON.stringify(detail.metadata, null, 2)}</pre>
            </div>
          </Space>
        )}
      </Drawer>
    </PageContainer>
  );
}
