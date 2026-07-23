import { useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AppPage } from "../../shared/ui/AppPage";
import { PageEmpty, PageError } from "../../shared/ui/PageStates";
import { apiRequest } from "../../shared/api/http";
import { queryKeys } from "../../shared/api/queryKeys";
import { useFavoriteDraftStore } from "./apps.store";
import type { MobileApp } from "../../shared/api/types";
import { useMobileBootstrap } from "./workbench.api";

async function saveFavorites(ids: number[]): Promise<void> {
  await apiRequest("/api/mobile/preferences/apps", {
    method: "PUT",
    body: JSON.stringify({ formIds: ids }),
  });
}

function fallbackApp(id: number): MobileApp {
  return { formId: id, code: `unknown-${id}`, name: `应用 ${id}`, category: "other", categoryLabel: "其他" };
}

function initials(name: string): string {
  const trimmed = name.trim();
  if (!trimmed) return "?";
  return trimmed.charAt(0);
}

export function FavoriteAppsPage() {
  const navigate = useNavigate();
  const draft = useFavoriteDraftStore();
  const queryClient = useQueryClient();
  const bootstrapQuery = useMobileBootstrap();
  const catalogQuery = useQuery({
    queryKey: queryKeys.apps({}),
    queryFn: () => apiRequest<MobileApp[]>("/api/mobile/apps"),
    enabled: draft.ids.length > 0,
    retry: 0,
    refetchOnWindowFocus: false,
  });

  const mutation = useMutation({
    mutationFn: saveFavorites,
    onSuccess: async () => {
      draft.markClean();
      await queryClient.invalidateQueries({ queryKey: queryKeys.bootstrap });
      navigate("/workbench");
    },
  });

  useEffect(() => {
    if (!bootstrapQuery.data?.favoriteApps) return;
    const ids = bootstrapQuery.data.favoriteApps.map((app) => app.formId);
    draft.syncSource(ids);
  }, [bootstrapQuery.data, draft]);

  const catalogMap = new Map((catalogQuery.data ?? []).map((app) => [app.formId, app]));
  const items: ReadonlyArray<MobileApp> = draft.ids.map((id) => catalogMap.get(id) ?? fallbackApp(id));

  function handleMove(from: number, to: number) {
    draft.move(from, to);
  }

  return (
    <AppPage
      title="管理常用应用"
      action={
        <button
          type="button"
          className="af-link-button"
          onClick={() => {
            if (!draft.isDirty) {
              navigate("/apps");
              return;
            }
            mutation.mutate(Array.from(draft.ids));
          }}
          style={{ fontSize: 13 }}
        >
          完成
        </button>
      }
    >
      {mutation.isError ? (
        <p role="alert" style={{ margin: "0 0 12px", color: "var(--af-color-danger)", fontSize: 13 }}>
          保存失败，请重试
        </p>
      ) : null}
      {catalogQuery.isError && draft.ids.length > 0 ? (
        <PageError
          title="常用应用加载失败"
          message="请稍后重试。"
          onRetry={() => void catalogQuery.refetch()}
        />
      ) : null}
      {!catalogQuery.isError && items.length === 0 ? (
        <PageEmpty title="还没有常用应用" hint="回到应用目录先选几个吧" />
      ) : null}
      {!catalogQuery.isError && items.length > 0 ? (
        <section className="af-card">
          <div className="af-card__title">
            <span>已添加</span>
            <small style={{ fontSize: 10, fontWeight: 400 }}>拖动排序</small>
          </div>
          <ul style={{ listStyle: "none", margin: 0, padding: 0, display: "grid", gap: 8 }}>
            {items.map((app, index) => (
              <li
                key={app.formId}
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: 10,
                  padding: "8px 4px",
                  borderTop: index === 0 ? "0" : "1px solid var(--af-color-line)",
                }}
              >
                <span className="af-app-grid__icon" style={{ width: 32, height: 32, fontSize: 13 }}>
                  {initials(app.name)}
                </span>
                <span style={{ flex: 1, fontSize: 13, fontWeight: 500 }}>{app.name}</span>
                <button
                  type="button"
                  onClick={() => handleMove(index, index - 1)}
                  aria-label="上移"
                  disabled={index === 0}
                  style={{
                    width: 36,
                    height: 28,
                    borderRadius: 6,
                    border: "1px solid var(--af-color-input-border)",
                    background: "var(--af-color-surface)",
                    cursor: index === 0 ? "not-allowed" : "pointer",
                    opacity: index === 0 ? 0.4 : 1,
                  }}
                >
                  {"\u2191"}
                </button>
                <button
                  type="button"
                  onClick={() => handleMove(index, index + 1)}
                  aria-label="下移"
                  disabled={index === items.length - 1}
                  style={{
                    width: 36,
                    height: 28,
                    borderRadius: 6,
                    border: "1px solid var(--af-color-input-border)",
                    background: "var(--af-color-surface)",
                    cursor: index === items.length - 1 ? "not-allowed" : "pointer",
                    opacity: index === items.length - 1 ? 0.4 : 1,
                  }}
                >
                  {"\u2193"}
                </button>
                <button
                  type="button"
                  onClick={() => {
                    if (window.confirm(`确定从常用应用移除「${app.name}」吗？`)) draft.remove(app.formId);
                  }}
                  aria-label="移除"
                  style={{
                    minHeight: 28,
                    padding: "0 10px",
                    borderRadius: 6,
                    border: "1px solid var(--af-color-input-border)",
                    background: "var(--af-color-surface)",
                    color: "var(--af-color-danger)",
                    cursor: "pointer",
                  }}
                >
                  {"\u00D7"}
                </button>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </AppPage>
  );
}

export default FavoriteAppsPage;
