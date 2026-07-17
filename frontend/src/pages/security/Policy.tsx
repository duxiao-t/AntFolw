import { PageContainer } from '@ant-design/pro-components';
import { Result } from 'antd';

export default function PolicyPage() {
  return (
    <PageContainer>
      <Result status="info" title="安全策略" subTitle="登录验证配置、IP 白名单，功能开发中" />
    </PageContainer>
  );
}
