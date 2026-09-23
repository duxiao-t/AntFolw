import { MoreOutlined, PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { history, request } from '@umijs/max';
import { createStyles } from 'antd-style';
import {
  App, Button, Card, Dropdown, Empty, Form, Input, Modal, Select,
  Space, Steps, Table, Tooltip, Typography,
} from 'antd';
import type { MenuProps } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { AssigneePicker } from '../../components/AssigneePicker';
import { OptionSourceImportModal } from './OptionSourceImportModal';
import { OptionSourceRowsDrawer } from './OptionSourceRowsDrawer';

type Summary = {
  id: number; code: string; name: string; status: string; version: number;
  publishedVersionId?: number; publishedVersionNo?: number; rowCount?: number;
  draftVersionId?: number; inUseFormCount: number;
};
type Version = {
  id: number; versionNo: number; status: string; columns: string[];
  rowCount: number; originalName: string;
};
type FormRef = { id: number; code: string; name: string };
type VersionUsage = { versionId: number; forms: FormRef[] };
type Detail = {
  source: Summary; versions: Version[]; userIds: number[]; roleIds: number[];
  forms: FormRef[]; versionUsage: VersionUsage[]; deletable: boolean;
  deleteBlockedReason?: string | null;
};

const useStyles = createStyles(({ token }) => ({
  layout: {
    display: 'grid',
    gridTemplateColumns: 'minmax(260px, 320px) minmax(0, 1fr)',
    gap: 16,
    alignItems: 'start',
    '@media (max-width: 900px)': { gridTemplateColumns: 'minmax(0, 1fr)' },
  },
  listHead: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
    padding: '0 0 12px',
  },
  listTitle: { display: 'flex', alignItems: 'baseline', gap: 6 },
  count: {
    color: 'var(--af-color-muted)',
    fontSize: 12,
    fontVariantNumeric: 'tabular-nums',
  },
  list: { display: 'flex', flexDirection: 'column', gap: 2, marginTop: 8 },
  item: {
    display: 'block',
    width: '100%',
    padding: '9px 10px',
    border: 0,
    borderLeft: '3px solid transparent',
    borderRadius: token.borderRadius,
    background: 'transparent',
    color: 'inherit',
    cursor: 'pointer',
    font: 'inherit',
    textAlign: 'left',
    transition: 'background-color .15s',
    '&:hover': { background: token.colorFillTertiary },
    '&:focus-visible': { outline: `2px solid ${token.colorPrimary}`, outlineOffset: -2 },
  },
  itemActive: {
    borderLeftColor: token.colorPrimary,
    background: 'var(--af-color-primary-soft)',
    '&:hover': { background: 'var(--af-color-primary-soft)' },
  },
  itemName: {
    display: 'flex',
    alignItems: 'center',
    gap: 6,
    fontWeight: 500,
    color: 'var(--af-color-text)',
  },
  meta: {
    display: 'block',
    marginTop: 2,
    color: 'var(--af-color-muted)',
    fontSize: 12,
    fontVariantNumeric: 'tabular-nums',
  },
  dot: {
    flex: '0 0 auto',
    width: 6,
    height: 6,
    borderRadius: '50%',
    background: token.colorSuccess,
  },
  dotOff: { background: token.colorTextQuaternary },
  dotDraft: { background: token.colorWarning },
  head: {
    display: 'flex',
    alignItems: 'flex-start',
    justifyContent: 'space-between',
    gap: 12,
    paddingBottom: 12,
    borderBottom: '1px solid var(--af-color-line)',
  },
  headTitle: { display: 'flex', alignItems: 'center', gap: 8 },
  headName: { margin: 0, fontSize: 16, fontWeight: 600 },
  bar: {
    display: 'inline-block',
    width: 3,
    height: 14,
    marginRight: 8,
    verticalAlign: '-2px',
    borderRadius: 2,
    background: token.colorPrimary,
  },
  rowNote: {
    display: 'block',
    marginTop: 10,
    color: 'var(--af-color-muted)',
    fontSize: 12,
  },
  field: { display: 'flex', flexDirection: 'column', gap: 6 },
  fieldRow: {
    display: 'flex',
    alignItems: 'flex-start',
    gap: 10,
    flexWrap: 'wrap',
    '@media (max-width: 680px)': { flexDirection: 'column', alignItems: 'stretch' },
  },
  hint: { color: 'var(--af-color-muted)', fontSize: 12 },
  warn: { color: 'var(--af-color-warning)', fontSize: 12, lineHeight: 1.5 },
  // 「删除」置灰时下面那行解释。禁用项不响应 hover，光标也别变手。
  menuNote: {
    display: 'block',
    maxWidth: 240,
    color: 'var(--af-color-muted)',
    fontSize: 12,
    lineHeight: 1.5,
    whiteSpace: 'normal',
    cursor: 'default',
  },
  createForm: { display: 'flex', gap: 10, flexWrap: 'wrap' },
}));

export default function OptionSources() {
  const { styles, cx } = useStyles();
  const { message, modal } = App.useApp();
  const [list, setList] = useState<Summary[]>([]);
  const [detail, setDetail] = useState<Detail>();
  const [users, setUsers] = useState<number[]>([]);
  const [roles, setRoles] = useState<number[]>([]);
  const [formIds, setFormIds] = useState<number[]>([]);
  const [formOptions, setFormOptions] = useState<FormRef[]>([]);
  const [keyword, setKeyword] = useState('');
  const [busy, setBusy] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [rowsVersion, setRowsVersion] = useState<Version | null>(null);

  const refresh = async (id?: number) => {
    const sources = await request<Summary[]>('/api/option-sources');
    setList(sources);
    if (!id) return;
    const current = await request<Detail>(`/api/option-sources/${id}`);
    setDetail(current);
    setUsers(current.userIds);
    setRoles(current.roleIds);
    setFormIds(current.forms.map((form) => form.id));
  };

  useEffect(() => {
    void refresh().catch((error) => message.error(error?.message ?? '无法加载数据源'));
  }, []);

  // 表单多选要能搜索：先给一份默认列表，输入时再按关键字远程查。
  const searchForms = async (word?: string) => {
    try {
      const page = await request<{ records?: FormRef[] }>('/api/forms/definitions', {
        params: { page: 1, size: 50, keyword: word },
      });
      setFormOptions(page.records ?? []);
    } catch (error: any) {
      message.error(error?.message ?? '无法加载表单');
    }
  };

  const open = async (source: Summary) => {
    try {
      await refresh(source.id);
      void searchForms();
    } catch (error: any) {
      message.error(error?.message ?? '无法打开数据源');
    }
  };

  const run = async (action: () => Promise<number | undefined>, success: string, clear = false) => {
    setBusy(true);
    try {
      const id = await action();
      if (clear) setDetail(undefined);
      await refresh(clear ? undefined : id ?? detail?.source.id);
      message.success(success);
    } catch (error: any) {
      message.error(error?.message ?? '操作失败');
    } finally {
      setBusy(false);
    }
  };

  const sources = useMemo(() => list.filter((source) => {
    const word = keyword.trim().toLowerCase();
    if (!word) return true;
    return source.name.toLowerCase().includes(word) || source.code.toLowerCase().includes(word);
  }), [list, keyword]);

  const latestVersionNo = detail?.source.publishedVersionNo;

  // 每张表单在发布那一刻把 versionId 钉死，所以同一个源的各个版本，在用表单可以是两批人。
  const usageByVersion = useMemo(() => {
    const map = new Map<number, FormRef[]>();
    for (const usage of detail?.versionUsage ?? []) map.set(usage.versionId, usage.forms);
    return map;
  }, [detail?.versionUsage]);

  const usedByForms = (versionId: number) => {
    const forms = usageByVersion.get(versionId) ?? [];
    if (!forms.length) return <Typography.Text type="secondary">未使用</Typography.Text>;
    return (
      <Tooltip title={forms.map((form) => `${form.name} · ${form.code}`).join('\n')}>
        <span>{forms.map((form) => form.name).join('、')}</span>
      </Tooltip>
    );
  };

  // 只有不可逆的操作才弹确认。停用/启用/发布/取消发布都能回头，点一下就给反馈更直接。
  const moreItems: MenuProps['items'] = detail ? [
    {
      key: 'toggle-status',
      label: detail.source.status === 'ACTIVE' ? '停用' : '启用',
    },
    { type: 'divider' },
    { key: 'delete', danger: true, disabled: !detail.deletable, label: '删除' },
    ...(detail.deletable || !detail.deleteBlockedReason ? [] : [{
      key: 'delete-blocked-reason',
      disabled: true,
      label: <span className={styles.menuNote}>{detail.deleteBlockedReason}</span>,
    }]),
  ] : [];

  const onMore = ({ key }: { key: string }) => {
    if (!detail) return;
    const id = detail.source.id;
    if (key === 'toggle-status') {
      const disabling = detail.source.status === 'ACTIVE';
      void run(async () => {
        await request(`/api/option-sources/${id}/${disabling ? 'disable' : 'enable'}`,
          { method: 'POST' });
      }, disabling ? '已停用' : '已启用');
      return;
    }
    if (key === 'delete') {
      modal.confirm({
        title: '确定删除这个数据源？',
        content: '它没有已发布版本、也没有表单在用。删除后无法恢复。',
        okText: '删除', okButtonProps: { danger: true }, cancelText: '取消',
        onOk: () => run(async () => {
          await request(`/api/option-sources/${id}`, { method: 'DELETE' });
        }, '数据源已删除', true),
      });
    }
  };

  const publishVersion = (version: Version) => {
    if (!detail) return;
    void run(async () => {
      await request(
        `/api/option-sources/${detail.source.id}/versions/${version.id}/publish`, { method: 'POST' });
    }, `v${version.versionNo} 已发布`);
  };

  const unpublishVersion = (version: Version) => {
    if (!detail) return;
    // 被表单版本快照引用的版本，后端会拒绝并说清有几张表单——直接把那句话弹出来。
    void run(async () => {
      await request(
        `/api/option-sources/${detail.source.id}/versions/${version.id}/unpublish`, { method: 'POST' });
    }, `v${version.versionNo} 已退回待发布`);
  };

  const discardVersion = (version: Version) => {
    if (!detail) return;
    const id = detail.source.id;
    modal.confirm({
      title: `丢弃 v${version.versionNo}？`,
      content: `这一版还没发布过，丢弃会连着它导入的 ${version.rowCount} 行数据一起删掉，无法恢复。`,
      okText: '丢弃', okButtonProps: { danger: true }, cancelText: '取消',
      onOk: () => run(async () => {
        await request(`/api/option-sources/${id}/versions/${version.id}`, { method: 'DELETE' });
      }, `v${version.versionNo} 已丢弃`),
    });
  };

  const metaLine = (source: Summary) => [
    source.publishedVersionNo ? `v${source.publishedVersionNo} · 最新` : '未发布',
    source.rowCount ? `${source.rowCount} 行` : undefined,
  ].filter(Boolean).join(' · ');

  return (
    <PageContainer
      title="选项数据源"
      subTitle="导入一次，多个表单可引用；表单固定它绑定的那个版本"
      onBack={() => history.push('/approval/forms')}
      extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
        新建数据源
      </Button>}
    >
      <div className={styles.layout}>
        <Card size="small" title={false}>
          <div className={styles.listHead}>
            <span className={styles.listTitle}>
              <Typography.Text strong>数据源</Typography.Text>
              <span className={styles.count}>{list.length} 个</span>
            </span>
          </div>
          <Input allowClear prefix={<SearchOutlined />} placeholder="搜索名称或编码"
            aria-label="搜索数据源" value={keyword} onChange={(event) => setKeyword(event.target.value)} />
          {list.length === 0 && <Empty style={{ marginTop: 16 }} description="还没有数据源" />}
          {list.length > 0 && sources.length === 0 && (
            <Empty style={{ marginTop: 16 }} image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有匹配的数据源" />
          )}
          <div className={styles.list}>
            {sources.map((source) => (
              <button key={source.id} type="button"
                className={cx(styles.item, detail?.source.id === source.id && styles.itemActive)}
                aria-current={detail?.source.id === source.id}
                onClick={() => void open(source)}>
                <span className={styles.itemName}>
                  <span className={cx(styles.dot, source.status !== 'ACTIVE' && styles.dotOff)} aria-hidden />
                  {source.name}
                </span>
                <span className={styles.meta}>
                  {/* 状态只用灰点表示太弱了（也过不了无障碍），停用就写出来。 */}
                  {source.status !== 'ACTIVE' ? '已停用 · ' : ''}
                  {metaLine(source)}
                  <br />
                  {/* 「在用」= 谁的字段真的绑着它。这和「引用与权限」里那份"谁可以挑到它"的
                      清单是两回事，措辞必须分开，否则两个数字会打架。 */}
                  {source.inUseFormCount > 0 ? `${source.inUseFormCount} 张表单在用` : '未被使用'}
                </span>
              </button>
            ))}
          </div>
        </Card>

        {!detail && list.length === 0 && (
          <Card>
            <Typography.Title level={5} style={{ marginTop: 0 }}>三步开始用</Typography.Title>
            <Steps direction="vertical" size="small" style={{ marginBottom: 16 }} items={[
              { title: '新建数据源', description: '给它起个名字，比如「设备台账」。编码是给表单内部用的英文名。' },
              { title: '导入并发布版本', description: '粘贴文本，或上传 Excel / CSV。检查无误后导入，再发布成版本。' },
              { title: '在表单里引用', description: '把数据源引用到表单，字段的「选项来源」就能挑到它。' },
            ]} />
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
              新建数据源
            </Button>
          </Card>
        )}

        {!detail && list.length > 0 && (
          <Card><Empty description="从左边挑一个数据源" /></Card>
        )}

        {detail && <Space direction="vertical" style={{ width: '100%' }} size="middle">
          <Card>
            <div className={styles.head}>
              <div>
                <div className={styles.headTitle}>
                  <h2 className={styles.headName}>{detail.source.name}</h2>
                  <span className={styles.meta} style={{ margin: 0 }}>{detail.source.code}</span>
                </div>
                <span className={styles.meta}>
                  {detail.source.status === 'ACTIVE' ? '可使用' : '已停用'}
                  {' · '}
                  {metaLine(detail.source)}
                  {' · '}
                  {detail.source.inUseFormCount > 0
                    ? `${detail.source.inUseFormCount} 张表单在用` : '未被使用'}
                </span>
              </div>
              <Dropdown menu={{ items: moreItems, onClick: onMore }} trigger={['click']}>
                <Button type="text" aria-label="更多操作" icon={<MoreOutlined />} />
              </Dropdown>
            </div>
            <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
              已发布的版本不会再变。表单固定它绑定的版本；要用上新数据，需重新发布那张表单。
            </Typography.Paragraph>
          </Card>

          <Card title="版本" size="small"
            extra={<Button type="primary" ghost icon={<PlusOutlined />}
              disabled={detail.source.status !== 'ACTIVE'}
              onClick={() => setImportOpen(true)}>导入新版本</Button>}>
            <Table size="small" rowKey="id" pagination={false} dataSource={detail.versions}
              onRow={(version) => (version.versionNo === latestVersionNo
                ? { style: { background: 'var(--af-color-primary-soft)' } } : {})}
              columns={[
                {
                  title: '版本', dataIndex: 'versionNo', width: 130,
                  render: (versionNo: number) => (
                    <span className={styles.itemName}>
                      {versionNo === latestVersionNo && <span className={styles.bar} aria-hidden />}
                      {`v${versionNo}`}
                      {versionNo === latestVersionNo && <Typography.Text type="secondary">最新</Typography.Text>}
                    </span>
                  ),
                },
                {
                  title: '状态', dataIndex: 'status', width: 110,
                  render: (value: string) => (
                    <span className={styles.itemName}>
                      <span className={cx(styles.dot, value === 'DRAFT' ? styles.dotDraft : undefined)} aria-hidden />
                      {value === 'PUBLISHED' ? '已发布' : '待发布'}
                    </span>
                  ),
                },
                { title: '记录', dataIndex: 'rowCount', width: 90, align: 'right' as const },
                { title: '文件', dataIndex: 'originalName', width: 160, ellipsis: true,
                  render: (value: string) => value || '—' },
                {
                  title: '在用的表单', width: 220, ellipsis: true,
                  render: (_: unknown, version: Version) => usedByForms(version.id),
                },
                {
                  title: '操作', width: 180,
                  render: (_: unknown, version: Version) => (version.status === 'DRAFT'
                    ? <Space size={4}>
                        <Button size="small" type="link" onClick={() => publishVersion(version)}>发布</Button>
                        <Button size="small" type="link" danger
                          onClick={() => discardVersion(version)}>丢弃</Button>
                      </Space>
                    : <Space size={4}>
                        <Button size="small" type="link"
                          onClick={() => setRowsVersion(version)}>查看数据</Button>
                        <Button size="small" type="link"
                          onClick={() => unpublishVersion(version)}>取消发布</Button>
                      </Space>),
                },
              ]} />
            {!detail.versions.length && <span className={styles.rowNote}>
              还没有任何版本。点「导入新版本」把文本或表格导进来。
            </span>}
          </Card>

          <Card title="引用与权限" size="small">
            <Space direction="vertical" style={{ width: '100%' }} size={16}>
              <div className={styles.field}>
                <Typography.Text strong>哪些表单可以用</Typography.Text>
                <div className={styles.fieldRow}>
                  <Select mode="multiple" style={{ minWidth: 280, flex: 1 }} showSearch
                    placeholder="选择表单，可搜索" aria-label="引用到表单"
                    filterOption={false} value={formIds} onChange={setFormIds}
                    onSearch={(word) => void searchForms(word)}
                    options={formOptions.map((form) => ({
                      value: form.id, label: `${form.name} · ${form.code}`,
                    }))} />
                  <Button loading={busy} onClick={() => void run(async () => {
                    await request(`/api/option-sources/${detail.source.id}/forms`, {
                      method: 'PUT',
                      data: { version: detail.source.version, formIds },
                    });
                  }, '引用关系已保存')}>保存</Button>
                </div>
                <span className={styles.hint}>被引用的表单，才能在字段里挑到这个数据源。</span>
                {/* 清空这份清单的效果很容易被低估：页面上什么都不变，但其实没人能再新建绑定了，
                    而已经在用的表单不受影响——所以两者都要说出来。 */}
                {formIds.length === 0 && detail.source.inUseFormCount > 0 && (
                  <span className={styles.warn}>
                    还没有引用任何表单，但有 {detail.source.inUseFormCount} 张表单的字段仍在用它。
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
                    await request(`/api/option-sources/${detail.source.id}/grants`, {
                      method: 'PUT',
                      data: { version: detail.source.version, userIds: users, roleIds: roles },
                    });
                  }, '引用权限已保存')}>保存</Button>
                </div>
                <span className={styles.hint}>管理员始终可以引用；其他人需在这里获得用户或角色授权。</span>
              </div>
            </Space>
          </Card>
        </Space>}
      </div>

      <Modal title="新建数据源" open={createOpen} onCancel={() => setCreateOpen(false)}
        footer={null} destroyOnHidden>
        <Form layout="vertical" onFinish={(values: { name: string; code: string }) =>
          void run(async () => {
            const created = await request<Detail>('/api/option-sources', {
              method: 'POST',
              data: { name: values.name.trim(), code: values.code.trim() },
            });
            setCreateOpen(false);
            return created.source.id;
          }, '数据源已创建')}>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="例如 设备台账" maxLength={128} />
          </Form.Item>
          <Form.Item name="code" label="编码" rules={[
            { required: true, message: '请输入编码' },
            { pattern: /^[a-z][a-z0-9_]{1,63}$/, message: '小写字母开头，只能用小写字母、数字和下划线' },
          ]} extra="英文编码，创建后不可修改。">
            <Input placeholder="例如 device_codes" maxLength={64} />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={busy} block>创建</Button>
        </Form>
      </Modal>

      {detail && <OptionSourceImportModal sourceId={detail.source.id} open={importOpen}
        onClose={() => setImportOpen(false)}
        onImported={() => void refresh(detail.source.id)} />}

      <OptionSourceRowsDrawer sourceId={detail?.source.id ?? 0} version={rowsVersion}
        onClose={() => setRowsVersion(null)} />
    </PageContainer>
  );
}
