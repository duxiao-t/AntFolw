import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { history, request } from '@umijs/max';
import { createStyles } from 'antd-style';
import { App, Alert, Badge, Button, Card, Empty, Form, Input, Modal, Steps, Table, Typography } from 'antd';
import dayjs from 'dayjs';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { OptionSourceDrawer } from './OptionSourceDrawer';
import type { SourceSummary } from './optionSourceTypes';

const useStyles = createStyles(({ token }) => ({
  head: { display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' },
  // 状态徽标在窄屏下会被压成一字一行（"可使用"竖着排），钉住不换行。
  status: { whiteSpace: 'nowrap' },
  name: { fontWeight: 500, color: 'var(--af-color-text)' },
  code: { color: 'var(--af-color-muted)', fontSize: 12 },
  meta: {
    display: 'block',
    marginTop: 2,
    color: 'var(--af-color-muted)',
    fontSize: 12,
    fontVariantNumeric: 'tabular-nums',
  },
  bar: {
    display: 'inline-block',
    width: 3,
    height: 13,
    marginRight: 6,
    verticalAlign: '-2px',
    borderRadius: 2,
    background: token.colorPrimary,
  },
  actions: { display: 'flex', gap: 2 },
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
  },
  actionDanger: { color: 'var(--af-color-danger)' },
  empty: { padding: '8px 0 4px' },
  emptyTitle: { marginTop: 0 },
}));

/**
 * 选项数据源（管理端）：列表即表格，详情走右侧抽屉（版本历史 / 引用与权限）。
 *
 * 抽屉用 `key={sourceId}` 渲染：换源即卸载重建，在途请求不会把界面切回上一个源。
 */
export default function OptionSources() {
  const { styles, cx } = useStyles();
  const { message } = App.useApp();
  const [list, setList] = useState<SourceSummary[]>([]);
  const [keyword, setKeyword] = useState('');
  const [busy, setBusy] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  // 行内三个操作都开同一个抽屉，只是自动打开的那一块不同（版本历史 / 导入 / 删除确认）。
  const [openSourceId, setOpenSourceId] = useState<number>();
  const [openIntent, setOpenIntent] = useState<'versions' | 'import' | 'delete'>('versions');
  const openDrawer = (id: number, intent: 'versions' | 'import' | 'delete' = 'versions') => {
    setOpenIntent(intent);
    setOpenSourceId(id);
  };

  const reloadList = useCallback(async () => {
    try {
      setList(await request<SourceSummary[]>('/api/option-sources'));
    } catch (error: any) {
      message.error(error?.message ?? '无法加载数据源');
    }
  }, [message]);

  useEffect(() => { void reloadList(); }, [reloadList]);

  const sources = useMemo(() => {
    const word = keyword.trim().toLowerCase();
    if (!word) return list;
    return list.filter((source) =>
      source.name.toLowerCase().includes(word) || source.code.toLowerCase().includes(word));
  }, [list, keyword]);

  const versionLine = (source: SourceSummary) =>
    source.publishedVersionNo
      ? `v${source.publishedVersionNo} · 共 ${source.rowCount ?? 0} 行的最新可用版本`
      : source.anyPublishedVersion ? '无可用版本（都下架了）' : '未发布';

  const action = (label: string, onClick: () => void, danger = false) => (
    <button type="button" className={cx(styles.action, danger && styles.actionDanger)}
      onClick={onClick}>
      {label}
    </button>
  );

  return (
    <PageContainer
      title="选项数据源"
      subTitle="导入一次，多个表单可引用；表单固定它绑定的那个版本"
      onBack={() => history.push('/approval/forms')}
      extra={
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
          新建数据源
        </Button>
      }
    >
      <Card size="small">
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="版本化规则"
          description="数据源每次导入并发布都会产生一个新版本，已发布的版本不会再变；表单在发布时钉死它绑定的版本，所以更新数据源不会影响已发布的表单与历史数据。"
        />
        <Input allowClear prefix={<SearchOutlined />} placeholder="搜索名称或编码"
          aria-label="搜索数据源" value={keyword} onChange={(event) => setKeyword(event.target.value)}
          style={{ maxWidth: 320, marginBottom: 12 }} />
        <Table<SourceSummary>
          rowKey="id"
          size="middle"
          dataSource={sources}
          pagination={false}
          locale={{
            emptyText: list.length > 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有匹配的数据源" />
            ) : (
              <div className={styles.empty}>
                <Typography.Title level={5} className={styles.emptyTitle}>三步开始用</Typography.Title>
                <Steps direction="vertical" size="small" items={[
                  { title: '新建数据源', description: '给它起个名字，比如「设备台账」。编码是给表单内部用的英文名。' },
                  { title: '导入并发布版本', description: '粘贴文本，或上传 Excel / CSV。检查无误后导入，再发布成版本。' },
                  { title: '在表单里引用', description: '把数据源引用到表单，字段的「选项来源」就能挑到它。' },
                ]} />
              </div>
            ),
          }}
          columns={[
            {
              title: '名称',
              dataIndex: 'name',
              render: (name: string, source) => (
                <div>
                  <div className={styles.head}>
                    <span className={styles.bar} aria-hidden />
                    <span className={styles.name}>{name}</span>
                    <Badge status={source.status === 'ACTIVE' ? 'success' : 'default'}
                      text={<span className={styles.status}>
                        {source.status === 'ACTIVE' ? '可使用' : '已停用'}
                      </span>} />
                  </div>
                  <span className={styles.meta}>{source.code}</span>
                </div>
              ),
            },
            {
              title: '当前版本',
              width: 220,
              render: (_, source) => (
                <>
                  <span>{source.publishedVersionNo ? `v${source.publishedVersionNo}` : '—'}</span>
                  {source.draftVersionId != null && (
                    <Typography.Text type="secondary"> · 有待发布草稿</Typography.Text>
                  )}
                  <span className={styles.meta}>{versionLine(source)}</span>
                </>
              ),
            },
            {
              title: '引用情况',
              width: 180,
              render: (_, source) => (
                <>
                  <span>{source.inUseFormCount > 0 ? `${source.inUseFormCount} 张表单在用` : '未被使用'}</span>
                  <span className={styles.meta}>按字段实际绑定统计</span>
                </>
              ),
            },
            {
              title: '更新时间',
              dataIndex: 'updatedAt',
              width: 160,
              render: (value?: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—'),
            },
            {
              title: '操作',
              width: 200,
              render: (_, source) => (
                <div className={styles.actions}>
                  {action('版本', () => openDrawer(source.id, 'versions'))}
                  {action('发新版', () => openDrawer(source.id, 'import'))}
                  {action('删除', () => openDrawer(source.id, 'delete'), true)}
                </div>
              ),
            },
          ]}
        />
      </Card>

      <Modal title="新建数据源" open={createOpen} onCancel={() => setCreateOpen(false)}
        footer={null} destroyOnHidden>
        <Form layout="vertical" onFinish={async (values: { name: string; code: string }) => {
          setBusy(true);
          try {
            const created = await request<{ source: SourceSummary }>('/api/option-sources', {
              method: 'POST',
              data: { name: values.name.trim(), code: values.code.trim() },
            });
            setCreateOpen(false);
            await reloadList();
            openDrawer(created.source.id, 'import');
            message.success('数据源已创建，接着导入并发布第一个版本');
          } catch (error: any) {
            message.error(error?.message ?? '创建失败');
          } finally {
            setBusy(false);
          }
        }}>
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

      {openSourceId != null && (
        <OptionSourceDrawer
          key={openSourceId}
          sourceId={openSourceId}
          intent={openIntent}
          onClose={() => setOpenSourceId(undefined)}
          onListChanged={() => void reloadList()}
        />
      )}
    </PageContainer>
  );
}
