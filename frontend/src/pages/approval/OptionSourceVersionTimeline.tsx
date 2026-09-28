import { LinkOutlined } from '@ant-design/icons';
import { Timeline, Tooltip, Typography } from 'antd';
import { createStyles } from 'antd-style';
import dayjs from 'dayjs';
import type { FormRef, VersionView } from './optionSourceTypes';
import { versionStateLabel } from './optionSourceTypes';

const useStyles = createStyles(({ token }) => ({
  card: {
    padding: '10px 12px',
    border: '1px solid var(--af-color-line)',
    borderRadius: token.borderRadius,
    background: 'var(--af-color-surface)',
  },
  cardCurrent: {
    borderColor: token.colorPrimary,
    boxShadow: '0 0 0 2px var(--af-color-primary-soft)',
  },
  head: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
    flexWrap: 'wrap',
  },
  title: { display: 'inline-flex', alignItems: 'center', gap: 6, fontWeight: 600 },
  bar: {
    display: 'inline-block',
    width: 3,
    height: 13,
    borderRadius: 2,
    background: token.colorPrimary,
  },
  state: {
    display: 'inline-flex',
    alignItems: 'center',
    gap: 4,
    color: 'var(--af-color-muted)',
    fontSize: 12,
  },
  dot: {
    width: 6,
    height: 6,
    borderRadius: '50%',
    background: token.colorSuccess,
  },
  dotDraft: { background: token.colorWarning },
  dotOff: { background: token.colorTextQuaternary },
  meta: {
    marginTop: 4,
    color: 'var(--af-color-muted)',
    fontSize: 12,
    fontVariantNumeric: 'tabular-nums',
  },
  note: { marginTop: 6, fontSize: 12, color: 'var(--af-color-text-secondary)' },
  tagLine: { marginTop: 6, display: 'flex', flexWrap: 'wrap', gap: 4, alignItems: 'center' },
  tag: {
    display: 'inline-flex',
    alignItems: 'center',
    gap: 4,
    padding: '0 6px',
    border: '1px solid var(--af-color-border)',
    borderRadius: token.borderRadiusSM,
    background: 'var(--af-color-bg)',
    fontSize: 12,
    lineHeight: '20px',
  },
  actions: { display: 'flex', gap: 2, flexWrap: 'wrap' },
  action: {
    padding: '0 6px',
    border: 0,
    background: 'transparent',
    color: token.colorPrimary,
    cursor: 'pointer',
    font: 'inherit',
    fontSize: 12,
    borderRadius: token.borderRadiusSM,
    '&:hover': { background: token.colorFillTertiary },
    '&:disabled': { color: token.colorTextQuaternary, cursor: 'not-allowed' },
  },
  actionDanger: { color: 'var(--af-color-danger)' },
}));

type Props = {
  versions: VersionView[];
  /** 「最新」= 已发布且未停用里版本号最大的那个（与后端 list() 同口径）。 */
  latestVersionNo?: number | null;
  /** 每个版本在用的表单；**只能**用它判"谁在用"，不能拿源级引用清单分摊。 */
  usageByVersion: Map<number, FormRef[]>;
  busy?: boolean;
  onPublish(version: VersionView): void;
  onDiscard(version: VersionView): void;
  onUnpublish(version: VersionView): void;
  onToggleDisabled(version: VersionView): void;
  onShowRows(version: VersionView): void;
  onDiff(version: VersionView, previousVersionNo: number): void;
};

function stamp(version: VersionView): string {
  const at = version.publishedAt ?? version.createdAt;
  return at ? dayjs(at).format('YYYY-MM-DD HH:mm') : '—';
}

/**
 * 版本历史：每版一张卡（demo 的信息层级）。
 *
 * 卡片里能写下一整句的原因说明——原来挤在表格的 Tooltip 里，引用一多就没人看得到。
 */
export function OptionSourceVersionTimeline({
  versions,
  latestVersionNo,
  usageByVersion,
  busy,
  onPublish,
  onDiscard,
  onUnpublish,
  onToggleDisabled,
  onShowRows,
  onDiff,
}: Props) {
  const { styles, cx } = useStyles();
  if (!versions.length) {
    return (
      <Typography.Text type="secondary">
        还没有任何版本。点「导入新版本」把文本或表格导进来。
      </Typography.Text>
    );
  }
  return (
    <Timeline
      items={versions.map((version, index) => {
        const previous = versions[index + 1];
        const isLatest = version.versionNo === latestVersionNo;
        const state = versionStateLabel(version);
        const used = usageByVersion.get(version.id) ?? [];
        const action = (label: string, onClick: () => void, danger = false, title?: string) => (
          <Tooltip key={label} title={title}>
            <button
              type="button"
              className={cx(styles.action, danger && styles.actionDanger)}
              disabled={busy}
              onClick={onClick}
            >
              {label}
            </button>
          </Tooltip>
        );
        return {
          key: version.id,
          color: version.status === 'DRAFT' ? 'gray' : 'blue',
          content: (
            <div className={cx(styles.card, isLatest && styles.cardCurrent)}>
              <div className={styles.head}>
                <span className={styles.title}>
                  {isLatest && <span className={styles.bar} aria-hidden />}
                  {`v${version.versionNo}`}
                  {isLatest && <Typography.Text type="secondary">最新</Typography.Text>}
                </span>
                <span className={styles.actions}>
                  {version.status === 'DRAFT' ? (
                    <>
                      {action('发布', () => onPublish(version))}
                      {action('丢弃', () => onDiscard(version), true,
                        '连同行数据一起删掉，无法恢复')}
                    </>
                  ) : (
                    <>
                      {previous &&
                        action(`对比 v${previous.versionNo}`, () => onDiff(version, previous.versionNo))}
                      {action('查看数据', () => onShowRows(version))}
                      {action(version.disabledAt ? '启用' : '停用', () => onToggleDisabled(version),
                        false,
                        version.disabledAt
                          ? '重新参与新绑定（读路径一直不受影响）'
                          : '不再被新绑定挑到；已在用它的表单照常读取')}
                      {action('取消发布', () => onUnpublish(version), true,
                        '退回待发布；会让钉着它的表单发布失败，所以没人钉着才允许')}
                    </>
                  )}
                </span>
              </div>
              <div className={styles.meta}>
                <span className={styles.state}>
                  <span
                    className={cx(styles.dot, version.status === 'DRAFT' && styles.dotDraft,
                      version.disabledAt ? styles.dotOff : undefined)}
                    aria-hidden
                  />
                  {state}
                </span>
                {` · ${stamp(version)}`}
                {version.publishedByName ? ` · ${version.publishedByName}` : ''}
                {` · 记录 ${version.rowCount} 行`}
                {version.originalName ? ` · ${version.originalName}` : ''}
              </div>
              <div className={styles.note}>
                变更说明：{version.note || '—'}
              </div>
              <div className={styles.tagLine}>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  在用的表单：
                </Typography.Text>
                {used.length === 0 && (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>未使用</Typography.Text>
                )}
                {used.map((form) => (
                  <Tooltip key={form.id} title={`${form.name} · ${form.code}`}>
                    <span className={styles.tag}>
                      <LinkOutlined />
                      {form.name}
                    </span>
                  </Tooltip>
                ))}
              </div>
            </div>
          ),
        };
      })}
    />
  );
}
