import { useMemo } from "react";
import { useNavigate } from "react-router-dom";
import { AppPage } from "../../shared/ui/AppPage";
import { PageError, PageSkeleton } from "../../shared/ui/PageStates";
import { AppGrid } from "./components/AppGrid";
import { RecentProcesses } from "./components/RecentProcesses";
import { capFavorites, capRecents, useMobileBootstrap } from "./workbench.api";

function greeting(now: Date) {
  const hour = now.getHours();
  if (hour < 6) return "夜深了";
  if (hour < 11) return "早上好";
  if (hour < 13) return "中午好";
  if (hour < 18) return "下午好";
  return "晚上好";
}

function dateLabel(now: Date) {
  const weekdays = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"];
  return `${now.getMonth() + 1}月${now.getDate()}日 ${weekdays[now.getDay()]}`;
}

export function WorkbenchPage() {
  const query = useMobileBootstrap();
  const navigate = useNavigate();
  const apps = useMemo(() => capFavorites(query.data?.favoriteApps ?? []).slice(0, 7), [query.data]);
  const processes = useMemo(() => capRecents(query.data?.recentProcesses ?? []), [query.data]);
  const userName = query.data?.user.displayName ?? "";
  const pendingCount = query.data?.pendingCount ?? 0;
  const draftCount = query.data?.draftCount ?? 0;

  if (query.isPending) return <PageSkeleton rows={5} />;
  if (query.isError) return <PageError title="工作台加载失败" message="请检查网络连接后重试。" onRetry={() => void query.refetch()} />;

  return (
    <AppPage title="工作台" back={false} tabbar testId="workbench" action={<button className="app-bar__action" type="button" aria-label="搜索"><SearchIcon /></button>}>
      <section className="hero hero--bleed">
        <div className="hero__head"><small>AntFlow 科技 · {dateLabel(new Date())}</small><button className="avatar-btn" type="button" aria-label="我的" onClick={() => navigate("/profile")}>{userName.slice(0, 1) || "A"}</button></div>
        <div className="hero__greeting"><h2>{greeting(new Date())},{userName || "同事"}</h2><p>审批、发起和跟进流程集中处理。</p></div>
      </section>

      <section className="kpis" aria-label="快捷统计">
        <button className="stat-card stat-card--accent" type="button" onClick={() => navigate("/tasks")}><b>{pendingCount}</b><span>待办</span></button>
        <button className="stat-card" type="button" onClick={() => navigate("/tasks?view=process")}><b>{processes.length}</b><span>最近流程</span></button>
        <button className="stat-card" type="button" onClick={() => navigate("/forms/drafts")}><b>{draftCount}</b><span>草稿</span></button>
      </section>

      <section className="section">
        <div className="section__title"><div><h3>常用应用</h3><small>选择表单快速发起</small></div><button className="link" type="button" onClick={() => navigate("/apps")}>全部</button></div>
        <AppGrid apps={apps} onSelect={(app) => navigate(`/forms/${encodeURIComponent(app.code)}`)} onMore={() => navigate("/apps")} />
      </section>

      <section className="section">
        <div className="section__title"><div><h3>最近流程</h3><small>跟进发起后的审批状态</small></div><button className="link" type="button" onClick={() => navigate("/tasks?view=process")}>查看</button></div>
        <RecentProcesses processes={processes} />
      </section>
      <div className="spacer-24" />
    </AppPage>
  );
}

function SearchIcon() {
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><circle cx="11" cy="11" r="7" /><path d="m21 21-4.3-4.3" /></svg>;
}

export default WorkbenchPage;
