import { useMemo } from "react";
import { useNavigate } from "react-router-dom";
import { AppGrid } from "./components/AppGrid";
import { RecentProcesses } from "./components/RecentProcesses";
import {
  capFavorites,
  capRecents,
  useMobileBootstrap,
} from "./workbench.api";
import { PageError, PageSkeleton } from "../../shared/ui/PageStates";

function greeting(now: Date): string {
  const hour = now.getHours();
  if (hour < 6) return "夜深了";
  if (hour < 11) return "早上好";
  if (hour < 13) return "中午好";
  if (hour < 18) return "下午好";
  return "晚上好";
}

export function WorkbenchPage() {
  const query = useMobileBootstrap();
  const navigate = useNavigate();
  const apps = useMemo(() => capFavorites(query.data?.favoriteApps ?? []), [query.data]);
  const processes = useMemo(() => capRecents(query.data?.recentProcesses ?? []), [query.data]);
  const userName = query.data?.user.displayName ?? "";
  const pendingCount = query.data?.pendingCount ?? 0;

  if (query.isPending) {
    return <PageSkeleton rows={5} />;
  }

  if (query.isError) {
    return (
      <PageError
        title="工作台加载失败"
        message="请稍后重试。"
        onRetry={() => {
          void query.refetch();
        }}
      />
    );
  }

  return (
    <main className="af-page" data-testid="workbench">
      <header className="af-head-bar">
        <div>
          <h3>工作台</h3>
          <small>AntFlow 科技</small>
        </div>
        <div className="af-avatar" aria-hidden="true">
          {userName.slice(0, 1)}
        </div>
      </header>

      <section className="af-hero af-fade-in" aria-label="问候">
        <div>
          <b>
            {greeting(new Date())}，{userName || "同事"}
          </b>
          <small>高效完成今天的工作</small>
        </div>
        <span className="af-hero__badge">{pendingCount} 项待办</span>
      </section>

      <section className="af-card af-fade-in" aria-label="常用应用">
        <div className="af-card__title">
          <span>常用应用</span>
          <button
            type="button"
            className="af-link-button"
            onClick={() => navigate("/apps")}
            style={{ fontSize: 11, fontWeight: 400 }}
          >
            全部应用 {"\u203A"}
          </button>
        </div>
        <AppGrid apps={apps} onSelect={(app) => navigate(`/forms/${encodeURIComponent(app.code)}`)} />
      </section>

      <section className="af-card af-fade-in" aria-label="最近流程">
        <div className="af-card__title">
          <span>最近流程</span>
          <button
            type="button"
            className="af-link-button"
            onClick={() => navigate("/tasks?view=process")}
            style={{ fontSize: 11, fontWeight: 400 }}
          >
            查看全部 {"\u203A"}
          </button>
        </div>
        <RecentProcesses processes={processes} />
      </section>
    </main>
  );
}

export default WorkbenchPage;
