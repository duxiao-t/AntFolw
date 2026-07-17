import { PageContainer } from '@ant-design/pro-components';
import { Card, Button, Space } from 'antd';
import { history } from '@umijs/max';
import { FormOutlined, ApartmentOutlined } from '@ant-design/icons';

export default function DesignerEntryPage() {
  return (
    <PageContainer title="流程设计器">
      <Card>
        <Space direction="vertical" style={{ width: '100%' }}>
          <p>选择审批模板后进入可视化拖拽流程设计器，配置审批人、条件分支、抄送人等。</p>
          <Space>
            <Button type="primary" icon={<FormOutlined />} onClick={() => history.push('/approval/templates')}>
              从模板库选择
            </Button>
            <Button icon={<ApartmentOutlined />} onClick={() => history.push('/approval/templates')}>
              查看已有流程
            </Button>
          </Space>
        </Space>
      </Card>
    </PageContainer>
  );
}
