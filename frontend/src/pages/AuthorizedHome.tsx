import { PageLoading } from '@ant-design/pro-components';
import { history, useModel } from '@umijs/max';
import { Button, Result } from 'antd';
import { useEffect, useMemo } from 'react';

const pageOrder: Array<[string, string]> = [
  ['page.workplace', '/workplace'],
  ['page.org.contacts', '/org/contacts'],
  ['page.approval.forms', '/approval/forms'],
  ['page.approval.records', '/approval/records'],
  ['page.report.center', '/report/center'],
  ['page.report.dashboard', '/report/view'],
  ['page.report.export', '/report/export'],
  ['page.security.roles', '/security/roles'],
  ['page.security.audit_log', '/security/audit-log'],
  ['page.settings.company', '/settings/company'],
  ['page.settings.s3', '/settings/s3'],
  ['page.settings.wecom', '/settings/wecom'],
  ['page.settings.billing', '/settings/billing'],
];

export function firstAccessiblePath(roles: string[], permissions: string[]) {
  if (roles.includes('admin')) return '/workplace';
  return pageOrder.find(([permission]) => permissions.includes(permission))?.[1];
}

export default function AuthorizedHome() {
  const { initialState } = useModel('@@initialState');
  const currentUser = initialState?.currentUser as
    | (API.CurrentUser & { permissions?: string[] })
    | undefined;
  const target = useMemo(
    () => firstAccessiblePath(currentUser?.roles ?? [], currentUser?.permissions ?? []),
    [currentUser],
  );

  useEffect(() => {
    if (target) history.replace(target);
  }, [target]);

  if (target) return <PageLoading />;
  return (
    <Result
      status="403"
      title="暂无可访问页面"
      subTitle="请联系管理员为当前账号分配页面权限。"
      extra={<Button onClick={() => { localStorage.removeItem('antflow-token'); history.push('/user/login'); }}>退出登录</Button>}
    />
  );
}
