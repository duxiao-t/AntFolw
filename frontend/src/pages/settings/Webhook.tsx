import { PageContainer } from '@ant-design/pro-components';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { Alert, App, Button, Card, Input, Space, Tag, Typography } from 'antd';
import { useEffect, useState } from 'react';

type WebhookSettings = {
  envHosts: string[];
  dbHosts: string[];
  effective: string[];
  httpsOnly: boolean;
  allowPrivateAddresses: boolean;
};

const QUERY_KEY = ['webhook-settings'];

/**
 * Webhook 出站允许主机：env 基线只读展示，本页维护「追加项」。
 *
 * 只有管理员能进（integration:webhook:manage）。私网/回环/云元数据地址始终被拦，
 * 生产还强制 HTTPS —— 这些不受本页影响，改的是 SSRF 出站边界，保存会写审计。
 */
export default function WebhookSettingsPage() {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState('');

  const settings = useQuery({
    queryKey: QUERY_KEY,
    queryFn: () => request<WebhookSettings>('/api/settings/webhook'),
    retry: 0,
  });

  useEffect(() => {
    if (settings.data) setDraft(settings.data.dbHosts.join('\n'));
  }, [settings.data]);

  const save = useMutation({
    mutationFn: (hosts: string[]) =>
      request<WebhookSettings>('/api/settings/webhook', {
        method: 'PUT',
        data: { allowedHosts: hosts },
      }),
    onSuccess: (data) => {
      queryClient.setQueryData(QUERY_KEY, data);
      setDraft(data.dbHosts.join('\n'));
      message.success('已保存');
    },
    onError: (error: Error) => message.error(error?.message ?? '保存失败'),
  });

  const submit = () => {
    const hosts = draft
      .split(/[\n,]/)
      .map((value) => value.trim())
      .filter(Boolean);
    save.mutate(hosts);
  };

  const data = settings.data;

  return (
    <PageContainer
      header={{ title: 'Webhook 出站', subTitle: '允许触发器/通知访问的目标主机' }}
      content={
        <Alert
          type="info"
          showIcon
          title="白名单 = 环境变量基线 + 本页追加"
          description="环境变量 ANTFLOW_WEBHOOK_ALLOWED_HOSTS 提供基线（随部署固定，本页只读）；这里只做追加，误清空不会放宽到任意主机。私网/回环/云元数据地址始终禁止，生产环境强制 HTTPS。"
        />
      }
    >
      {settings.isError ? (
        <Alert
          type="error"
          showIcon
          title="配置加载失败"
          action={<Button size="small" onClick={() => settings.refetch()}>重试</Button>}
        />
      ) : null}

      <Card title="允许的主机" loading={settings.isPending} style={{ marginTop: 16 }}>
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <div>
            <Typography.Text type="secondary">环境变量基线（只读）</Typography.Text>
            <div style={{ marginTop: 6 }}>
              {data && data.envHosts.length > 0
                ? data.envHosts.map((host) => <Tag key={host}>{host}</Tag>)
                : <Typography.Text type="secondary">未配置</Typography.Text>}
            </div>
          </div>

          <div>
            <Typography.Text type="secondary">
              后台追加（每行一个域名，支持 *.example.com；不要带协议或端口）
            </Typography.Text>
            <Input.TextArea
              style={{ marginTop: 6, fontFamily: 'monospace' }}
              rows={5}
              value={draft}
              placeholder={'qyapi.weixin.qq.com\noapi.dingtalk.com'}
              onChange={(event) => setDraft(event.target.value)}
            />
          </div>

          <div>
            <Typography.Text type="secondary">当前生效（基线 ∪ 追加）</Typography.Text>
            <div style={{ marginTop: 6 }}>
              {data && data.effective.length > 0
                ? data.effective.map((host) => <Tag color="blue" key={host}>{host}</Tag>)
                : <Typography.Text type="secondary">为空：所有出站 Webhook 都会被拒绝</Typography.Text>}
            </div>
          </div>

          <Space>
            <Button type="primary" loading={save.isPending} onClick={submit}>
              保存
            </Button>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              生产强制 HTTPS；私网/回环地址始终禁止。
            </Typography.Text>
          </Space>
        </Space>
      </Card>
    </PageContainer>
  );
}
