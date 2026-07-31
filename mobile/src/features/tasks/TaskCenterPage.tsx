import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useSearchParams } from "react-router-dom";
import { queryKeys } from "../../shared/api/queryKeys";
import { AppPage } from "../../shared/ui/AppPage";
import { PageEmpty, PageError, PageSkeleton } from "../../shared/ui/PageStates";
import { TaskCard } from "./TaskCard";
import { TaskFilters } from "./TaskFilters";
import { fetchTaskCenterItems, type TaskView } from "./tasks.api";

const PAGE_SIZE = 20;
const tabs: Array<{ key: TaskView; label: string }> = [
  { key: "pending", label: "待我处理" },
  { key: "process", label: "我发起的" },
  { key: "done", label: "已处理" },
];

export function TaskCenterPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const view = parseView(searchParams.get("view"));
  const keyword = searchParams.get("keyword") ?? "";
  const status = searchParams.get("status") ?? "";
  const [visiblePages, setVisiblePages] = useState(1);
  const pageFilters = useMemo(() => Array.from({ length: visiblePages }, (_, index) => ({ view, keyword: keyword.trim() || undefined, status: status || undefined, page: index + 1, size: PAGE_SIZE })), [keyword, status, view, visiblePages]);
  const filters = useMemo(() => ({ view, keyword: keyword.trim() || undefined, status: status || undefined, page: visiblePages, size: PAGE_SIZE }), [keyword, status, view, visiblePages]);
  const returnSearch = useMemo(() => returnParams({ view, keyword, status }).toString(), [keyword, status, view]);
  const query = useQuery({
    queryKey: queryKeys.tasks(filters),
    queryFn: async () => {
      const pages = await Promise.all(pageFilters.map(fetchTaskCenterItems));
      return { items: pages.flatMap((page) => page.items), hasMore: pages.at(-1)?.hasMore ?? false };
    },
    retry: 0,
  });

  const heading = view === "pending" ? "需要你处理的审批" : view === "process" ? "我发起的流程" : "已处理";
  const subtitle = view === "pending" ? `共 ${query.data?.items.length ?? 0} 单 · 按时间排序` : view === "process" ? `共 ${query.data?.items.length ?? 0} 条 · 跟进审批状态` : `近 30 天 · 共 ${query.data?.items.length ?? 0} 条`;

  return (
    <AppPage title="待办" back={false} tabbar action={<button className="app-bar__action" type="button" aria-label="排序"><SortIcon /></button>}>
      <section style={{ margin: "4px 0 14px" }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", marginBottom: 10 }}>
          <div><h3 style={{ fontSize: 22, fontWeight: 800, margin: 0 }}>{heading}</h3><small style={{ color: "var(--af-color-muted)", fontSize: 12 }}>{subtitle}</small></div>
          {view === "pending" ? <span className="chip chip--soft">{query.data?.items.length ?? 0}</span> : null}
        </div>

        <div className="tabs" role="tablist" aria-label="任务视图">
          {tabs.map((tab) => <button key={tab.key} type="button" role="tab" aria-selected={view === tab.key} className={`tab${view === tab.key ? " is-active" : ""}`} onClick={() => changeView(tab.key)}>{tab.label}</button>)}
        </div>
        <TaskFilters keyword={keyword} status={status} view={view} onKeywordChange={changeKeyword} onStatusChange={changeStatus} />
      </section>

      <section style={{ display: "grid", gap: 10 }}>
        {query.isPending ? <PageSkeleton rows={5} /> : null}
        {query.isError ? <PageError onRetry={() => void query.refetch()} /> : null}
        {query.data?.items.length === 0 ? <PageEmpty title={emptyTitle(view)} hint="选择表单发起后，流程会集中显示在这里。" /> : null}
        {query.data?.items.map((item) => <TaskCard key={item.kind === "task" ? `task-${item.task.id}` : `process-${item.process.id}`} item={item} returnSearch={returnSearch} />)}
        {query.data?.hasMore ? <button type="button" className="btn btn--ghost btn--block" style={{ marginTop: 6 }} onClick={() => setVisiblePages((current) => current + 1)}>还有更多，请继续下拉加载</button> : null}
      </section>
      <div className="spacer-24" />
    </AppPage>
  );

  function changeView(nextView: TaskView) {
    setVisiblePages(1);
    setSearchParams(nextParams({ view: nextView, keyword, status: "" }));
  }
  function changeKeyword(nextKeyword: string) {
    setVisiblePages(1);
    setSearchParams(nextParams({ view, keyword: nextKeyword, status }));
  }
  function changeStatus(nextStatus: string) {
    setVisiblePages(1);
    setSearchParams(nextParams({ view, keyword, status: nextStatus }));
  }
}

function SortIcon() {
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M3 6h11M3 12h7M3 18h3" /><path d="M17 4v16M14 7l3-3 3 3" /></svg>;
}
function parseView(value: string | null): TaskView { return value === "done" || value === "process" ? value : "pending"; }
function nextParams({ view, keyword, status }: { view: TaskView; keyword: string; status: string }) { const params = new URLSearchParams({ view }); if (keyword.trim()) params.set("keyword", keyword.trim()); if (status) params.set("status", status); return params; }
function returnParams({ view, keyword, status }: { view: TaskView; keyword: string; status: string }) { const params = new URLSearchParams({ returnView: view }); if (keyword.trim()) params.set("returnKeyword", keyword.trim()); if (status) params.set("returnStatus", status); return params; }
function emptyTitle(view: TaskView) { return view === "done" ? "暂无已处理任务" : view === "process" ? "还没有发起的流程" : "暂无待办任务"; }

export default TaskCenterPage;
