import { PageContainer } from '@ant-design/pro-components';
import { useQuery } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { Alert, Card, Descriptions, Skeleton, Tag } from 'antd';

type AccountProfile = {
  id: number;
  username: string;
  displayName: string;
  employeeNo?: string | null;
  departmentName?: string | null;
  position?: string | null;
  email?: string | null;
  phone?: string | null;
  roles: string[];
};

export default function AccountSettings() {
  const profile = useQuery<AccountProfile>({
    queryKey: ['account-profile'],
    queryFn: () => request('/api/account/profile'),
    retry: 0,
  });

  return (
    <PageContainer title="个人设置" subTitle="查看当前账号与组织资料">
      <Card style={{ maxWidth: 900 }}>
        {profile.isPending ? <Skeleton active paragraph={{ rows: 7 }} /> : null}
        {profile.isError ? (
          <Alert type="error" showIcon title="个人资料加载失败" description="请刷新页面后重试。" />
        ) : null}
        {profile.data ? (
          <>
            <Alert
              type="info"
              showIcon
              title="资料由组织管理员维护"
              description="如姓名、部门、职务或联系方式有误，请联系组织管理员修改。"
              style={{ marginBottom: 20 }}
            />
            <Descriptions bordered column={{ xs: 1, sm: 2 }}>
              <Descriptions.Item label="姓名">{profile.data.displayName}</Descriptions.Item>
              <Descriptions.Item label="登录账号">{profile.data.username}</Descriptions.Item>
              <Descriptions.Item label="工号">{profile.data.employeeNo || '未设置'}</Descriptions.Item>
              <Descriptions.Item label="部门">{profile.data.departmentName || '未设置'}</Descriptions.Item>
              <Descriptions.Item label="职务">{profile.data.position || '未设置'}</Descriptions.Item>
              <Descriptions.Item label="手机号">{profile.data.phone || '未设置'}</Descriptions.Item>
              <Descriptions.Item label="邮箱" span={2}>{profile.data.email || '未设置'}</Descriptions.Item>
              <Descriptions.Item label="角色" span={2}>
                {profile.data.roles.length
                  ? profile.data.roles.map((role) => <Tag key={role}>{role}</Tag>)
                  : '未分配角色'}
              </Descriptions.Item>
            </Descriptions>
          </>
        ) : null}
      </Card>
    </PageContainer>
  );
}
