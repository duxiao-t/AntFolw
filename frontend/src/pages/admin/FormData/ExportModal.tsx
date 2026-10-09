import { DownloadOutlined } from '@ant-design/icons';
import { request } from '@umijs/max';
import { App, Button, Input, Modal, Select, Space, Typography } from 'antd';
import { createStyles } from 'antd-style';
import { useEffect, useMemo, useState } from 'react';
import { RANGE_PRESETS, rangeParams, tzOffsetMinutes } from '@/pages/report/Center';
import { blobErrorMessage } from '@/utils/format';

const useStyles = createStyles(({ token }) => ({
  form: { display: 'grid', gap: 12 },
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

/** 「提交数据」表格当前筛选带进来的默认值；打开弹窗时的那一份为准。 */
export type ExportDefaults = {
  formDefId?: number;
  status?: string;
  submitterKeyword?: string;
};

export default function ExportModal({
  defaults,
  onClose,
}: {
  defaults?: ExportDefaults;
  onClose: () => void;
}) {
  const { styles } = useStyles();
  const { message } = App.useApp();
  // 由调用方「开着才渲染」，每次打开都是全新挂载：初始值直接取 props 就好，
  // 不用 useEffect 去同步 defaults（那样会在用户改筛选后被重渲染打回去）。
  const [formDefId, setFormDefId] = useState<number | undefined>(defaults?.formDefId);
  const [status, setStatus] = useState(defaults?.status ?? '');
  const [submitterKeyword, setSubmitterKeyword] = useState(defaults?.submitterKeyword ?? '');
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
    <Modal title="导出数据" open onCancel={onClose} footer={null} width={560}>
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

        <Typography.Text className={styles.note}>
          导出内容与背后列表看到的完全一致（提交人 / 工号 / 部门 + 该表单字段列，取值也是页面显示的那份文本）；导出动作会记一条审计。
        </Typography.Text>

        <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
          <Button type="primary" icon={<DownloadOutlined />} loading={busy}
            disabled={counting || !count || count.total === 0} onClick={() => void download()}>
            {format === 'csv' ? '导出 CSV' : '导出 Excel'}
          </Button>
        </div>
      </div>
    </Modal>
  );
}
