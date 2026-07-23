import { useEffect, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { apiRequest } from "../../shared/api/http";
import { queryKeys } from "../../shared/api/queryKeys";
import type { MobileApp, AppFilters } from "../../shared/api/types";
import { AppPage } from "../../shared/ui/AppPage";
import { PageEmpty, PageError, PageSkeleton } from "../../shared/ui/PageStates";
import { useMobileBootstrap } from "./workbench.api";

const SEARCH_DEBOUNCE_MS = 250;

export async function fetchAppCatalog(filters: AppFilters): Promise<MobileApp[]> {
  const params = new URLSearchParams();
  if (filters.keyword) params.set("keyword", filters.keyword);
  if (filters.category) params.set("category", filters.category);
  const query = params.toString();
  return apiRequest<MobileApp[]>(`/api/mobile/apps${query ? `?${query}` : ""}`);
}

export function useAppCatalog(filters: AppFilters) {
  return useQuery({
    queryKey: queryKeys.apps(filters),
    queryFn: () => fetchAppCatalog(filters),
    staleTime: 30_000,
    refetchOnWindowFocus: false,
    retry: 0,
  });
}

function useDebouncedValue<T>(value: T, delay: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const handle = window.setTimeout(() => setDebounced(value), delay);
    return () => window.clearTimeout(handle);
  }, [value, delay]);
  return debounced;
}

function initials(name: string): string {
  const trimmed = name.trim();
  if (!trimmed) return "?";
  return trimmed.charAt(0);
}

export function AppCatalogPage() {
  const navigate = useNavigate();
  const [keyword] = useState("");
  const [category, setCategory] = useState<string | undefined>(undefined);
  const debouncedKeyword = useDebouncedValue(keyword, SEARCH_DEBOUNCE_MS);

  const filters: AppFilters = useMemo(
    () => ({ keyword: debouncedKeyword.trim() || undefined, category }),
    [debouncedKeyword, category],
  );

  const query = useAppCatalog(filters);
  const bootstrapQuery = useMobileBootstrap();
  const recentIds = useMemo(() => {
    const ids = bootstrapQuery.data?.recentProcesses ?? [];
    return Array.from(new Set(ids.map((p) => p.formCode)));
  }, [bootstrapQuery.data]);

  const apps = query.data ?? [];

  const groupedApps = useMemo(() => {
    const groups = new Map<string, MobileApp[]>();
    apps.forEach((app) => {
      const code = app.category?.trim() || "other";
      const list = groups.get(code) ?? [];
      list.push(app);
      groups.set(code, list);
    });
    return Array.from(groups, ([code, list]) => ({ code, label: list[0]?.categoryLabel?.trim() || (code === "other" ? "\u5176\u4ed6" : code), apps: list }));
  }, [apps]);

  const recentApps = useMemo(
    () => apps.filter((app) => recentIds.includes(app.code)).slice(0, 4),
    [apps, recentIds],
  );

  if (query.isPending) {
    return (
      <AppPage title="全部应用" variant="default">
        <PageSkeleton rows={6} />
      </AppPage>
    );
  }

  if (query.isError) {
    return (
      <AppPage title="全部应用">
        <PageError onRetry={() => void query.refetch()} />
      </AppPage>
    );
  }

  const noResults = apps.length === 0;
  const allCategories = Array.from(
    new Map(groupedApps.map((g) => [g.code, g.label])).entries(),
  );

  return (
    <AppPage
      title="全部应用"
      action={
        <button
          type="button"
          className="af-link-button"
          onClick={() => navigate("/apps/favorites")}
          style={{ fontSize: 13 }}
        >
          管理
        </button>
      }
    >
      <search className="af-search">{"\u2315 搜索应用"}</search>
      <div className="af-chips" role="tablist" aria-label="分类">
        <button
          type="button"
          role="tab"
          aria-selected={category === undefined}
          className={category === undefined ? "is-active" : ""}
          onClick={() => setCategory(undefined)}
          style={{ border: 0 }}
        >
          全部
        </button>
        {allCategories.map((entry) => (
          <button
            key={entry[0]}
            type="button"
            role="tab"
            aria-selected={category === entry[0]}
            className={category === entry[0] ? "is-active" : ""}
            onClick={() => setCategory(entry[0])}
            style={{ border: 0 }}
          >
            {entry[1]}
          </button>
        ))}
      </div>

      {noResults ? (
        <PageEmpty title="没有匹配的应用" hint="调整关键字或分类" />
      ) : (
        <div className="af-stack">
          {recentApps.length > 0 ? (
            <section className="af-card">
              <div className="af-card__title">最近使用</div>
              <ul className="af-app-grid" aria-label="最近使用" style={{ listStyle: "none", margin: 0, padding: 0 }}>
                {recentApps.map((app) => (
                  <li key={app.formId} style={{ display: "contents" }}>
                    <button
                      type="button"
                      className="af-app-grid__tile"
                      onClick={() => navigate(`/forms/${encodeURIComponent(app.code)}`)}
                    >
                      <span className="af-app-grid__icon">{initials(app.name)}</span>
                      <span className="af-app-grid__name">{app.name}</span>
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          ) : null}

          {groupedApps.map((group) => (
            <section key={group.code} className="af-card">
              <div className="af-card__title">{group.label}</div>
              <ul className="af-app-grid" aria-label={group.label} style={{ listStyle: "none", margin: 0, padding: 0 }}>
                {group.apps.map((app) => (
                  <li key={app.formId} style={{ display: "contents" }}>
                    <button
                      type="button"
                      className="af-app-grid__tile"
                      onClick={() => navigate(`/forms/${encodeURIComponent(app.code)}`)}
                    >
                      <span className="af-app-grid__icon">{initials(app.name)}</span>
                      <span className="af-app-grid__name">{app.name}</span>
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </div>
      )}
    </AppPage>
  );
}

export default AppCatalogPage;
