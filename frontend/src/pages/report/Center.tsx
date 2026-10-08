import { PageContainer } from '@ant-design/pro-components';
import { request } from '@umijs/max';
import { Alert, Empty, Select, Table, Typography } from 'antd';
import { createStyles } from 'antd-style';
import dayjs from 'dayjs';
import { useEffect, useMemo, useState } from 'react';

/** /api/reports/approval-summary 的形状；看板取的是同一份，所以类型放这里共用。 */
export type ReportCounts = {
  started: number;
  approved: number;
  rejected: number;
  withdrawn: number;
  running: number;
  other: number;
  /** 通过率 = 通过 / (通过 + 驳回)；没有已决记录时为 null（显示 —，不是 0%）。 */
  approvalRate: number | null;
  avgDurationHours: number | null;
};

export type ApprovalSummary = {
  totals: ReportCounts;
  byForm: Array<ReportCounts & { formDefId: number; formName: string | null }>;
  byDepartment: Array<ReportCounts & { deptId: number | null; deptName: string | null }>;
  byDay: Array<{ date: string; started: number; finished: number }>;
};

export const RANGE_PRESETS = [
  { label: '近 7 天', value: 7 },
  { label: '近 30 天', value: 30 },
  { label: '近 90 天', value: 90 },
];

/** 时间范围（含首尾）。报表一律带客户端时区：库里按 UTC 存，不换算"今天"会比用户看到的早 8 小时。 */
export function rangeParams(days: number, today = dayjs()): { from: string; to: string } {
  return {
    from: today.subtract(days - 1, 'day').format('YYYY-MM-DD'),
    to: today.format('YYYY-MM-DD'),
  };
}

export const tzOffsetMinutes = () => -new Date().getTimezoneOffset();

const useStyles = createStyles(({ token }) => ({
  filterBar: {
    display: 'flex',
    alignItems: 'center',
    gap: 12,
    flexWrap: 'wrap',
    marginBottom: 12,
  },
  note: { color: 'var(--af-color-muted)', fontSize: 12 },
  // 一块横条仪表盘，而不是四张同款卡片：竖分隔线让数字排成一列，比较时一行扫过去。
  totals: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))',
    border: '1px solid var(--af-color-line)',
    borderRadius: token.borderRadius,
    background: 'var(--af-color-surface)',
    marginBottom: 16,
  },
  cell: {
    padding: '12px 16px',
    borderLeft: '1px solid var(--af-color-line)',
    '&:first-child': { borderLeft: 'none' },
  },
  cellLabel: { color: 'var(--af-color-muted)', fontSize: 12 },
  cellValue: {
    fontSize: 24,
    fontWeight: 600,
    lineHeight: 1.3,
    color: 'var(--af-color-text)',
    fontVariantNumeric: 'tabular-nums',
  },
  cellUnit: { fontSize: 13, fontWeight: 400, color: 'var(--af-color-muted)', marginLeft: 4 },
  sectionTitle: { fontWeight: 600, fontSize: 13, margin: '4px 0 8px' },
  // 通过率不另画图：比较关系交给单元格里的一条横条，省掉第二套视觉系统。
  rateCell: { position: 'relative', padding: '2px 6px' },
  rateBar: {
    position: 'absolute',
    top: 2,
    bottom: 2,
    left: 0,
    background: 'var(--af-color-primary-soft)',
    borderRadius: token.borderRadiusSM,
  },
  rateText: { position: 'relative', fontVariantNumeric: 'tabular-nums' },
  muted: { color: 'var(--af-color-muted)' },
}));

export function RateCell({ rate }: { rate: number | null }) {
  const { styles } = useStyles();
  if (rate == null) return <span className={styles.muted}>—</span>;
  return (
    <div className={styles.rateCell}>
      <span className={styles.rateBar} style={{ width: `${Math.min(100, Math.max(0, rate))}%` }} aria-hidden />
      <span className={styles.rateText}>{`${rate.toFixed(1)}%`}</span>
    </div>
  );
}

export default function ReportCenterPage() {
  const { styles } = useStyles();
  const [days, setDays] = useState(30);
  const [formDefIds, setFormDefIds] = useState<number[]>([]);
  const [formOptions, setFormOptions] = useState<Array<{ value: number; label: string }>>([]);
  const [summary, setSummary] = useState<ApprovalSummary>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();

  const range = useMemo(() => rangeParams(days), [days]);

  // 表单候选是可选增强：没有 form:definition:read 的账号取不到，不该因此打不开报表。
  useEffect(() => {
    request<{ records?: Array<{ id: number; name: string }> }>('/api/forms/definitions', {
      params: { page: 1, size: 100 },
      skipErrorHandler: true,
    })
      .then((page) => setFormOptions((page.records ?? []).map((form) => ({
        value: form.id, label: form.name,
      }))))
      .catch(() => setFormOptions([]));
  }, []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    request<ApprovalSummary>('/api/reports/approval-summary', {
      params: {
        from: range.from,
        to: range.to,
        formDefIds: formDefIds.length ? formDefIds.join(',') : undefined,
        tzOffsetMinutes: tzOffsetMinutes(),
      },
      skipErrorHandler: true,
    })
      .then((data) => {
        if (cancelled) return;
        setSummary(data);
        setError(undefined);
      })
      .catch((reason) => {
        if (!cancelled) setError(reason?.message ?? '报表加载失败');
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [range.from, range.to, formDefIds]);

  const totals = summary?.totals;
  const formRows = (summary?.byForm ?? []).map((row) => ({
    key: `form-${row.formDefId}`, name: row.formName ?? `表单 #${row.formDefId}`, ...row,
  }));
  const deptRows = (summary?.byDepartment ?? []).map((row) => ({
    key: `dept-${row.deptId ?? 'none'}`, name: row.deptName ?? '未记录部门', ...row,
  }));

  const columns = useMemo(() => [
    { title: '名称', dataIndex: 'name', ellipsis: true },
    { title: '提交', dataIndex: 'started', width: 80, align: 'right' as const },
    { title: '通过', dataIndex: 'approved', width: 80, align: 'right' as const },
    { title: '驳回', dataIndex: 'rejected', width: 80, align: 'right' as const },
    { title: '进行中', dataIndex: 'running', width: 90, align: 'right' as const },
    { title: '通过率', dataIndex: 'approvalRate', width: 140,
      render: (rate: number | null) => <RateCell rate={rate} /> },
    { title: '平均耗时', dataIndex: 'avgDurationHours', width: 100, align: 'right' as const,
      render: (value: number | null) => (value == null
        ? <span className={styles.muted}>—</span>
        : <span style={{ fontVariantNumeric: 'tabular-nums' }}>{`${value}h`}</span>) },
  ], [styles]);

  const table = (data: Array<Record<string, unknown>>, empty: string) => (
    <Table size="small" rowKey="key" columns={columns} dataSource={data} pagination={false}
      locale={{ emptyText: <span className={styles.muted}>{empty}</span> }} />
  );

  const cells = [
    { label: '提交', value: totals?.started, unit: '件' },
    { label: '通过', value: totals?.approved, unit: '件' },
    { label: '驳回', value: totals?.rejected, unit: '件' },
    { label: '进行中', value: totals?.running, unit: '件' },
    { label: '撤回 / 其它', value: totals ? totals.withdrawn + totals.other : undefined, unit: '件' },
    { label: '平均审批耗时', value: totals?.avgDurationHours, unit: '小时' },
  ];

  return (
    <PageContainer subTitle="按发起时间统计你可见范围内的流程数据">
      <div className={styles.filterBar}>
        <Select value={days} options={RANGE_PRESETS} onChange={setDays} style={{ width: 120 }}
          aria-label="时间范围" />
        <Select mode="multiple" allowClear placeholder="全部表单（可多选）" style={{ minWidth: 260 }}
          aria-label="表单筛选" value={formDefIds} onChange={setFormDefIds}
          options={formOptions} maxTagCount={2} />
        <span className={styles.note}>
          按发起时间归期、状态取当前状态；通过率 = 通过 ÷ （通过 + 驳回）；平均耗时只算已完成的。
        </span>
      </div>

      {error && <Alert type="error" showIcon title={error} style={{ marginBottom: 12 }} />}

      <div className={styles.totals}>
        {cells.map((item) => (
          <div className={styles.cell} key={item.label}>
            <div className={styles.cellLabel}>{item.label}</div>
            <div className={styles.cellValue}>
              {loading || item.value == null ? '—' : item.value}
              {!loading && item.value != null && <span className={styles.cellUnit}>{item.unit}</span>}
            </div>
          </div>
        ))}
      </div>

      {!loading && totals?.started === 0 ? (
        <Empty description="这段时间没有流程数据。换个时间范围，或确认表单筛选。" />
      ) : (
        <>
          <div className={styles.sectionTitle}>按表单</div>
          {table(formRows, '这段时间没有表单数据')}
          <div className={styles.sectionTitle} style={{ marginTop: 16 }}>按部门</div>
          {table(deptRows, '这段时间没有部门数据')}
        </>
      )}

      <Typography.Paragraph className={styles.note} style={{ marginTop: 12, marginBottom: 0 }}>
        统计范围：你可见的数据（管理员为全量）。部门按发起部门快照归集，没有部门的归入「未记录部门」。
      </Typography.Paragraph>
    </PageContainer>
  );
}
