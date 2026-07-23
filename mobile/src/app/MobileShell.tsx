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
  { key: "/workbench", title: "工作台", icon: "\u2302" },
  { key: "/tasks", title: "待办", icon: "\u2713" },
  { key: "/profile", title: "我的", icon: "\u25CF" },
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
      <div style={{ flex: 1, minHeight: 0, display: "flex", flexDirection: "column" }}>
        <Outlet />
      </div>
      <nav className="af-tabbar touchSafeNav" aria-label="主导航">
        {tabs.map((tab) => {
          const active = isActiveTab(location.pathname, tab.key);
          return (
            <button
              type="button"
              key={tab.key}
              className={`af-tabbar__item${active ? " is-active" : ""}`}
              data-testid={`tab-${tab.key.replace("/", "")}`}
              onClick={() => navigate(tab.key)}
              aria-current={active ? "page" : undefined}
            >
              <i>{tab.icon}</i>
              <span>{tab.title}</span>
            </button>
          );
        })}
      </nav>
    </div>
  );
}

export default MobileShell;
