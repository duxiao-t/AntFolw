import { Alert, Modal, Table, Typography } from 'antd';
import { useMemo } from 'react';
import type { SourceDetail } from './optionSourceTypes';

export type LifecycleAction = 'disable' | 'delete';

type Row = { key: string; relation: string; name: string; code: string; version: string };

/**
 * 停用 / 删除数据源：先把"谁在用"摊开，再说清后果。
 *
 * 两道口径上的讲究：
 * 1. **能不能删以后端为准**（`deletable` / `deleteBlockedReason`）。
 *    `publishedVersionId` 为空不代表可删——全部下架时「最新」是 null，但它发布过，delete() 仍会拒。
 *    前端不许按"列出来的引用条数"自己放行。
 * 2. **源停用 ≠ 版本停用**，文案必须分开：版本停用只把版本从新候选里移除，读路径完全不受影响；
 *    源停用会让"那张表单再次发布"直接失败（发布要求源处于启用状态）。
 */
export function OptionSourceLifecycleModal({ detail, action, busy, onCancel, onConfirm }: {
  detail: SourceDetail | null;
  action: LifecycleAction | null;
  busy?: boolean;
  onCancel(): void;
  onConfirm(action: LifecycleAction): void;
}) {
  const rows = useMemo<Row[]>(() => {
    if (!detail) return [];
    const seen = new Set<number>();
    const used = detail.versionUsage.flatMap((usage) => usage.forms.map((form) => {
      seen.add(form.id);
      return {
        key: `used-${form.id}-${usage.versionId}`,
        relation: '在用（字段绑着）',
        name: form.name,
        code: form.code,
        version: `v${detail.versions.find((version) => version.id === usage.versionId)?.versionNo ?? '?'}`,
      };
    }));
    const referenced = detail.forms.filter((form) => !seen.has(form.id)).map((form) => ({
      key: `ref-${form.id}`,
      relation: '引用（可撤销）',
      name: form.name,
      code: form.code,
      version: '—',
    }));
    return [...used, ...referenced];
  }, [detail]);

  const deleting = action === 'delete';
  const blocked = deleting && detail != null && !detail.deletable;

  return (
    <Modal
      title={deleting ? `删除数据源：${detail?.source.name ?? ''}` : `停用数据源：${detail?.source.name ?? ''}`}
      open={action != null}
      onCancel={onCancel}
      onOk={() => action && onConfirm(action)}
      okText={deleting ? '确认删除' : '确认停用'}
      okButtonProps={{ danger: deleting, disabled: blocked, loading: busy }}
      cancelText="取消"
      width={640}
      destroyOnHidden
    >
      {detail && (
        <>
          {deleting ? (
            <Alert
              type={detail.deletable ? 'warning' : 'error'}
              showIcon
              style={{ marginBottom: 12 }}
              message={detail.deletable
                ? '它没有已发布版本、也没有表单在用它，可以删除'
                : `不能删除：${detail.deleteBlockedReason ?? '仍被引用'}`}
              description={detail.deletable
                ? '删除后无法恢复。想留个退路可以改为「停用」。'
                : '想让它不再被新表单选用，请改用「停用」。'}
            />
          ) : (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 12 }}
              message="停用后不再出现在新绑定的候选里"
              description="已经绑着它的表单照常填报、历史记录照常回显；但那张表单**再次发布**会被拒（发布要求数据源处于启用状态），要先在这里启用。这与「停用某个版本」不同——版本停用只从新候选里移除，读路径完全不受影响。"
            />
          )}
          <Typography.Text strong style={{ fontSize: 13 }}>
            谁和它有关
          </Typography.Text>
          <Table<Row>
            size="small"
            rowKey="key"
            style={{ marginTop: 8 }}
            pagination={false}
            dataSource={rows}
            locale={{ emptyText: '没有表单在用它，也没有引用它的表单' }}
            columns={[
              { title: '关系', dataIndex: 'relation', width: 150 },
              {
                title: '表单', dataIndex: 'name',
                render: (name: string, row) => (
                  <span>
                    {name}
                    <Typography.Text type="secondary" style={{ marginLeft: 6, fontSize: 12 }}>
                      {row.code}
                    </Typography.Text>
                  </span>
                ),
              },
              { title: '绑定版本', dataIndex: 'version', width: 100 },
            ]}
          />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            「在用」是字段真的绑着它，撤掉源侧引用也不受影响；「引用」是源侧那份清单，
            只决定别的表单能不能新建绑定。
          </Typography.Text>
        </>
      )}
    </Modal>
  );
}
