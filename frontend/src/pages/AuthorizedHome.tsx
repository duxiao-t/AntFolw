import { PageLoading } from '@ant-design/pro-components';
import { history, useModel } from '@umijs/max';
import { Button, Result } from 'antd';
import { useEffect, useMemo } from 'react';
import { CONSOLE_ENTRY, firstAccessiblePath } from './registry';

export { firstAccessiblePath };

export default function AuthorizedHome() {
  const { initialState } = useModel('@@initialState');
  const currentUser = initialState?.currentUser as
    | (API.CurrentUser & { permissions?: string[] })
    | undefined;
  const roles = currentUser?.roles ?? [];
  const permissions = currentUser?.permissions ?? [];
  const canEnterConsole = roles.includes('admin') || permissions.includes(CONSOLE_ENTRY);
  const target = useMemo(
    () => (canEnterConsole ? firstAccessiblePath(roles, permissions) : undefined),
    [canEnterConsole, roles, permissions],
  );

  useEffect(() => {
    if (target) history.replace(target);
  }, [target]);

  if (target) return <PageLoading />;
  if (!canEnterConsole) {
    return (
      <Result
        status="403"
        title="当前账号不能进入管理端"
        subTitle="请使用手机端（企业微信或移动浏览器）打开移动入口办理审批业务。"
        extra={[
          <Button
            type="primary"
            key="mobile"
            onClick={() => window.location.replace('/mobile/')}
          >
            前往移动端
          </Button>,
          <Button
            key="logout"
            onClick={() => {
              localStorage.removeItem('antflow-token');
              history.push('/user/login');
            }}
          >
            退出登录
          </Button>,
        ]}
      />
    );
  }
  return (
    <Result
      status="403"
      title="暂无可访问页面"
      subTitle="请联系管理员为当前账号分配能力。"
      extra={
        <Button
          onClick={() => {
            localStorage.removeItem('antflow-token');
            history.push('/user/login');
          }}
        >
          退出登录
        </Button>
      }
    />
  );
}
