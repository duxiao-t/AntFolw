import { Column, Line, Pie } from '@ant-design/plots';
import { PageContainer } from '@ant-design/pro-components';
import { request } from '@umijs/max';
import { Alert, Empty, Select, Typography } from 'antd';
import { createStyles } from 'antd-style';
import { useEffect, useMemo, useState } from 'react';
import { type ApprovalSummary, RANGE_PRESETS, rangeParams, tzOffsetMinutes } from './Center';

const useStyles = createStyles(({ token }) => ({
  filterBar: {
    display: 'flex',
    alignItems: 'center',
    gap: 12,
    flexWrap: 'wrap',
    marginBottom: 12,
  },
  note: { color: 'var(--af-color-muted)', fontSize: 12 },
  // 趋势是这页唯一的大块，两个对比退成同一行的小面板。
  hero: {
    padding: '8px 12px 0',
    border: '1px solid var(--af-color-line)',
    borderRadius: token.borderRadius,
    background: 'var(--af-color-surface)',
    marginBottom: 12,
  },
  pair: { display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: 12 },
  panel: {
    padding: '8px 12px 0',
    border: '1px solid var(--af-color-line)',
    borderRadius: token.borderRadius,
    background: 'var(--af-color-surface)',
  },
  title: { fontWeight: 600, fontSize: 13, paddingTop: 8 },
  empty: { padding: '40px 0' },
}));

/** 配色只用仓库主色 + 一个固定小调色板，不引入新的视觉体系。 */
const SERIES_COLORS = ['#0b57d0', '#0f8a5f'];
const PALETTE = ['#0b57d0', '#0f8a5f', '#f0a020', '#5a6fa8', '#c0392b', '#8c8c8c'];

export default function ReportDashboardPage() {
  const { styles } = useStyles();
  const [days, setDays] = useState(30);
  const [formDefIds, setFormDefIds] = useState<number[]>([]);
  const [formOptions, setFormOptions] = useState<Array<{ value: number; label: string }>>([]);
  const [summary, setSummary] = useState<ApprovalSummary>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();

  const range = useMemo(() => rangeParams(days), [days]);

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
        if (!cancelled) setError(reason?.message ?? '看板加载失败');
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [range.from, range.to, formDefIds]);

  // 折线的数据是长表：一天两条（发起 / 完成），图例自己就说明了图在讲什么。
  const trend = useMemo(() => (summary?.byDay ?? []).flatMap((day) => [
    { date: day.date.slice(5), type: '发起', value: day.started },
    { date: day.date.slice(5), type: '完成', value: day.finished },
  ]), [summary?.byDay]);

  const byDept = useMemo(() => (summary?.byDepartment ?? [])
    .filter((row) => row.approvalRate != null)
    .map((row) => ({
      name: row.deptName ?? '未记录部门', rate: row.approvalRate as number, started: row.started,
    })), [summary?.byDepartment]);

  const byForm = useMemo(() => (summary?.byForm ?? [])
    .map((row) => ({ type: row.formName ?? `表单 #${row.formDefId}`, value: row.started })),
  [summary?.byForm]);

  const hasData = (summary?.totals.started ?? 0) > 0;

  return (
    <PageContainer subTitle="同一份统计的图形视图：一页看数，一页看形">
      <div className={styles.filterBar}>
        <Select value={days} options={RANGE_PRESETS} onChange={setDays} style={{ width: 120 }}
          aria-label="时间范围" />
        <Select mode="multiple" allowClear placeholder="全部表单（可多选）" style={{ minWidth: 260 }}
          aria-label="表单筛选" value={formDefIds} onChange={setFormDefIds}
          options={formOptions} maxTagCount={2} />
        <span className={styles.note}>数字口径与报表中心完全一致（同一个接口、同一组筛选）。</span>
      </div>

      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} />}

      {loading ? null : !hasData ? (
        <Empty className={styles.empty} description="这段时间没有流程数据，换个时间范围再看看。" />
      ) : (
        <>
          <div className={styles.hero}>
            <div className={styles.title}>发起 / 完成趋势</div>
            <Line
              height={260}
              data={trend}
              xField="date"
              yField="value"
              seriesField="type"
              color={SERIES_COLORS}
              animate={false}
              legend={{ position: 'top' }}
              axis={{ y: { title: false }, x: { title: false } }}
            />
          </div>
          <div className={styles.pair}>
            <div className={styles.panel}>
              <div className={styles.title}>部门通过率</div>
              {byDept.length === 0 ? (
                <Empty className={styles.empty} image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="这段时间还没有已决的流程" />
              ) : (
                <Column
                  height={220}
                  data={byDept}
                  xField="name"
                  yField="rate"
                  color={PALETTE[0]}
                  animate={false}
                  axis={{ y: { title: false }, x: { title: false } }}
                />
              )}
            </div>
            <div className={styles.panel}>
              <div className={styles.title}>表单提交占比</div>
              {byForm.length === 0 ? (
                <Empty className={styles.empty} image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="这段时间还没有提交" />
              ) : (
                <Pie
                  height={220}
                  data={byForm}
                  angleField="value"
                  colorField="type"
                  color={PALETTE}
                  innerRadius={0.6}
                  animate={false}
                  legend={{ position: 'bottom' }}
                />
              )}
            </div>
          </div>
        </>
      )}

      <Typography.Paragraph className={styles.note} style={{ marginTop: 12, marginBottom: 0 }}>
        统计范围：你可见的数据（管理员为全量）。
      </Typography.Paragraph>
    </PageContainer>
  );
}
