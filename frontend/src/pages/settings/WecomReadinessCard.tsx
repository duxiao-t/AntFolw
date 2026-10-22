import { useMutation, useQuery } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { App, Button, Card, Space, Typography } from 'antd';
import type { DeliveryStatus, Settings } from './wecomModel';
import { useWecomStyles } from './wecomStyles';

type Props = { companyId?: number; settings?: Settings };

/** 接入状态卡片：四段链路是纯展示，投递统计与两个消息按钮只在开启应用消息后出现。 */
export function WecomReadinessCard({ companyId, settings }: Props) {
  const { styles } = useWecomStyles();
  const { message } = App.useApp();

  const deliveryQuery = useQuery({
    queryKey: ['wecom-message-status', companyId],
    queryFn: () => request<DeliveryStatus>('/api/integrations/wecom/message-status', { params: { companyId } }),
    enabled: companyId !== undefined && settings?.messageEnabled === true,
    refetchInterval: 10_000,
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

  return (
    <Card className={styles.card} style={{ marginTop: 24 }} title="接入状态">
      <div className={styles.chain}>
        {[
          ['01 通讯录', settings?.secretConfigured, '同步成员并建立身份映射'],
          ['02 登录', settings?.oauthEnabled, '用户点击企业微信登录后进入系统'],
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
  );
}
