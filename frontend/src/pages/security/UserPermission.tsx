import { PageContainer } from '@ant-design/pro-components';
import { Result } from 'antd';

export default function UserPermissionPage() {
  return (
    <PageContainer>
      <Result status="info" title="用户权限分配" subTitle="为用户分配角色与数据权限，功能开发中" />
    </PageContainer>
  );
}
