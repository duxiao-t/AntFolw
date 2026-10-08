import { DownloadOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { request } from '@umijs/max';
import { App, Alert, Button, Input, Select, Space, Typography } from 'antd';
import { createStyles } from 'antd-style';
import { useEffect, useMemo, useState } from 'react';
import { blobErrorMessage } from '@/utils/format';
import { RANGE_PRESETS, rangeParams, tzOffsetMinutes } from './Center';

const useStyles = createStyles(({ token }) => ({
  form: { display: 'grid', gap: 12, maxWidth: 560 },
  row: { display: 'grid', gap: 6 },
  label: { fontSize: 13 },
  note: { color: 'var(--af-color-muted)', fontSize: 12, lineHeight: 1.7 },
  count: {
    padding: '10px 12px',
    border: '1px solid var(--af-color-line)',
    borderRadius: token.borderRadius,
    background: 'var(--af-color-surface)',
    fontVariantNumeric: 'tabular-nums',
  },
  countValue: { fontSize: 20, fontWeight: 600 },
}));

const STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'SUBMITTED', label: '已提交' },
  { value: 'DRAFT', label: '草稿' },
];

/** Excel 放第一：多数人要的是能直接打开、能筛选的表；CSV 是给"喂给别的系统"用的。 */
const FORMAT_OPTIONS = [
  { value: 'xlsx', label: 'Excel（.xlsx）' },
  { value: 'csv', label: 'CSV（.csv）' },
] as const;

type ExportFormat = (typeof FORMAT_OPTIONS)[number]['value'];

export default function ExportPage() {
  const { styles } = useStyles();
  const { message } = App.useApp();
  const [formDefId, setFormDefId] = useState<number>();
  const [status, setStatus] = useState('');
  const [submitterKeyword, setSubmitterKeyword] = useState('');
  const [days, setDays] = useState(30);
  const [format, setFormat] = useState<ExportFormat>('xlsx');
  const [formOptions, setFormOptions] = useState<Array<{ value: number; label: string }>>([]);
  const [count, setCount] = useState<{ total: number; limit: number }>();
  const [counting, setCounting] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();

  const range = useMemo(() => rangeParams(days), [days]);
  const params = useMemo(() => ({
    formDefId,
    status: status || undefined,
    submitterKeyword: submitterKeyword.trim() || undefined,
    from: range.from,
    to: range.to,
  }), [formDefId, status, submitterKeyword, range.from, range.to]);

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

  // 先回答"会导出多少行"：导出是动作页，点下去之前就该知道拿到什么。
  useEffect(() => {
    let cancelled = false;
    setCounting(true);
    // 参数必须与下载一致（含时间范围与 tzOffsetMinutes）：少了时间范围，改了区间之后
    // 预览的仍是旧数字，还会把 truncated 报歪。
    request<{ total: number; limit: number }>('/api/reports/form-data-count', {
      params: { ...params, tzOffsetMinutes: tzOffsetMinutes() },
      skipErrorHandler: true,
    })
      .then((result) => { if (!cancelled) { setCount(result); setError(undefined); } })
      .catch((reason) => { if (!cancelled) setError(reason?.message ?? '无法预估导出范围'); })
      .finally(() => { if (!cancelled) setCounting(false); });
    return () => { cancelled = true; };
  }, [params]);

  const download = async () => {
    setBusy(true);
    try {
      const blob = await request('/api/forms/data/admin/export', {
        params: { ...params, tzOffsetMinutes: tzOffsetMinutes(), format },
        responseType: 'blob',
        skipErrorHandler: true,
      });
      const url = URL.createObjectURL(blob as Blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = `antflow-form-data.${format}`;
      anchor.click();
      URL.revokeObjectURL(url);
      message.success('已开始下载');
    } catch (reason) {
      message.error(await blobErrorMessage(reason));
    } finally {
      setBusy(false);
    }
  };

  const truncated = count != null && count.total > count.limit;

  return (
    <PageContainer subTitle="把表单台账导成 Excel 或 CSV，内容与「表单管理 → 数据」里看到的一致">
      <div className={styles.form}>
        <div className={styles.row}>
          <span className={styles.label}>格式</span>
          <Select value={format} onChange={(value) => setFormat(value)} options={[...FORMAT_OPTIONS]}
            aria-label="格式" style={{ maxWidth: 200 }} />
        </div>
        <div className={styles.row}>
          <span className={styles.label}>表单</span>
          <Select allowClear showSearch placeholder="全部表单" aria-label="表单"
            optionFilterProp="label" value={formDefId} onChange={setFormDefId}
            options={formOptions} style={{ maxWidth: 320 }} />
        </div>
        <div className={styles.row}>
          <span className={styles.label}>状态</span>
          <Select value={status} onChange={setStatus} options={STATUS_OPTIONS}
            aria-label="状态" style={{ maxWidth: 160 }} />
        </div>
        <div className={styles.row}>
          <span className={styles.label}>时间范围（按提交时间）</span>
          <Space>
            <Select value={days} options={RANGE_PRESETS} onChange={setDays}
              aria-label="时间范围" style={{ width: 120 }} />
            <Typography.Text className={styles.note}>
              {`${range.from} 起`}
            </Typography.Text>
          </Space>
        </div>
        <div className={styles.row}>
          <span className={styles.label}>提交人</span>
          <Input allowClear placeholder="姓名或工号，留空为全部" aria-label="提交人"
            value={submitterKeyword} onChange={(event) => setSubmitterKeyword(event.target.value)}
            style={{ maxWidth: 320 }} />
        </div>

        <div className={styles.count}>
          {error ? (
            <Typography.Text type="danger">{error}</Typography.Text>
          ) : counting || count == null ? (
            <Typography.Text className={styles.note}>正在估算…</Typography.Text>
          ) : (
            <>
              <Typography.Text className={styles.note}>按当前条件将导出</Typography.Text>
              <div>
                <span className={styles.countValue}>{Math.min(count.total, count.limit)}</span>
                <Typography.Text className={styles.note}>{` 行（上限 ${count.limit} 行）`}</Typography.Text>
              </div>
              {truncated && (
                <Typography.Text type="warning">
                  {`共有 ${count.total} 行，超过上限只导前 ${count.limit} 行——缩小时间范围或按表单分批导。`}
                </Typography.Text>
              )}
              {count.total === 0 && (
                <Typography.Text className={styles.note}>
                  当前条件没有数据，换个筛选再看看。
                </Typography.Text>
              )}
            </>
          )}
        </div>

        <Button type="primary" icon={<DownloadOutlined />} loading={busy}
          disabled={counting || !count || count.total === 0} onClick={() => void download()}>
          {format === 'csv' ? '导出 CSV' : '导出 Excel'}
        </Button>
      </div>

      <Alert
        type="info"
        showIcon
        style={{ marginTop: 16, maxWidth: 560 }}
        title="导出范围与你在「表单管理 → 数据」里看到的完全一致"
        description="列表显示的提交人 / 工号 / 部门 + 该表单的字段列会一起导出，取值也是页面显示的那份文本（下拉给选项名、检查项给逐条结果）；范围外（以及你没有权限看的）数据不会出现。导出动作会记一条审计。"
      />
    </PageContainer>
  );
}
