import { useEffect } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { apiRequest } from "../../shared/api/http";
import { queryKeys } from "../../shared/api/queryKeys";
import type { MobileApp } from "../../shared/api/types";
import { AppPage } from "../../shared/ui/AppPage";
import { PageEmpty, PageError, PageSkeleton } from "../../shared/ui/PageStates";
import { useFavoriteDraftStore } from "./apps.store";
import { useMobileBootstrap } from "./workbench.api";

async function saveFavorites(ids: number[]) { await apiRequest("/api/mobile/preferences/apps", { method: "PUT", body: JSON.stringify({ formIds: ids }) }); }
function fallbackApp(id: number): MobileApp { return { formId: id, code: `unknown-${id}`, name: `应用 ${id}`, category: "other", categoryLabel: "其他" }; }

export function FavoriteAppsPage() {
  const navigate = useNavigate();
  const draft = useFavoriteDraftStore();
  const queryClient = useQueryClient();
  const bootstrapQuery = useMobileBootstrap();
  const catalogQuery = useQuery({ queryKey: queryKeys.apps({}), queryFn: () => apiRequest<MobileApp[]>("/api/mobile/apps"), retry: 0, refetchOnWindowFocus: false });
  const mutation = useMutation({ mutationFn: saveFavorites, onSuccess: async () => { draft.markClean(); await queryClient.invalidateQueries({ queryKey: queryKeys.bootstrap }); navigate("/workbench"); } });

  useEffect(() => { if (bootstrapQuery.data?.favoriteApps) draft.syncSource(bootstrapQuery.data.favoriteApps.map((app) => app.formId)); }, [bootstrapQuery.data, draft]);
  const catalogMap = new Map((catalogQuery.data ?? []).map((app) => [app.formId, app]));
  const items = draft.ids.map((id) => catalogMap.get(id) ?? fallbackApp(id));

  if ((bootstrapQuery.isPending || catalogQuery.isPending) && items.length === 0) return <PageSkeleton rows={5} />;

  return (
    <AppPage title="我的收藏" contentClassName="favorites-page" action={<button className="app-bar__action" type="button" disabled={mutation.isPending} onClick={() => { if (draft.isDirty) mutation.mutate(Array.from(draft.ids)); else navigate("/apps"); }}>{draft.isDirty ? "完成" : "编辑"}</button>}>
      <p className="muted small" style={{ margin: "4px 4px 14px" }}>最多收藏 8 个，使用箭头可重新排序。</p>
      {mutation.isError ? <p className="status-notice status-notice--danger" role="alert">保存失败，请重试</p> : null}
      {catalogQuery.isError ? <PageError title="常用应用加载失败" onRetry={() => void catalogQuery.refetch()} /> : null}
      {!catalogQuery.isError && items.length === 0 ? <PageEmpty title="还没有收藏" hint="从全部应用中选择常用表单。" action={<button className="btn btn--primary" type="button" onClick={() => navigate("/apps")}>浏览应用</button>} /> : null}
      {items.length > 0 ? <div className="reorder-list">{items.map((app, index) => <div className="reorder-item" key={app.formId}><span className="reorder-item__handle">⋮⋮</span><div className={`reorder-item__icon ${glyphTone(app.categoryLabel ?? app.category ?? "")}`}>{app.name.trim().charAt(0) || "?"}</div><div className="reorder-item__main"><b>{app.name}</b><small>{app.description || app.categoryLabel || "常用审批表单"}</small></div><div className="reorder-item__btns"><button type="button" aria-label={`上移 ${app.name}`} disabled={index === 0} onClick={() => draft.move(index, index - 1)}>↑</button><button type="button" aria-label={`下移 ${app.name}`} disabled={index === items.length - 1} onClick={() => draft.move(index, index + 1)}>↓</button></div></div>)}</div> : null}
      {items.length > 0 ? <><div className="spacer-24" /><div className="empty-hero"><div className="empty-hero__art"><svg className="favorites-summary-icon" viewBox="0 0 120 120" fill="none" aria-hidden="true"><circle className="favorites-summary-icon__disc" cx="60" cy="60" r="44" /><path className="favorites-summary-icon__check" d="M44 60l12 12 22-26" strokeWidth="5" strokeLinecap="round" strokeLinejoin="round" /></svg></div><h3>已添加 {items.length} 个收藏</h3><p>{items.length < 8 ? `还可以再添加 ${8 - items.length} 个常用表单。` : "已达到收藏数量上限。"}</p></div></> : null}
    </AppPage>
  );
}

function glyphTone(label: string) { if (/财务/.test(label)) return "app-glyph--finance"; if (/人事/.test(label)) return "app-glyph--people"; if (/IT|技术/.test(label)) return "app-glyph--it"; if (/业务|运营|采购/.test(label)) return "app-glyph--operations"; return "app-glyph--admin"; }

export default FavoriteAppsPage;
