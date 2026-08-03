import { PageContainer } from '@ant-design/pro-components';
import { Result } from 'antd';

export default function ReportDashboardPage() {
  return (
    <PageContainer>
      <Result status="info" title="数据看板" subTitle="图表联动的审批效率与业务数据看板，功能开发中" />
    </PageContainer>
  );
}
