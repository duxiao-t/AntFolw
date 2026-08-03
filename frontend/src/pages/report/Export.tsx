import { PageContainer } from '@ant-design/pro-components';
import { Result } from 'antd';

export default function ExportPage() {
  return (
    <PageContainer>
      <Result status="info" title="数据导出" subTitle="支持 Excel/PDF 格式导出审批数据，功能开发中" />
    </PageContainer>
  );
}
