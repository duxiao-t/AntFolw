import { useMutation, useQuery } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { Alert, App, Button, Card, Space, Typography } from 'antd';
import type { AccessInfo } from './wecomModel';
import { useWecomStyles } from './wecomStyles';

/**
 * 后台配置卡片：给出四项需要粘回企业微信管理后台的地址，并能就地探测域名验证文件。
 * 接入信息只有这一处消费，查询直接住在组件里。
 */
export function WecomAccessCard() {
  const { styles } = useWecomStyles();
  const { message } = App.useApp();

  const accessQuery = useQuery({
    queryKey: ['wecom-access-info'],
    queryFn: () => request<AccessInfo>('/api/integrations/wecom/access-info'),
  });
  const accessInfo = accessQuery.data?.publicBaseUrl ? accessQuery.data : undefined;

  const verificationMutation = useMutation({
    mutationFn: async () => {
      if (!accessInfo) throw new Error('公网接入信息尚未加载');
      const expected = new URL(accessInfo.verificationFileUrl).pathname
        .match(/\/WW_verify_(.+)\.txt$/)?.[1];
      const response = await fetch(accessInfo.verificationFileUrl, { cache: 'no-store' });
      if (!expected || !response.ok || (await response.text()).trim() !== expected) {
        throw new Error('验证文件不可达或内容不匹配');
      }
    },
    onSuccess: () => message.success('验证文件可达且内容正确'),
    onError: (error: Error) => message.error(error.message),
  });

  return (
    <Card className={styles.card} style={{ marginTop: 24 }} title="企业微信后台配置"
      loading={accessQuery.isLoading}>
      {accessInfo ? (
        <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
          {!accessInfo.secure && (
            <Alert type="warning" showIcon
              title="当前公网入口使用 HTTP"
              description="可信域名校验可以使用，但摄像头、麦克风、定位及部分企业微信能力可能要求 HTTPS。" />
          )}
          <div className={styles.accessGrid}>
            <Typography.Text type="secondary">可信域名</Typography.Text>
            <Typography.Text code copyable>{accessInfo.trustedDomain}</Typography.Text>
            <Typography.Text type="secondary">移动端应用主页（企微入口）</Typography.Text>
            <Typography.Text copyable>{accessInfo.mobileHomeUrl}</Typography.Text>
            <Typography.Text type="secondary">OAuth 回调地址（仅用于登录）</Typography.Text>
            <Typography.Text copyable>{accessInfo.oauthCallbackUrl}</Typography.Text>
            <Typography.Text type="secondary">域名验证文件</Typography.Text>
            <Space wrap>
              <Typography.Text copyable>{accessInfo.verificationFileUrl}</Typography.Text>
              <Button href={accessInfo.verificationFileUrl} target="_blank">打开</Button>
              <Button loading={verificationMutation.isPending}
                onClick={() => verificationMutation.mutate()}>检测</Button>
            </Space>
          </div>
        </Space>
      ) : null}
    </Card>
  );
}
