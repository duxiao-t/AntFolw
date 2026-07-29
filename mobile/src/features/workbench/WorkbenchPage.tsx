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
import { AppPage } from "../../shared/ui/AppPage";

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
    <AppPage back={false} flush>
      <div className="af-page af-workbench" data-testid="workbench">
        <section className="af-workbench__top" aria-label="工作台概览">
          <header className="af-head-bar">
            <div>
              <h3>工作台</h3>
              <small>AntFlow 科技</small>
            </div>
            <button
              type="button"
              className="af-avatar af-avatar--button"
              aria-label="进入我的"
              onClick={() => navigate("/profile")}
            >
              {userName.slice(0, 1) || "A"}
            </button>
          </header>

          <div className="af-workbench__greeting">
            <div className="af-workbench__today">
              <span>{greeting(new Date())}</span>
              <h2>{userName || "同事"}</h2>
              <p>审批、发起和跟进流程集中处理。</p>
            </div>
            <button type="button" className="af-workbench__todo" onClick={() => navigate("/tasks")}>
              <b>{pendingCount}</b>
              <small>待办</small>
            </button>
          </div>

          <div className="af-workbench__metrics" aria-label="快捷统计">
            <button type="button" onClick={() => navigate("/apps")}>
              <b>{apps.length}</b>
              <span>常用表单</span>
            </button>
            <button type="button" onClick={() => navigate("/tasks?view=process")}>
              <b>{processes.length}</b>
              <span>最近流程</span>
            </button>
            <button type="button" onClick={() => navigate("/forms/drafts")}>
              <b>草稿</b>
              <span>继续填写</span>
            </button>
          </div>
        </section>

        <section className="af-section af-fade-in" aria-label="常用应用">
          <div className="af-section__title">
            <div>
              <h3>常用应用</h3>
              <small>选择表单快速发起</small>
            </div>
            <button type="button" className="af-link-button" onClick={() => navigate("/apps")}>
              全部
            </button>
          </div>
          <AppGrid apps={apps} onSelect={(app) => navigate(`/forms/${encodeURIComponent(app.code)}`)} />
        </section>

        <section className="af-section af-fade-in" aria-label="最近流程">
          <div className="af-section__title">
            <div>
              <h3>最近流程</h3>
              <small>跟进发起后的审批状态</small>
            </div>
            <button type="button" className="af-link-button" onClick={() => navigate("/tasks?view=process")}>
              查看
            </button>
          </div>
          <RecentProcesses processes={processes} />
        </section>
      </div>
    </AppPage>
  );
}

export default WorkbenchPage;
