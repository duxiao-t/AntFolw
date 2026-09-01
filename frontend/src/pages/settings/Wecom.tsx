import {
  CheckCircleFilled,
  CloudSyncOutlined,
  KeyOutlined,
  SafetyCertificateOutlined,
  WechatOutlined,
} from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from '@umijs/max';
import {
  Alert,
  App,
  Button,
  Card,
  Col,
  Form,
  Input,
  InputNumber,
  Progress,
  Radio,
  Row,
  Select,
  Space,
  Tag,
  Typography,
  Switch,
} from 'antd';
import { createStyles } from 'antd-style';
import dayjs from 'dayjs';
import { useEffect, useState } from 'react';

type Company = { id: number; name: string };
type SyncStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'PARTIAL' | 'FAILED';
type SyncJob = {
  id: number;
  companyId: number;
  status: SyncStatus;
  phase: string;
  percent: number;
  totalUsers: number;
  processedUsers: number;
  createdUsers: number;
  updatedUsers: number;
  failedUsers: number;
  message?: string;
  errorSummary: string[];
  startedAt?: string;
  finishedAt?: string;
  syncMode?: 'FULL' | 'INCREMENTAL';
};
type Settings = {
  companyId: number;
  corpId: string;
  secretConfigured: boolean;
  latestJob?: SyncJob;
  agentId?: number;
  agentSecretConfigured: boolean;
  oauthEnabled: boolean;
  jsSdkEnabled: boolean;
  messageEnabled: boolean;
};
type FormValues = {
  corpId: string;
  secret?: string;
  agentId?: number;
  agentSecret?: string;
  oauthEnabled: boolean;
  jsSdkEnabled: boolean;
  messageEnabled: boolean;
};
type DeliveryStatus = { pending: number; dead: number; oldestPendingAt?: string };

const WECOM_GREEN = '#07c160';
const active = (job?: SyncJob) =>
  job?.status === 'PENDING' || job?.status === 'RUNNING';

export const syncPollInterval = (job?: SyncJob) => (active(job) ? 1000 : false);

const phaseLabels: Record<string, string> = {
  CONNECTING: '连接企微',
  DEPARTMENTS: '部门同步',
  FETCHING_USERS: '获取成员',
  USERS: '用户同步',
  RELATIONS: '关系同步',
  COMPLETED: '完成',
};

const statusMeta: Record<SyncStatus, { label: string; color: string }> = {
  PENDING: { label: '等待执行', color: 'default' },
  RUNNING: { label: '同步中', color: 'processing' },
  SUCCESS: { label: '同步成功', color: 'success' },
  PARTIAL: { label: '部分成功', color: 'warning' },
  FAILED: { label: '同步失败', color: 'error' },
};

const useStyles = createStyles(({ token }) => ({
  grid: { alignItems: 'stretch' },
  card: {
    height: '100%',
    borderColor: token.colorBorderSecondary,
    boxShadow: '0 8px 24px rgba(28, 50, 74, 0.05)',
  },
  cardTitle: { display: 'flex', alignItems: 'center', gap: 10 },
  icon: {
    display: 'grid',
    width: 34,
    height: 34,
    placeItems: 'center',
    borderRadius: 10,
    color: WECOM_GREEN,
    background: 'rgba(7, 193, 96, 0.10)',
    fontSize: 18,
  },
  intro: { display: 'block', marginBottom: 24, color: token.colorTextSecondary },
  form: { maxWidth: 520 },
  secretHint: { color: token.colorTextTertiary, fontSize: 12 },
  progressHeader: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 12,
    marginBottom: 12,
  },
  phase: { color: token.colorTextSecondary },
  metrics: {
    display: 'grid',
    gridTemplateColumns: 'repeat(4, minmax(0, 1fr))',
    gap: 10,
    marginBlock: 22,
    '@media (max-width: 680px)': { gridTemplateColumns: 'repeat(2, 1fr)' },
  },
  metric: {
    padding: '14px 12px',
    border: `1px solid ${token.colorBorderSecondary}`,
    borderRadius: token.borderRadiusLG,
    background: token.colorFillAlter,
  },
  metricValue: { display: 'block', color: token.colorText, fontSize: 22, lineHeight: 1.2 },
  metricLabel: { display: 'block', marginTop: 5, color: token.colorTextSecondary, fontSize: 12 },
  taskMessage: { minHeight: 22, color: token.colorTextSecondary },
  empty: {
    display: 'grid',
    minHeight: 250,
    placeItems: 'center',
    color: token.colorTextSecondary,
    textAlign: 'center',
  },
  emptyIcon: { color: token.colorTextQuaternary, fontSize: 38 },
  footer: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 12,
    marginTop: 20,
    paddingTop: 18,
    borderTop: `1px solid ${token.colorSplit}`,
  },
  time: { color: token.colorTextTertiary, fontSize: 12 },
  chain: { display: 'grid', gridTemplateColumns: 'repeat(4, minmax(0, 1fr))', gap: 12,
    '@media (max-width: 760px)': { gridTemplateColumns: '1fr 1fr' } },
  chainItem: { padding: 14, border: `1px solid ${token.colorBorderSecondary}`,
    borderRadius: token.borderRadiusLG, background: token.colorFillAlter },
  chainIndex: { display: 'block', marginBottom: 5, color: WECOM_GREEN, fontSize: 11,
    fontWeight: 700, letterSpacing: '.08em' },
}));

function Metric({ label, value }: { label: string; value: number | string }) {
  const { styles } = useStyles();
  return (
    <div className={styles.metric}>
      <strong className={styles.metricValue}>{value}</strong>
      <span className={styles.metricLabel}>{label}</span>
    </div>
  );
}

export default function WecomPage() {
  const { styles } = useStyles();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<FormValues>();
  const [companyId, setCompanyId] = useState<number>();
  const [startedJobId, setStartedJobId] = useState<number>();
  const [dirty, setDirty] = useState(false);
  const [syncMode, setSyncMode] = useState<'FULL' | 'INCREMENTAL'>('INCREMENTAL');

  const companiesQuery = useQuery({
    queryKey: ['companies'],
    queryFn: () => request<Company[]>('/api/companies'),
  });
  const companies = companiesQuery.data ?? [];

  useEffect(() => {
    if (companyId === undefined && companies.length) setCompanyId(companies[0].id);
  }, [companies, companyId]);

  const settingsQuery = useQuery({
    queryKey: ['wecom-settings', companyId],
    queryFn: () => request<Settings>('/api/integrations/wecom/settings', {
      params: { companyId },
    }),
    enabled: companyId !== undefined,
  });
  const settings = settingsQuery.data;

  useEffect(() => {
    if (!settings) return;
    form.setFieldsValue({
      corpId: settings.corpId,
      secret: undefined,
      agentId: settings.agentId,
      agentSecret: undefined,
      oauthEnabled: settings.oauthEnabled,
      jsSdkEnabled: settings.jsSdkEnabled,
      messageEnabled: settings.messageEnabled,
    });
    setDirty(false);
  }, [form, settings]);

  const jobId = startedJobId ?? settings?.latestJob?.id;
  const jobQuery = useQuery({
    queryKey: ['wecom-sync-job', jobId],
    queryFn: () => request<SyncJob>(`/api/integrations/wecom/sync-jobs/${jobId}`),
    enabled: jobId !== undefined && (startedJobId !== undefined || active(settings?.latestJob)),
    initialData: jobId === settings?.latestJob?.id ? settings?.latestJob : undefined,
    refetchInterval: (query) => syncPollInterval(query.state.data as SyncJob | undefined),
  });
  const job = jobQuery.data ?? settings?.latestJob;
  const deliveryQuery = useQuery({
    queryKey: ['wecom-message-status', companyId],
    queryFn: () => request<DeliveryStatus>('/api/integrations/wecom/message-status', { params: { companyId } }),
    enabled: companyId !== undefined && settings?.messageEnabled === true,
    refetchInterval: 10_000,
  });

  const saveMutation = useMutation({
    mutationFn: (values: FormValues) => request<Settings>('/api/integrations/wecom/settings', {
      method: 'PUT',
      data: {
        companyId,
        corpId: values.corpId.trim(),
        ...(values.secret?.trim() ? { secret: values.secret.trim() } : {}),
        agentId: values.agentId,
        ...(values.agentSecret?.trim() ? { agentSecret: values.agentSecret.trim() } : {}),
        oauthEnabled: values.oauthEnabled,
        jsSdkEnabled: values.jsSdkEnabled,
        messageEnabled: values.messageEnabled,
      },
    }),
    onSuccess: (saved) => {
      queryClient.setQueryData(['wecom-settings', companyId], saved);
      form.setFieldValue('secret', undefined);
      form.setFieldValue('agentSecret', undefined);
      setDirty(false);
      message.success('企业微信连接配置已保存');
    },
  });

  const startMutation = useMutation({
    mutationFn: (mode: 'FULL' | 'INCREMENTAL') => request<SyncJob>('/api/integrations/wecom/sync-jobs', {
      method: 'POST',
      data: { companyId, mode },
    }),
    onSuccess: (started) => {
      setStartedJobId(started.id);
      queryClient.setQueryData(['wecom-sync-job', started.id], started);
      message.success(started.status === 'PENDING' ? '同步任务已创建' : '已恢复正在运行的任务');
    },
  });
  const testMessageMutation = useMutation({
    mutationFn: () => request('/api/integrations/wecom/test-message', {
      method: 'POST', data: { companyId },
    }),
    onSuccess: () => message.success('测试消息已发送到当前管理员的企业微信'),
  });
  const retryMessagesMutation = useMutation({
    mutationFn: () => request('/api/integrations/wecom/retry-messages', {
      method: 'POST', data: { companyId },
    }),
    onSuccess: () => { void deliveryQuery.refetch(); message.success('失败消息已重新进入投递队列'); },
  });

  const selectCompany = (value: number) => {
    setCompanyId(value);
    setStartedJobId(undefined);
    setDirty(false);
    form.resetFields();
  };

  const currentStatus = job ? statusMeta[job.status] : undefined;
  const alertType = job?.status === 'FAILED' ? 'error' : 'warning';
  const canStart = !!settings?.secretConfigured && !dirty && !active(job);

  return (
    <PageContainer
      title="企业微信"
      subTitle="按通讯录、免登、JS-SDK、应用消息的顺序完成审批入口配置"
    >
      <Row gutter={[24, 24]} className={styles.grid}>
        <Col xs={24} xl={10}>
          <Card
            className={styles.card}
            loading={companiesQuery.isLoading || settingsQuery.isLoading}
            title={(
              <span className={styles.cardTitle}>
                <span className={styles.icon}><WechatOutlined /></span>
                连接配置
              </span>
            )}
          >
            <Typography.Text className={styles.intro}>
              使用企业微信管理后台的“通讯录同步 Secret”。凭证加密保存且不会再次显示。
            </Typography.Text>
            <Form<FormValues>
              className={styles.form}
              form={form}
              layout="vertical"
              requiredMark="optional"
              onValuesChange={() => setDirty(true)}
              onFinish={(values) => saveMutation.mutate(values)}
            >
              <Form.Item label="公司" required>
                <Select
                  aria-label="公司"
                  value={companyId}
                  loading={companiesQuery.isLoading}
                  options={companies.map((company) => ({
                    value: company.id,
                    label: company.name,
                  }))}
                  placeholder="请选择公司"
                  onChange={selectCompany}
                />
              </Form.Item>
              <Form.Item
                name="corpId"
                label="企业 ID（CorpID）"
                rules={[{ required: true, whitespace: true, message: '请输入 CorpID' }]}
              >
                <Input prefix={<SafetyCertificateOutlined />} maxLength={128} placeholder="ww..." />
              </Form.Item>
              <Form.Item name="agentId" label="自建应用 AgentId">
                <InputNumber min={1} precision={0} style={{ width: '100%' }} placeholder="企业微信应用 AgentId" />
              </Form.Item>
              <Form.Item
                name="agentSecret"
                label="自建应用 Secret"
                extra={settings?.agentSecretConfigured
                  ? <span className={styles.secretHint}><CheckCircleFilled style={{ color: WECOM_GREEN }} /> 已安全配置，留空表示不修改</span>
                  : '与通讯录同步 Secret 分开配置，保存后不会回显'}
              >
                <Input.Password prefix={<KeyOutlined />} maxLength={512} autoComplete="new-password"
                  placeholder={settings?.agentSecretConfigured ? '已配置（留空不修改）' : '请输入应用 Secret'} />
              </Form.Item>
              <Form.Item name="oauthEnabled" label="企业微信内自动免登" valuePropName="checked">
                <Switch />
              </Form.Item>
              <Form.Item name="jsSdkEnabled" label="表单 JS-SDK 能力" valuePropName="checked">
                <Switch />
              </Form.Item>
              <Form.Item name="messageEnabled" label="审批应用消息" valuePropName="checked">
                <Switch />
              </Form.Item>
              <Form.Item
                name="secret"
                label="通讯录同步 Secret"
                extra={settings?.secretConfigured
                  ? <span className={styles.secretHint}><CheckCircleFilled style={{ color: WECOM_GREEN }} /> 已安全配置，留空保存表示不修改</span>
                  : '首次连接必须填写，保存后不会回显'}
              >
                <Input.Password
                  prefix={<KeyOutlined />}
                  maxLength={512}
                  autoComplete="new-password"
                  placeholder={settings?.secretConfigured ? '已配置（留空不修改）' : '请输入通讯录同步 Secret'}
                />
              </Form.Item>
              <Space wrap>
                <Button
                  type="primary"
                  htmlType="submit"
                  disabled={companyId === undefined}
                  loading={saveMutation.isPending}
                >
                  保存连接配置
                </Button>
                {dirty && <Typography.Text type="warning">配置有未保存修改</Typography.Text>}
              </Space>
            </Form>
          </Card>
        </Col>

        <Col xs={24} xl={14}>
          <Card
            className={styles.card}
            loading={settingsQuery.isLoading}
            title={(
              <span className={styles.cardTitle}>
                <span className={styles.icon}><CloudSyncOutlined /></span>
                当前同步任务
              </span>
            )}
            extra={currentStatus && <Tag color={currentStatus.color}>{currentStatus.label}</Tag>}
          >
            {job ? (
              <>
                <div className={styles.progressHeader}>
                  <div>
                    <Typography.Text strong>{phaseLabels[job.phase] ?? job.phase}</Typography.Text>
                    <Typography.Text className={styles.phase}> · {job.message}</Typography.Text>
                  </div>
                  <Typography.Text strong>{job.percent}%</Typography.Text>
                </div>
                <Progress
                  percent={job.percent}
                  showInfo={false}
                  strokeColor={job.status === 'FAILED' ? undefined : WECOM_GREEN}
                  status={job.status === 'FAILED' ? 'exception' : job.status === 'SUCCESS' ? 'success' : 'active'}
                />
                <div className={styles.metrics}>
                  <Metric label="处理进度" value={`${job.processedUsers}/${job.totalUsers}`} />
                  <Metric label="新增成员" value={job.createdUsers} />
                  <Metric label="更新成员" value={job.updatedUsers} />
                  <Metric label="失败成员" value={job.failedUsers} />
                </div>
                {(job.status === 'PARTIAL' || job.status === 'FAILED') && (
                  <Alert
                    showIcon
                    type={alertType}
                    title={job.status === 'FAILED' ? '同步未完成' : '部分数据需要处理'}
                    description={job.errorSummary.length
                      ? job.errorSummary.join('；')
                      : '请检查企微凭证、通讯录权限或成员联系方式后重新同步。'}
                  />
                )}
                {job.status === 'SUCCESS' && (
                  <Alert showIcon type="success" title="通讯录已同步完成" />
                )}
                <div className={styles.footer}>
                  <span className={styles.time}>
                    {job.finishedAt
                      ? `完成于 ${dayjs(job.finishedAt).format('YYYY-MM-DD HH:mm:ss')}`
                      : job.startedAt
                        ? `开始于 ${dayjs(job.startedAt).format('YYYY-MM-DD HH:mm:ss')}`
                        : '等待执行器接收任务'}
                  </span>
                  <Space>
                    <Radio.Group
                      value={syncMode}
                      onChange={(event) => setSyncMode(event.target.value)}
                      disabled={!canStart || startMutation.isPending}
                    >
                      <Radio.Button value="INCREMENTAL">增量同步</Radio.Button>
                      <Radio.Button value="FULL">全量同步</Radio.Button>
                    </Radio.Group>
                    <Button
                      type="primary"
                      icon={<CloudSyncOutlined />}
                      disabled={!canStart}
                      loading={startMutation.isPending}
                      onClick={() => startMutation.mutate(syncMode)}
                      style={{ background: canStart ? WECOM_GREEN : undefined }}
                    >
                      {active(job) ? '同步进行中' : '开始同步'}
                    </Button>
                  </Space>
                </div>
              </>
            ) : (
              <div className={styles.empty}>
                <Space orientation="vertical" size="small">
                  <CloudSyncOutlined className={styles.emptyIcon} />
                  <Typography.Text strong>尚未执行通讯录同步</Typography.Text>
                  <Typography.Text type="secondary">保存连接配置后即可创建持久化同步任务</Typography.Text>
                  <Space>
                    <Radio.Group
                      value={syncMode}
                      onChange={(event) => setSyncMode(event.target.value)}
                      disabled={!canStart || startMutation.isPending}
                    >
                      <Radio.Button value="INCREMENTAL">增量同步</Radio.Button>
                      <Radio.Button value="FULL">全量同步</Radio.Button>
                    </Radio.Group>
                    <Button
                      type="primary"
                      icon={<CloudSyncOutlined />}
                      disabled={!canStart}
                      loading={startMutation.isPending}
                      onClick={() => startMutation.mutate(syncMode)}
                      style={{ background: canStart ? WECOM_GREEN : undefined, marginTop: 10 }}
                    >
                      开始同步
                    </Button>
                  </Space>
                </Space>
              </div>
            )}
          </Card>
        </Col>
      </Row>
      <Card className={styles.card} style={{ marginTop: 24 }} title="接入状态">
        <div className={styles.chain}>
          {[
            ['01 通讯录', settings?.secretConfigured, '同步成员并建立身份映射'],
            ['02 免登', settings?.oauthEnabled, '企业微信内自动进入工作台'],
            ['03 JS-SDK', settings?.jsSdkEnabled, '选图、录音、扫码与定位'],
            ['04 应用消息', settings?.messageEnabled, '待办与结果可靠送达'],
          ].map(([label, enabled, detail]) => (
            <div className={styles.chainItem} key={String(label)}>
              <span className={styles.chainIndex}>{label}</span>
              <Typography.Text strong>{enabled ? '已启用' : '未启用'}</Typography.Text><br />
              <Typography.Text type="secondary">{detail}</Typography.Text>
            </div>
          ))}
        </div>
        {settings?.messageEnabled ? (
          <div className={styles.footer}>
            <Typography.Text type="secondary">
              待投递 {deliveryQuery.data?.pending ?? 0} · 失败 {deliveryQuery.data?.dead ?? 0}
            </Typography.Text>
            <Space wrap>
              {(deliveryQuery.data?.dead ?? 0) > 0 && <Button loading={retryMessagesMutation.isPending}
                onClick={() => retryMessagesMutation.mutate()}>重试失败消息</Button>}
              <Button type="primary" loading={testMessageMutation.isPending}
                onClick={() => testMessageMutation.mutate()}>发送测试消息</Button>
            </Space>
          </div>
        ) : null}
      </Card>
    </PageContainer>
  );
}
