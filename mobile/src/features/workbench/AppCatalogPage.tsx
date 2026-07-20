import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Input } from 'antd-mobile';
import { useNavigate } from 'react-router-dom';
import { apiRequest } from '../../shared/api/http';
import { queryKeys } from '../../shared/api/queryKeys';
import type { MobileApp, AppFilters } from '../../shared/api/types';
import { AppPage } from '../../shared/ui/AppPage';
import { PageEmpty, PageError, PageSkeleton } from '../../shared/ui/PageStates';
import { FAVORITE_LIMIT_MESSAGE, useFavoriteDraftStore } from './apps.store';
import { useMobileBootstrap } from './workbench.api';

const SEARCH_DEBOUNCE_MS = 250;
const UNCATEGORISED_LABEL = '其他';
const UNCATEGORISED_CODE = 'other';

export async function fetchAppCatalog(filters: AppFilters): Promise<MobileApp[]> {
  const params = new URLSearchParams();
  if (filters.keyword) params.set('keyword', filters.keyword);
  if (filters.category) params.set('category', filters.category);
  const query = params.toString();
  return apiRequest<MobileApp[]>(`/api/mobile/apps${query ? `?${query}` : ''}`);
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

function categoryCode(app: MobileApp): string {
  return app.category?.trim() || UNCATEGORISED_CODE;
}

function categoryLabel(app: MobileApp): string {
  const code = categoryCode(app);
  return app.categoryLabel?.trim() || (code === UNCATEGORISED_CODE ? UNCATEGORISED_LABEL : code);
}

export function AppCatalogPage() {
  const navigate = useNavigate();
  const [keywordInput, setKeywordInput] = useState('');
  const [category, setCategory] = useState<string | undefined>(undefined);
  const debouncedKeyword = useDebouncedValue(keywordInput, SEARCH_DEBOUNCE_MS);

  const filters: AppFilters = useMemo(
    () => ({ keyword: debouncedKeyword.trim() || undefined, category }),
    [debouncedKeyword, category],
  );

  const query = useAppCatalog(filters);
  const bootstrapQuery = useMobileBootstrap();
  const draft = useFavoriteDraftStore();
  const favoriteIds = draft.ids;

  useEffect(() => {
    if (!bootstrapQuery.data?.favoriteApps) return;
    const ids = bootstrapQuery.data.favoriteApps.map((app) => app.formId);
    draft.syncSource(ids);
  }, [bootstrapQuery.data, draft]);

  const categories = useMemo(() => {
    const seen = new Set<string>();
    const labels = new Map<string, string>();
    (query.data ?? []).forEach((app) => {
      const code = categoryCode(app);
      seen.add(code);
      if (!labels.has(code)) labels.set(code, categoryLabel(app));
    });
    if (!seen.has(UNCATEGORISED_CODE)) labels.set(UNCATEGORISED_CODE, UNCATEGORISED_LABEL);
    return Array.from(labels, ([code, label]) => ({ code, label }));
  }, [query.data]);

  function toggleFavorite(app: MobileApp) {
    if (favoriteIds.includes(app.formId)) {
      draft.remove(app.formId);
      return;
    }
    const before = favoriteIds.length;
    draft.add(app.formId);
    if (useFavoriteDraftStore.getState().ids.length === before) return;
  }

  if (query.isPending) {
    return (
      <AppPage title="应用目录">
        <PageSkeleton rows={6} />
      </AppPage>
    );
  }

  if (query.isError) {
    return (
      <AppPage title="应用目录">
        <PageError onRetry={() => void query.refetch()} />
      </AppPage>
    );
  }

  const apps = query.data ?? [];
  const noResults = apps.length === 0;

  return (
    <AppPage
      title="应用目录"
      description={`搜索并选择最多 ${favoriteIds.length}/8 个常用应用`}
    >
      <Input
        placeholder="搜索应用"
        value={keywordInput}
        onChange={(value) => setKeywordInput(value)}
        aria-label="搜索应用"
      />
      {draft.reason === 'limit' ? (
        <p
          role="alert"
          style={{
            margin: '8px 0 0',
            color: 'var(--af-color-warning)',
            fontSize: '0.875rem',
          }}
        >
          {FAVORITE_LIMIT_MESSAGE}
        </p>
      ) : null}
      <div role="tablist" aria-label="分类" style={{ display: 'flex', gap: 8, margin: '12px 0' }}>
        <button
          type="button"
          role="tab"
          aria-selected={category === undefined}
          onClick={() => setCategory(undefined)}
          style={categoryButtonStyle(category === undefined)}
        >
          全部
        </button>
        {categories.map((entry) => (
          <button
            key={entry.code}
            type="button"
            role="tab"
            aria-selected={category === entry.code}
            onClick={() => setCategory(entry.code)}
            style={categoryButtonStyle(category === entry.code)}
          >
            {entry.label}
          </button>
        ))}
      </div>
      {noResults ? (
        <PageEmpty title="没有匹配的应用" hint="调整关键字或分类" />
      ) : (
        <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
          {apps.map((app) => (
            <li key={app.formId}>
              <button
                type="button"
                onClick={() => toggleFavorite(app)}
                aria-pressed={favoriteIds.includes(app.formId)}
                style={{
                  width: '100%',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  gap: 12,
                  padding: '12px 16px',
                  marginBottom: 8,
                  background: 'var(--af-color-surface)',
                  borderRadius: 'var(--af-radius-surface)',
                  border: 'none',
                  textAlign: 'left',
                  font: 'inherit',
                  color: 'inherit',
                  cursor: 'pointer',
                  minHeight: 56,
                }}
              >
                <span style={{ display: 'flex', flexDirection: 'column', gap: 4, flex: 1 }}>
                  <strong style={{ fontWeight: 500 }}>{app.name}</strong>
                  <span style={{ fontSize: '0.8125rem', color: 'rgba(0,0,0,0.55)' }}>
                    {app.description ?? categoryLabel(app)}
                  </span>
                </span>
                <span aria-hidden="true" style={{ color: favoriteIds.includes(app.formId) ? 'var(--af-color-primary)' : 'rgba(0,0,0,0.45)' }}>
                  {favoriteIds.includes(app.formId) ? '★ 已选' : '☆ 选择'}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
      <button
        type="button"
        onClick={() => navigate('/apps/favorites')}
        style={{
          marginTop: 16,
          width: '100%',
          minHeight: 44,
          padding: '8px 16px',
          borderRadius: 'var(--af-radius-surface)',
          border: 'none',
          background: 'var(--af-color-primary)',
          color: 'var(--af-color-on-primary)',
          fontSize: '1rem',
          cursor: 'pointer',
        }}
      >
        完成 ({favoriteIds.length}/8)
      </button>
    </AppPage>
  );
}

function categoryButtonStyle(active: boolean): React.CSSProperties {
  return {
    padding: '6px 12px',
    borderRadius: 999,
    border: active ? 'none' : '1px solid var(--af-color-border)',
    background: active ? 'var(--af-color-primary)' : 'var(--af-color-surface)',
    color: active ? 'var(--af-color-on-primary)' : 'var(--af-color-text)',
    fontSize: '0.8125rem',
    cursor: 'pointer',
    minHeight: 32,
  };
}


export default AppCatalogPage;
