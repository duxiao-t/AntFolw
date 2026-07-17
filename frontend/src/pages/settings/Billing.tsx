import { PageContainer } from '@ant-design/pro-components';
import { Result, Card, Statistic, Row, Col } from 'antd';

export default function BillingPage() {
  return (
    <PageContainer>
      <Row gutter={16}>
        <Col span={8}><Card><Statistic title="当前套餐" value="免费版" /></Card></Col>
        <Col span={8}><Card><Statistic title="已用流程实例" value={0} suffix="条" /></Card></Col>
        <Col span={8}><Card><Statistic title="到期日期" value="-" /></Card></Col>
      </Row>
      <Card style={{ marginTop: 16 }}>
        <Result status="info" title="订阅与账单" subTitle="升级套餐以获得更多流程实例与高级功能" />
      </Card>
    </PageContainer>
  );
}
