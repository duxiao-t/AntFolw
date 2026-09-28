import { MoreOutlined, PlusOutlined } from '@ant-design/icons';
import { App, Button, Drawer, Dropdown, Empty, Select, Spin, Typography } from 'antd';
import type { MenuProps } from 'antd';
import { createStyles } from 'antd-style';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { request } from '@umijs/max';
import { AssigneePicker } from '../../components/AssigneePicker';
import { OptionSourceImportModal } from './OptionSourceImportModal';
import { OptionSourceLifecycleModal, type LifecycleAction } from './OptionSourceLifecycleModal';
import { OptionSourceRowsDrawer } from './OptionSourceRowsDrawer';
import { OptionSourceVersionTimeline } from './OptionSourceVersionTimeline';
import { OptionSourceDiffModal, type DiffTarget } from './OptionSourceDiffModal';
import type { FormRef, SourceDetail, VersionView } from './optionSourceTypes';

const useStyles = createStyles(() => ({
  head: { display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12 },
  headTitle: { display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' },
  headName: { margin: 0, fontSize: 16, fontWeight: 600 },
  meta: { color: 'var(--af-color-muted)', fontSize: 12, fontVariantNumeric: 'tabular-nums' },
  section: {
    marginTop: 16,
    paddingTop: 12,
    borderTop: '1px solid var(--af-color-line)',
  },
  sectionHead: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
    marginBottom: 10,
  },
  field: { display: 'flex', flexDirection: 'column', gap: 6, marginBottom: 14 },
  fieldRow: { display: 'flex', alignItems: 'flex-start', gap: 10, flexWrap: 'wrap' },
  hint: { color: 'var(--af-color-muted)', fontSize: 12 },
  warn: { color: 'var(--af-color-warning)', fontSize: 12, lineHeight: 1.5 },
  note: { color: 'var(--af-color-muted)', fontSize: 12, lineHeight: 1.6 },
  center: { display: 'flex', justifyContent: 'center', padding: '32px 0' },
  timeline: { marginTop: 4 },
  timelineTitle: { fontWeight: 600, fontSize: 13, marginBottom: 8 },
}));

/**
 * 一个数据源的详情抽屉：版本历史 + 引用与权限 + 它的所有操作。
 *
 * 归属问题靠**结构**解决而不是靠序号补丁：父级用 `key={sourceId}` 渲染本组件，
 * 换源即卸载重建——在途的详情/搜索请求属于被卸载的那个实例，setState 落不到新源上，
 * 也不会把界面切回上一个源（原来 `run()` 里捕获的旧 id 会这么干）。
 */
export function OptionSourceDrawer({ sourceId, intent = 'versions', onClose, onListChanged }: {
  sourceId: number;
  /** 列表页行内操作直接打开的那一块：版本历史 / 导入新版本 / 删除确认。 */
  intent?: 'versions' | 'import' | 'delete';
  onClose(): void;
  onListChanged(): void;
}) {
  const { styles } = useStyles();
  const { message, modal } = App.useApp();
  const [open, setOpen] = useState(true);
  const [detail, setDetail] = useState<SourceDetail>();
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [users, setUsers] = useState<number[]>([]);
  const [roles, setRoles] = useState<number[]>([]);
  const [formIds, setFormIds] = useState<number[]>([]);
  const [formOptions, setFormOptions] = useState<FormRef[]>([]);
  const [importOpen, setImportOpen] = useState(intent === 'import');
  const [rowsVersion, setRowsVersion] = useState<VersionView | null>(null);
  const [diffTarget, setDiffTarget] = useState<DiffTarget | null>(null);
  const [lifecycle, setLifecycle] = useState<LifecycleAction | null>(
    intent === 'delete' ? 'delete' : null);

  const alive = useRef(true);
  useEffect(() => () => { alive.current = false; }, []);

  // 刷新只更新**这一个**源的详情：写成功之后调用，失败单独提示（不能报成"操作失败"，
  // 那会让人以为写没生效、再点一次）。
  const reload = useCallback(async () => {
    try {
      const next = await request<SourceDetail>(`/api/option-sources/${sourceId}`);
      if (!alive.current) return;
      setDetail(next);
      setUsers(next.userIds);
      setRoles(next.roleIds);
      setFormIds(next.forms.map((form) => form.id));
    } catch (error: any) {
      if (alive.current) message.error(error?.message ?? '详情刷新失败，请重试');
    } finally {
      if (alive.current) setLoading(false);
    }
  }, [sourceId, message]);

  useEffect(() => { void reload(); }, [reload]);

  // 表单多选要能搜索：默认一份，输入时按关键字远程查。
  const formSearchSeq = useRef(0);
  const searchForms = async (word?: string) => {
    const seq = ++formSearchSeq.current;
    setFormOptions([]);
    try {
      const page = await request<{ records?: FormRef[] }>('/api/forms/definitions', {
        params: { page: 1, size: 50, keyword: word },
      });
      if (alive.current && seq === formSearchSeq.current) setFormOptions(page.records ?? []);
    } catch (error: any) {
      if (alive.current && seq === formSearchSeq.current) message.error(error?.message ?? '无法加载表单');
    }
  };
  useEffect(() => { void searchForms(); }, []);

  // 已引用的表单必须始终有名字：候选只有前 50 条或当前搜索词，缺了就会显示裸 ID。
  const formChoices = useMemo(() => {
    const merged = new Map<number, FormRef>();
    for (const form of detail?.forms ?? []) merged.set(form.id, form);
    for (const form of formOptions) merged.set(form.id, form);
    return [...merged.values()].map((form) => ({ value: form.id, label: `${form.name} · ${form.code}` }));
  }, [detail?.forms, formOptions]);

  const usageByVersion = useMemo(() => {
    const map = new Map<number, FormRef[]>();
    for (const usage of detail?.versionUsage ?? []) map.set(usage.versionId, usage.forms);
    return map;
  }, [detail?.versionUsage]);

  const run = async (action: () => Promise<unknown>, success: string, closeAfter = false) => {
    setBusy(true);
    try {
      await action();
    } catch (error: any) {
      message.error(error?.message ?? '操作失败');
      setBusy(false);
      return;
    }
    setBusy(false);
    message.success(success);
    onListChanged();
    if (closeAfter) {
      setOpen(false);
      return;
    }
    await reload();
  };

  const sourceAction = (path: string, success: string, closeAfter = false) => () =>
    void run(async () => {
      await request(`/api/option-sources/${sourceId}/${path}`, { method: 'POST' });
    }, success, closeAfter);

  const versionAction = (version: VersionView, path: string, success: string) => () =>
    void run(async () => {
      await request(`/api/option-sources/${sourceId}/versions/${version.id}/${path}`, { method: 'POST' });
    }, success);

  const discardVersion = (version: VersionView) => {
    modal.confirm({
      title: `丢弃 v${version.versionNo}？`,
      content: `这一版还没发布过，丢弃会连着它导入的 ${version.rowCount} 行数据一起删掉，无法恢复。`,
      okText: '丢弃', okButtonProps: { danger: true }, cancelText: '取消',
      onOk: () => run(async () => {
        await request(`/api/option-sources/${sourceId}/versions/${version.id}`, { method: 'DELETE' });
      }, `v${version.versionNo} 已丢弃`),
    });
  };

  const source = detail?.source;
  // 删除一律走弹窗：能不能删、为什么、还牵扯谁，都在那一个地方说清（不在这里摆一行置灰的理由，
  // 那样用户只看到"不行"，看不到全貌，也没法顺着往下处理）。
  const moreItems: MenuProps['items'] = source ? [
    { key: 'toggle-status', label: source.status === 'ACTIVE' ? '停用…' : '启用' },
    { type: 'divider' },
    { key: 'delete', danger: true, label: '删除…' },
  ] : [];

  const onMore = ({ key }: { key: string }) => {
    if (!source) return;
    if (key === 'toggle-status') {
      if (source.status === 'ACTIVE') { setLifecycle('disable'); return; }
      sourceAction('enable', '已启用')();
      return;
    }
    if (key === 'delete') setLifecycle('delete');
  };

  const metaLine = source ? [
    source.status === 'ACTIVE' ? '可使用' : '已停用',
    source.publishedVersionNo ? `v${source.publishedVersionNo} · 最新`
      : source.anyPublishedVersion ? '无可用版本' : '未发布',
    source.inUseFormCount > 0 ? `${source.inUseFormCount} 张表单在用` : '未被使用',
  ].join(' · ') : '';

  return (
    <Drawer
      size={760}
      open={open}
      onClose={() => setOpen(false)}
      afterOpenChange={(next) => { if (!next) onClose(); }}
      destroyOnHidden
      title={source ? `版本历史 · ${source.name}` : '数据源'}
      extra={source && (
        <Dropdown menu={{ items: moreItems, onClick: onMore }} trigger={['click']}>
          <Button type="text" aria-label="更多操作" icon={<MoreOutlined />} />
        </Dropdown>
      )}
    >
      {loading && <div className={styles.center}><Spin /></div>}
      {!loading && !detail && <Empty description="数据源加载失败，请重试" />}
      {detail && source && (
        <>
          <div className={styles.head}>
            <div>
              <div className={styles.headTitle}>
                <h2 className={styles.headName}>{source.name}</h2>
                <span className={styles.meta}>{source.code}</span>
              </div>
              <span className={styles.meta}>{metaLine}</span>
            </div>
          </div>
          <div className={styles.section}>
            <div className={styles.sectionHead}>
              <span className={styles.timelineTitle}>版本</span>
              <Button type="primary" ghost size="small" icon={<PlusOutlined />}
                disabled={source.status !== 'ACTIVE'}
                onClick={() => setImportOpen(true)}>导入新版本</Button>
            </div>
            <div className={styles.timeline}>
              <OptionSourceVersionTimeline
                versions={detail.versions}
                latestVersionNo={source.publishedVersionNo}
                usageByVersion={usageByVersion}
                busy={busy}
                onPublish={(version) => versionAction(version, 'publish', `v${version.versionNo} 已发布`)()}
                onDiscard={discardVersion}
                onUnpublish={(version) =>
                  versionAction(version, 'unpublish', `v${version.versionNo} 已退回待发布`)()}
                onToggleDisabled={(version) =>
                  versionAction(version, version.disabledAt ? 'enable' : 'disable',
                    version.disabledAt ? `v${version.versionNo} 已启用` : `v${version.versionNo} 已停用`)()}
                onShowRows={(version) => setRowsVersion(version)}
                onDiff={(version, previousVersionNo) => setDiffTarget({
                  versionId: version.id, versionNo: version.versionNo, previousVersionNo,
                })}
              />
            </div>
            <Typography.Paragraph className={styles.note} style={{ marginTop: 12, marginBottom: 0 }}>
              已发布的版本不会再变。表单固定它绑定的版本；要用上新数据，需重新发布那张表单。
            </Typography.Paragraph>
          </div>

          <div className={styles.section}>
            <div className={styles.sectionHead}>
              <span className={styles.timelineTitle}>引用与权限</span>
            </div>
            <div className={styles.field}>
              <Typography.Text strong>哪些表单可以用</Typography.Text>
              <div className={styles.fieldRow}>
                <Select mode="multiple" style={{ minWidth: 280, flex: 1 }} showSearch
                  placeholder="选择表单，可搜索" aria-label="引用到表单"
                  filterOption={false} value={formIds} onChange={setFormIds}
                  onSearch={(word) => void searchForms(word)}
                  options={formChoices} />
                <Button loading={busy} onClick={() => void run(async () => {
                  await request(`/api/option-sources/${sourceId}/forms`, {
                    method: 'PUT',
                    data: { version: source.version, formIds },
                  });
                }, '引用关系已保存')}>保存</Button>
              </div>
              <span className={styles.hint}>被引用的表单，才能在字段里挑到这个数据源。</span>
              {formIds.length === 0 && source.inUseFormCount > 0 && (
                <span className={styles.warn}>
                  还没有引用任何表单，但有 {source.inUseFormCount} 张表单的字段仍在用它。
                  它们不受影响；只是别的表单无法再新建绑定。
                </span>
              )}
            </div>
            <div className={styles.field}>
              <Typography.Text strong>谁可以引用</Typography.Text>
              <AssigneePicker mode="user" value={users} onChange={setUsers} />
              <AssigneePicker mode="role" value={roles} onChange={setRoles} />
              <div className={styles.fieldRow}>
                <Button loading={busy} onClick={() => void run(async () => {
                  await request(`/api/option-sources/${sourceId}/grants`, {
                    method: 'PUT',
                    data: { version: source.version, userIds: users, roleIds: roles },
                  });
                }, '引用权限已保存')}>保存</Button>
              </div>
              <span className={styles.hint}>管理员始终可以引用；其他人需在这里获得用户或角色授权。</span>
            </div>
          </div>
        </>
      )}

      <OptionSourceImportModal sourceId={sourceId} open={importOpen}
        onClose={() => setImportOpen(false)}
        onImported={() => { onListChanged(); void reload(); }} />
      <OptionSourceRowsDrawer sourceId={sourceId} version={rowsVersion}
        onClose={() => setRowsVersion(null)} />
      <OptionSourceDiffModal sourceId={sourceId} target={diffTarget}
        onClose={() => setDiffTarget(null)} />
      {/* 等详情到了再开：删除确认里要列"谁在用"，空着弹出来只会闪一下。 */}
      <OptionSourceLifecycleModal detail={detail ?? null} action={detail ? lifecycle : null} busy={busy}
        onCancel={() => setLifecycle(null)}
        onConfirm={(action) => {
          setLifecycle(null);
          if (action === 'disable') void sourceAction('disable', '已停用')();
          else void run(async () => {
            await request(`/api/option-sources/${sourceId}`, { method: 'DELETE' });
          }, '数据源已删除', true);
        }} />
    </Drawer>
  );
}
