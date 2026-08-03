import { PageContainer } from '@ant-design/pro-components';
import { Result } from 'antd';

export default function AuditLogPage() {
  return (
    <PageContainer>
      <Result status="info" title="操作日志审计" subTitle="记录管理员操作与审批关键节点，功能开发中" />
    </PageContainer>
  );
}
