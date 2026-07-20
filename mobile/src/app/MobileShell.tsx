import { useMemo } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { TabBar } from 'antd-mobile';
import { AppOutline, CheckShieldOutline, UserOutline } from 'antd-mobile-icons';
import classes from './MobileShell.module.css';
import { useMobileBootstrap } from '../features/workbench/workbench.api';

interface TabConfig {
  key: string;
  title: string;
  icon: React.ReactNode;
  badge?: number;
}

const TAB_BASE: ReadonlyArray<Omit<TabConfig, 'badge'>> = [
  { key: '/workbench', title: '工作台', icon: <AppOutline /> },
  { key: '/tasks', title: '待办', icon: <CheckShieldOutline /> },
  { key: '/profile', title: '我的', icon: <UserOutline /> },
];

function isActiveTab(pathname: string, key: string): boolean {
  return pathname === key || pathname.startsWith(`${key}/`);
}

export function MobileShell() {
  const navigate = useNavigate();
  const location = useLocation();
  const bootstrap = useMobileBootstrap();

  const tabs: TabConfig[] = useMemo(() => {
    const pendingCount = bootstrap.data?.pendingCount ?? 0;
    return TAB_BASE.map((tab) => {
      if (tab.key === '/tasks') {
        return { ...tab, badge: pendingCount > 0 ? pendingCount : undefined };
      }
      return tab;
    });
  }, [bootstrap.data?.pendingCount]);

  return (
    <div className={classes.mobileShell} data-testid="mobile-shell">
      <div className={classes.content}>
        <Outlet />
      </div>
      <nav className={classes.tabBarWrap} aria-label="主导航">
        <TabBar activeKey={tabs.find((tab) => isActiveTab(location.pathname, tab.key))?.key} safeArea={false}>
          {tabs.map((tab) => (
            <TabBar.Item
              key={tab.key}
              title={tab.title}
              icon={tab.icon}
              badge={tab.badge ?? null}
              onClick={() => navigate(tab.key)}
              data-testid={`tab-${tab.key.replace('/', '')}`}
            />
          ))}
        </TabBar>
      </nav>
    </div>
  );
}

export default MobileShell;
