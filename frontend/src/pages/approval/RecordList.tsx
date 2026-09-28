import { PageContainer, ProTable, type ProColumns } from '@ant-design/pro-components';
import { Button, Tag, Tooltip } from 'antd';
import { createStyles } from 'antd-style';
import { request, history } from '@umijs/max';
import dayjs from 'dayjs';
import {
  formatDuration,
  instanceStatus,
  nodeLabel,
  parseProcessSnapshot,
} from '../proc/detailPresentation';

type InstanceRecord = {
  id: number;
  formCode?: string | null;
  formName?: string | null;
  businessNo?: string | null;
  startedBy?: number | null;
  applicantName?: string | null;
  applicantEmployeeNo?: string | null;
  applicantDepartment?: string | null;
  status: string;
  currentNodeId?: string | null;
  processSnapshot?: unknown;
  startedAt?: string | null;
  finishedAt?: string | null;
};

const useStyles = createStyles(({ token }) => ({
  cell: { display: 'grid', gap: 5, minWidth: 0 },
  title: {
    color: token.colorText, fontWeight: 600,
    overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
  },
  detail: { color: token.colorTextSecondary, fontSize: 12, lineHeight: 1.5 },
  number: { fontFamily: "'SFMono-Regular', Consolas, monospace", fontVariantNumeric: 'tabular-nums' },
  row: { cursor: 'pointer' },
}));

function formatTime(value?: string | null) {
  return value && dayjs(value).isValid() ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—';
}

function currentStage(record: InstanceRecord) {
  if (record.status !== 'RUNNING') return '流程已结束';
  if (record.currentNodeId === '__rework__') return '修改后重提';
  const label = nodeLabel(parseProcessSnapshot(record.processSnapshot), record.currentNodeId);
  return label === record.currentNodeId || !record.currentNodeId ? '审批处理中' : label;
}

export default function RecordListPage() {
  const { styles, cx } = useStyles();
  const columns: ProColumns<InstanceRecord>[] = [
    {
      title: '关键词', dataIndex: 'keyword', hideInTable: true,
      fieldProps: { placeholder: '表单、业务单号、申请人或实例号', allowClear: true },
    },
    {
      title: '审批事项', dataIndex: 'formName', width: 280, search: false,
      render: (_, record) => (
        <div className={styles.cell}>
          <strong className={styles.title} title={record.formName ?? record.formCode ?? undefined}>
            {record.formName || record.formCode || '未命名流程'}
          </strong>
          <span className={cx(styles.detail, styles.number)}>
            {record.businessNo ? `单号 ${record.businessNo} · ` : ''}实例 #{record.id}
          </span>
        </div>
      ),
    },
    {
      title: '申请人', dataIndex: 'applicantName', width: 190, search: false,
      render: (_, record) => (
        <div className={styles.cell}>
          <span className={styles.title}>{record.applicantName || '未记录申请人'}</span>
          <span className={styles.detail}>
            {[record.applicantDepartment || '未记录部门',
              record.applicantEmployeeNo ? `工号 ${record.applicantEmployeeNo}` : null]
              .filter(Boolean).join(' · ')}
          </span>
        </div>
      ),
    },
    {
      title: '流程状态', dataIndex: 'status', width: 110, valueType: 'select',
      valueEnum: {
        RUNNING: { text: '进行中（含待修改）' },
        REWORK: { text: '待修改' },
        APPROVED: { text: '已通过' },
        REJECTED: { text: '已驳回' },
        WITHDRAWN: { text: '已撤回' },
      },
      render: (_, record) => {
        const status = instanceStatus(record.status, record.currentNodeId);
        return <Tag color={status.tone}>{status.label}</Tag>;
      },
    },
    {
      title: '当前环节', dataIndex: 'currentNodeId', width: 150, search: false,
      render: (_, record) => (
        <Tooltip title={record.currentNodeId ? `节点标识：${record.currentNodeId}` : undefined}>
          <span className={styles.title}>{currentStage(record)}</span>
        </Tooltip>
      ),
    },
    {
      title: '发起时间', dataIndex: 'startedAt', width: 165, search: false,
      render: (_, record) => <span className={styles.number}>{formatTime(record.startedAt)}</span>,
    },
    {
      title: '发起时间', dataIndex: 'startedRange', valueType: 'dateRange', hideInTable: true,
      search: {
        transform: (value: string[] | undefined) => value?.length === 2 ? {
          from: dayjs(value[0]).startOf('day').toISOString(),
          to: dayjs(value[1]).add(1, 'day').startOf('day').toISOString(),
        } : {},
      },
    },
    {
      title: '流转耗时', key: 'duration', width: 180, search: false,
      render: (_, record) => (
        <div className={styles.cell}>
          <span>{record.status === 'RUNNING' ? '已流转 ' : ''}
            {record.status !== 'RUNNING' && !record.finishedAt
              ? '未记录' : formatDuration(record.startedAt, record.finishedAt)}
          </span>
          <span className={styles.detail}>
            {record.finishedAt ? `完成于 ${formatTime(record.finishedAt)}`
              : record.status === 'RUNNING' ? '尚未结束' : '未记录完成时间'}
          </span>
        </div>
      ),
    },
    {
      title: '操作', valueType: 'option', width: 110, fixed: 'right',
      render: (_, record) => (
        <Button type="link" size="small" onClick={(event) => {
          event.stopPropagation();
          history.push(`/proc/${record.id}`);
        }}>查看详情</Button>
      ),
    },
  ];

  return (
    <PageContainer title="审批记录" subTitle="查询授权范围内的申请、流转状态与审批轨迹">
      <ProTable<InstanceRecord>
        headerTitle="审批记录"
        rowKey="id"
        columns={columns}
        options={false}
        scroll={{ x: 1185 }}
        tableLayout="fixed"
        request={async (params) => {
          const result = await request<WorkflowPage<InstanceRecord>>('/api/instances', {
            params: {
              scope: 'authorized',
              page: params.current,
              size: params.pageSize,
              status: params.status,
              keyword: params.keyword?.trim() || undefined,
              from: params.from,
              to: params.to,
            },
          });
          return { data: result.records, success: true, total: result.total };
        }}
        pagination={{ defaultPageSize: 20, showSizeChanger: true,
          pageSizeOptions: [20, 50, 100], showTotal: (total) => `共 ${total} 条` }}
        search={{ labelWidth: 'auto', defaultCollapsed: false, collapseRender: false }}
        onRow={(record) => ({
          onClick: () => history.push(`/proc/${record.id}`),
          className: styles.row,
        })}
      />
    </PageContainer>
  );
}

type WorkflowPage<T> = { records: T[]; total: number; page: number; size: number };
