import { useMemo } from "react";
import { Outlet, useLocation, useNavigate } from "react-router-dom";
import { useMobileBootstrap } from "../features/workbench/workbench.api";

interface TabConfig {
  key: string;
  title: string;
  icon: string;
  badge?: number;
}

const TAB_BASE: ReadonlyArray<Omit<TabConfig, "badge">> = [
  { key: "/workbench", title: "工作台", icon: "home" },
  { key: "/tasks", title: "待办", icon: "tasks" },
  { key: "/profile", title: "我的", icon: "profile" },
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
      if (tab.key === "/tasks") {
        return { ...tab, badge: pendingCount > 0 ? pendingCount : undefined };
      }
      return tab;
    });
  }, [bootstrap.data?.pendingCount]);

  return (
    <div className="af-shell" data-testid="mobile-shell">
      <Outlet />
      <nav className="tabbar touchSafeNav" aria-label="主导航">
        {tabs.map((tab) => {
          const active = isActiveTab(location.pathname, tab.key);
          return (
            <button
              type="button"
              key={tab.key}
              className={`tabbar__item${active ? " is-active" : ""}`}
              data-testid={`tab-${tab.key.replace("/", "")}`}
              onClick={() => navigate(tab.key)}
              aria-current={active ? "page" : undefined}
            >
              <TabIcon name={tab.icon} />
              {tab.title}
              {tab.badge ? <span className="tabbar__badge">{tab.badge > 99 ? "99+" : tab.badge}</span> : null}
            </button>
          );
        })}
      </nav>
    </div>
  );
}

function TabIcon({ name }: { name: string }) {
  if (name === "home") {
    return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M3 11.5 12 4l9 7.5" /><path d="M5 10v9h14v-9" /></svg>;
  }
  if (name === "tasks") {
    return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M4 7h12M4 12h16M4 17h10" /></svg>;
  }
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><circle cx="12" cy="9" r="4" /><path d="M4 21c0-4 4-7 8-7s8 3 8 7" /></svg>;
}

export default MobileShell;
