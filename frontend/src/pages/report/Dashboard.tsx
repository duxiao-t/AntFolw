import { Column, Line, Pie } from '@ant-design/plots';
import { PageContainer } from '@ant-design/pro-components';
import { request } from '@umijs/max';
import { Alert, Empty, Select, Typography } from 'antd';
import { createStyles } from 'antd-style';
import { useEffect, useMemo, useRef, useState } from 'react';
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
    // overflow: hidden 是必要的，不只是美观：G2 画出来的 canvas 一开始比容器宽，
    // 不裁的话它会反撑容器 → ResizeObserver 量到的是被撑大的宽度 → 永远缩不回来。
    // 网格子项还要 min-width: 0，否则 min-content 同样把面板撑开。
    minWidth: 0,
    overflow: 'hidden',
  },
  pair: { display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: 12 },
  panel: {
    padding: '8px 12px 0',
    border: '1px solid var(--af-color-line)',
    borderRadius: token.borderRadius,
    background: 'var(--af-color-surface)',
    minWidth: 0,
    overflow: 'hidden',
  },
  title: { fontWeight: 600, fontSize: 13, paddingTop: 8 },
  empty: { padding: '40px 0' },
}));

/** 配色只用仓库主色 + 一个固定小调色板，不引入新的视觉体系。 */
const SERIES_COLORS = ['#0b57d0', '#0f8a5f'];
const PALETTE = ['#0b57d0', '#0f8a5f', '#f0a020', '#5a6fa8', '#c0392b', '#8c8c8c'];

/**
 * 把容器的实际宽度喂给图表。
 *
 * <p>G2 只在挂载时量一次容器：布局后来又变窄（侧栏/滚动条出现），canvas 还按旧宽度画，
 * 于是整页多出一条横向滚动条。这里用 ResizeObserver 跟着容器走。
 */
function useChartWidth<T extends HTMLElement>(mounted: boolean) {
  const ref = useRef<T | null>(null);
  const [width, setWidth] = useState(0);
  useEffect(() => {
    // mounted 是必要的：容器在"加载中"时还不存在，只跑一次的 effect 会拿到 null 的 ref，
    // 之后就再也不量了（图表永远拿不到宽度）。
    const element = ref.current;
    if (!mounted || !element) return;
    setWidth(element.clientWidth);
    const observer = new ResizeObserver((entries) => {
      const next = entries[0]?.contentRect.width ?? 0;
      if (next > 0) setWidth(Math.floor(next));
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [mounted]);
  return [ref, width] as const;
}

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
  const chartsMounted = !loading && hasData;
  const [heroRef, heroWidth] = useChartWidth<HTMLDivElement>(chartsMounted);
  const [deptRef, deptWidth] = useChartWidth<HTMLDivElement>(chartsMounted);
  const [formRef, formWidth] = useChartWidth<HTMLDivElement>(chartsMounted);

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
          <div className={styles.hero} ref={heroRef}>
            <div className={styles.title}>发起 / 完成趋势</div>
            {/* 等量到宽度再挂载，并用 key 跟着宽度重建：plots 挂载后不再读 width 变化，
                而挂载那一刻布局可能还没稳定（侧栏未渲染 → 量出偏宽的容器）。 */}
            {heroWidth > 0 && (
              <Line
                key={`hero-${heroWidth}`}
                height={260}
                autoFit={false}
                width={heroWidth}
                data={trend}
                xField="date"
                yField="value"
                seriesField="type"
                color={SERIES_COLORS}
                animate={false}
                legend={{ position: 'top' }}
                axis={{ y: { title: false }, x: { title: false } }}
              />
            )}
          </div>
          <div className={styles.pair}>
            <div className={styles.panel} ref={deptRef}>
              <div className={styles.title}>部门通过率</div>
              {byDept.length === 0 ? (
                <Empty className={styles.empty} image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="这段时间还没有已决的流程" />
              ) : (
                <Column
                  key={`dept-${deptWidth}`}
                  height={220}
                  autoFit={false}
                  width={deptWidth || undefined}
                  data={byDept}
                  xField="name"
                  yField="rate"
                  color={PALETTE[0]}
                  animate={false}
                  axis={{ y: { title: false }, x: { title: false } }}
                />
              )}
            </div>
            <div className={styles.panel} ref={formRef}>
              <div className={styles.title}>表单提交占比</div>
              {byForm.length === 0 ? (
                <Empty className={styles.empty} image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="这段时间还没有提交" />
              ) : (
                <Pie
                  key={`form-${formWidth}`}
                  height={220}
                  autoFit={false}
                  width={formWidth || undefined}
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
