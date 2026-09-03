import { useEffect, useMemo, useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { apiRequest } from "../../shared/api/http";
import { queryKeys } from "../../shared/api/queryKeys";
import type { AppFilters, MobileApp } from "../../shared/api/types";
import { AppPage } from "../../shared/ui/AppPage";
import { PageEmpty, PageError, PageSkeleton } from "../../shared/ui/PageStates";

const SEARCH_DEBOUNCE_MS = 250;

export async function fetchAppCatalog(filters: AppFilters) {
  const params = new URLSearchParams();
  if (filters.keyword) params.set("keyword", filters.keyword);
  if (filters.category) params.set("category", filters.category);
  const query = params.toString();
  return apiRequest<MobileApp[]>(`/api/mobile/apps${query ? `?${query}` : ""}`);
}

export function useAppCatalog(filters: AppFilters) {
  return useQuery({ queryKey: queryKeys.apps(filters), queryFn: () => fetchAppCatalog(filters), staleTime: 30_000, refetchOnWindowFocus: false, retry: 0, placeholderData: keepPreviousData });
}

export function AppCatalogPage() {
  const navigate = useNavigate();
  const [keyword, setKeyword] = useState("");
  const [category, setCategory] = useState("");
  const debouncedKeyword = useDebouncedValue(keyword, SEARCH_DEBOUNCE_MS);
  const query = useAppCatalog({ keyword: debouncedKeyword.trim() || undefined });
  const apps = query.data ?? [];
  const groups = useMemo(() => {
    const filtered = category ? apps.filter((app) => (app.category?.trim() || "other") === category) : apps;
    const map = new Map<string, MobileApp[]>();
    filtered.forEach((app) => { const code = app.category?.trim() || "other"; map.set(code, [...(map.get(code) ?? []), app]); });
    return Array.from(map, ([code, values]) => ({ code, label: values[0]?.categoryLabel?.trim() || (code === "other" ? "其他" : code), apps: values }));
  }, [apps, category]);
  const categories = useMemo(() => Array.from(new Map(apps.map((app) => [app.category?.trim() || "other", app.categoryLabel?.trim() || app.category?.trim() || "其他"])), ([code, label]) => ({ code, label })), [apps]);

  if (query.isPending) return <PageSkeleton rows={6} />;
  if (query.isError) return <PageError onRetry={() => void query.refetch()} />;

  return (
    <AppPage title="全部应用" contentClassName="catalog-page" action={<button type="button" className="app-bar__action" aria-label="查看收藏" onClick={() => navigate("/apps/favorites")}><StarIcon /></button>}>
      <label className="searchbar catalog-search"><SearchIcon /><input value={keyword} onChange={(event) => setKeyword(event.currentTarget.value)} placeholder="搜索表单或应用" aria-label="搜索表单或应用" /></label>
      <div className="chip-row catalog-filter">
        <button className={`chip${category === "" ? " is-active" : ""}`} type="button" onClick={() => setCategory("")}>全部</button>
        {categories.map((item) => <button key={item.code} className={`chip${category === item.code ? " is-active" : ""}`} type="button" onClick={() => setCategory(item.code)}>{item.label}</button>)}
      </div>
      {groups.length === 0 ? <PageEmpty title="没有匹配的应用" hint="调整搜索词或分类后重试。" /> : groups.map((group) => (
        <section className="catalog-section" key={group.code}>
          <header className="catalog-section__head"><h2>{group.label}</h2><span>{group.apps.length} 个应用</span></header>
          <div className="catalog-card-grid">
            {group.apps.map((app) => <button key={app.formId} className="catalog-app-card" type="button" onClick={() => navigate(`/forms/${encodeURIComponent(app.code)}`)}><span className={`catalog-app-card__icon ${glyphTone(group.label)}`}>{app.iconUrl ? <img src={app.iconUrl} alt="" /> : app.name.trim().charAt(0) || "?"}</span><span className="catalog-app-card__name">{app.name}</span><span className="catalog-app-card__chev">›</span></button>)}
          </div>
        </section>
      ))}
    </AppPage>
  );
}

function useDebouncedValue<T>(value: T, delay: number) { const [debounced, setDebounced] = useState(value); useEffect(() => { const handle = window.setTimeout(() => setDebounced(value), delay); return () => window.clearTimeout(handle); }, [delay, value]); return debounced; }
function glyphTone(label: string) { if (/财务/.test(label)) return "app-glyph--finance"; if (/人事/.test(label)) return "app-glyph--people"; if (/IT|技术/.test(label)) return "app-glyph--it"; if (/业务|运营|采购/.test(label)) return "app-glyph--operations"; return "app-glyph--admin"; }
function SearchIcon() { return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><circle cx="11" cy="11" r="7" /><path d="m21 21-4.3-4.3" /></svg>; }
function StarIcon() { return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M12 17.3 6.18 21l1.64-7.03L2 9.74l7.19-.61L12 2.5l2.81 6.63L22 9.74l-5.82 4.23L17.82 21z" /></svg>; }

export default AppCatalogPage;
