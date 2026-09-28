import { Alert, Empty, Modal, Spin, Typography } from 'antd';
import { createStyles } from 'antd-style';
import { useEffect, useState } from 'react';
import { request } from '@umijs/max';
import type { VersionDiff } from './optionSourceTypes';

const useStyles = createStyles(({ token }) => ({
  columns: { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 },
  pane: {
    border: '1px solid var(--af-color-line)',
    borderRadius: token.borderRadius,
    padding: 8,
    minHeight: 120,
  },
  paneHead: { fontWeight: 600, marginBottom: 6, fontSize: 13 },
  row: {
    padding: '2px 6px',
    borderRadius: token.borderRadiusSM,
    fontSize: 12,
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    wordBreak: 'break-all',
  },
  added: { background: 'var(--af-color-success-soft)' },
  removed: { background: 'var(--af-color-danger-soft)' },
  more: { marginTop: 6, color: 'var(--af-color-muted)', fontSize: 12 },
}));

export type DiffTarget = { versionId: number; versionNo: number; previousVersionNo: number };

/**
 * 版本对比：这一版相对上一版的增删。
 *
 * 按**整行内容**比（不是挑某一列）：选项的"值列"是每个表单字段各自选的，数据源本身
 * 没有主键列，所以改一个标签会表现为"一删一增"——这是诚实的代价，后端注释里写明了。
 */
export function OptionSourceDiffModal({ sourceId, target, onClose }: {
  sourceId: number;
  target: DiffTarget | null;
  onClose(): void;
}) {
  const { styles } = useStyles();
  const [diff, setDiff] = useState<VersionDiff>();
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!target) return;
    let cancelled = false;
    setDiff(undefined);
    setLoading(true);
    request<VersionDiff>(
      `/api/option-sources/${sourceId}/versions/${target.versionId}/diff`,
    )
      .then((result) => { if (!cancelled) setDiff(result); })
      .catch(() => { if (!cancelled) setDiff(undefined); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [sourceId, target]);

  const pane = (
    head: string,
    rows: Array<Record<string, unknown>>,
    total: number,
    kind: 'added' | 'removed',
  ) => (
    <div className={styles.pane}>
      <div className={styles.paneHead}>{`${head} · ${total} 行`}</div>
      {rows.length === 0 ? (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>没有</Typography.Text>
      ) : (
        // key 用整行内容：差异列表是集合差，内容本身就唯一（也和"按整行比"的口径一致）。
        rows.map((row) => (
          <div key={JSON.stringify(row)} className={`${styles.row} ${styles[kind]}`}>
            {Object.entries(row).map(([column, value]) => `${column}=${String(value ?? '')}`).join(' · ')}
          </div>
        ))
      )}
    </div>
  );

  return (
    <Modal
      title={target ? `版本对比 · v${target.previousVersionNo} → v${target.versionNo}` : ''}
      open={!!target}
      onCancel={onClose}
      footer={null}
      width={720}
      destroyOnHidden
    >
      {loading && <Spin />}
      {!loading && diff && (
        <>
          {diff.previousVersionNo == null && (
            <Empty description="这是第一个版本，没有可对比的上一版" />
          )}
          {diff.previousVersionNo != null && (
            <>
              {diff.columnChanges.length > 0 && (
                <Alert
                  type="warning"
                  showIcon
                  style={{ marginBottom: 12 }}
                  message="列结构有变化"
                  description={diff.columnChanges.join('；')}
                />
              )}
              <div className={styles.columns}>
                {pane(`v${diff.previousVersionNo} 有、这一版没有`, diff.removed, diff.removedTotal, 'removed')}
                {pane(`这一版新增`, diff.added, diff.addedTotal, 'added')}
              </div>
              {diff.truncated && (
                <div className={styles.more}>
                  数据源行数超过一次对比的上限，这里只比了前面一部分——先看总数，必要时分次导入核对。
                </div>
              )}
              {!diff.truncated && (diff.addedTotal > diff.added.length || diff.removedTotal > diff.removed.length) && (
                <div className={styles.more}>
                  差异较大，只显示前 {diff.added.length + diff.removed.length} 行明细（已按整行去重）。
                </div>
              )}
            </>
          )}
        </>
      )}
    </Modal>
  );
}
