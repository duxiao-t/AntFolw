import { PageContainer } from '@ant-design/pro-components';
import { useMutation, useQuery } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { App, Button, Card, Col, Form, Input, Row, Skeleton, Tag, Typography } from 'antd';

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

/** 只读资料按「组织怎么认识你」排序：先身份、再归属、最后联系方式。 */
const PROFILE_FIELDS: Array<{ label: string; key: keyof AccountProfile }> = [
  { label: '姓名', key: 'displayName' },
  { label: '登录账号', key: 'username' },
  { label: '工号', key: 'employeeNo' },
  { label: '部门', key: 'departmentName' },
  { label: '职务', key: 'position' },
  { label: '手机号', key: 'phone' },
  { label: '邮箱', key: 'email' },
];

export default function AccountSettings() {
  const { message } = App.useApp();
  const [form] = Form.useForm();

  const profile = useQuery<AccountProfile>({
    queryKey: ['account-profile'],
    queryFn: () => request('/api/account/profile'),
    retry: 0,
  });

  const changePassword = useMutation({
    mutationFn: (values: { currentPassword: string; newPassword: string }) =>
      request<void>('/api/account/password', { method: 'POST', data: values }),
    onSuccess: () => {
      form.resetFields();
      message.success('密码已修改，其它设备需要重新登录');
    },
  });

  return (
    <PageContainer title="个人设置" subTitle="查看账号资料，并修改登录密码">
      <Row gutter={[24, 24]} align="top">
        <Col xs={24} lg={9} xl={8}>
          <section aria-labelledby="profile-heading">
            <div
              style={{
                display: 'flex',
                alignItems: 'baseline',
                justifyContent: 'space-between',
                gap: 12,
              }}
            >
              <Typography.Title id="profile-heading" level={5} style={{ margin: 0, fontSize: 15 }}>
                账号资料
              </Typography.Title>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                由组织管理员维护
              </Typography.Text>
            </div>
            <Typography.Paragraph type="secondary" style={{ fontSize: 12, margin: '6px 0 0' }}>
              姓名、部门、职务或联系方式有误时，请联系组织管理员修改。
            </Typography.Paragraph>

            {profile.isPending ? <Skeleton active paragraph={{ rows: 7 }} /> : null}
            {profile.isError ? (
              <Typography.Text type="danger" style={{ display: 'block', marginTop: 16 }}>
                个人资料加载失败，请刷新页面后重试。
              </Typography.Text>
            ) : null}
            {profile.data ? (
              <dl style={{ margin: '12px 0 0' }}>
                {PROFILE_FIELDS.map((field) => {
                  const value = profile.data?.[field.key];
                  const text = typeof value === 'string' && value.trim() ? value : '';
                  return (
                    <div
                      key={field.key}
                      style={{
                        padding: '10px 0',
                        borderTop: '1px solid var(--af-color-line)',
                      }}
                    >
                      <dt style={{ fontSize: 12, color: 'var(--af-color-muted)' }}>{field.label}</dt>
                      <dd
                        style={{
                          margin: '2px 0 0',
                          fontSize: 14,
                          color: text ? 'var(--af-color-text)' : 'var(--af-color-muted)',
                          overflowWrap: 'anywhere',
                        }}
                      >
                        {text || '未设置'}
                      </dd>
                    </div>
                  );
                })}
                <div style={{ padding: '10px 0', borderTop: '1px solid var(--af-color-line)' }}>
                  <dt style={{ fontSize: 12, color: 'var(--af-color-muted)' }}>角色</dt>
                  <dd style={{ margin: '4px 0 0' }}>
                    {profile.data.roles.length ? (
                      profile.data.roles.map((role) => (
                        <Tag key={role} style={{ marginInlineEnd: 6 }}>
                          {role}
                        </Tag>
                      ))
                    ) : (
                      <Typography.Text type="secondary" style={{ fontSize: 14 }}>
                        未分配角色
                      </Typography.Text>
                    )}
                  </dd>
                </div>
              </dl>
            ) : null}
          </section>
        </Col>

        <Col xs={24} lg={15} xl={16}>
          {/* 卡片只围住表单本身：拉满整列会让一小段表单陷在大片留白里。 */}
          <Card title="修改密码" style={{ maxWidth: 520 }}>
            <Form
              form={form}
              layout="vertical"
              requiredMark={false}
              style={{ maxWidth: 420 }}
              onFinish={(values) => changePassword.mutate(values)}
            >
              <Form.Item
                label="原密码"
                name="currentPassword"
                rules={[{ required: true, message: '请输入原密码' }]}
              >
                <Input.Password autoComplete="current-password" />
              </Form.Item>
              <Form.Item
                label="新密码"
                name="newPassword"
                rules={[
                  { required: true, message: '请输入新密码' },
                  { min: 8, max: 64, message: '密码长度为 8 到 64 位' },
                ]}
              >
                <Input.Password autoComplete="new-password" />
              </Form.Item>
              <Form.Item
                label="确认新密码"
                name="confirmPassword"
                dependencies={['newPassword']}
                rules={[
                  { required: true, message: '请再次输入新密码' },
                  ({ getFieldValue }) => ({
                    validator(_, value) {
                      return !value || getFieldValue('newPassword') === value
                        ? Promise.resolve()
                        : Promise.reject(new Error('两次输入的密码不一致'));
                    },
                  }),
                ]}
              >
                <Input.Password autoComplete="new-password" />
              </Form.Item>
              <Form.Item style={{ marginBottom: 0 }}>
                <Button type="primary" htmlType="submit" loading={changePassword.isPending}>
                  修改密码
                </Button>
                <Typography.Text
                  type="secondary"
                  style={{ display: 'block', marginTop: 8, fontSize: 12 }}
                >
                  修改后，其它已登录的设备需要重新登录。
                </Typography.Text>
              </Form.Item>
            </Form>
          </Card>
        </Col>
      </Row>
    </PageContainer>
  );
}
