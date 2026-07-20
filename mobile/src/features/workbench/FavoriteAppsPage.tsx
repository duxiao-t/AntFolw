import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AppPage } from '../../shared/ui/AppPage';
import { PageEmpty, PageError } from '../../shared/ui/PageStates';
import { apiRequest } from '../../shared/api/http';
import { queryKeys } from '../../shared/api/queryKeys';
import { useFavoriteDraftStore } from './apps.store';
import type { MobileApp } from '../../shared/api/types';
import { useMobileBootstrap } from './workbench.api';

async function saveFavorites(ids: number[]): Promise<void> {
  await apiRequest('/api/mobile/preferences/apps', {
    method: 'PUT',
    body: JSON.stringify({ formIds: ids }),
  });
}

const baseItemStyle: React.CSSProperties = {
  minHeight: 32,
  padding: '4px 12px',
  borderRadius: 6,
  border: '1px solid var(--af-color-border)',
  background: 'var(--af-color-surface)',
  cursor: 'pointer',
  fontFamily: 'inherit',
  fontSize: 'inherit',
};

const ghostStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 44,
  padding: '8px 16px',
  borderRadius: 'var(--af-radius-surface)',
  border: '1px solid var(--af-color-border)',
  background: 'var(--af-color-surface)',
  cursor: 'pointer',
  fontFamily: 'inherit',
  fontSize: 'inherit',
};

function primaryStyle(disabled: boolean): React.CSSProperties {
  return {
    flex: 1,
    minHeight: 44,
    padding: '8px 16px',
    borderRadius: 'var(--af-radius-surface)',
    border: 'none',
    background: disabled ? 'rgba(22,119,255,0.4)' : 'var(--af-color-primary)',
    color: 'var(--af-color-on-primary)',
    cursor: disabled ? 'not-allowed' : 'pointer',
    fontFamily: 'inherit',
    fontSize: 'inherit',
  };
}

function fallbackApp(id: number): MobileApp {
  return { formId: id, code: `unknown-${id}`, name: `应用 ${id}`, category: 'other', categoryLabel: '其他' };
}

export function FavoriteAppsPage() {
  const navigate = useNavigate();
  const draft = useFavoriteDraftStore();
  const queryClient = useQueryClient();
  const bootstrapQuery = useMobileBootstrap();
  const catalogQuery = useQuery({
    queryKey: queryKeys.apps({}),
    queryFn: () => apiRequest<MobileApp[]>('/api/mobile/apps'),
    enabled: draft.ids.length > 0,
    retry: 0,
    refetchOnWindowFocus: false,
  });

  const mutation = useMutation({
    mutationFn: saveFavorites,
    onSuccess: async () => {
      draft.markClean();
      await queryClient.invalidateQueries({ queryKey: queryKeys.bootstrap });
      navigate('/workbench');
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
    <AppPage title="常用应用" description="拖动排序，完成保存后生效">
      {mutation.isError ? (
        <p
          role="alert"
          style={{
            margin: '0 0 12px',
            color: 'var(--af-color-danger)',
            fontSize: '0.875rem',
          }}
        >
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
        <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
          {items.map((app, index) => (
            <li
              key={app.formId}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 12,
                padding: '12px 16px',
                marginBottom: 8,
                background: 'var(--af-color-surface)',
                borderRadius: 'var(--af-radius-surface)',
              }}
            >
              <span style={{ flex: 1, fontWeight: 500 }}>{app.name}</span>
              <button
                type="button"
                onClick={() => handleMove(index, index - 1)}
                aria-label="上移"
                style={baseItemStyle}
                disabled={index === 0}
              >
                ↑
              </button>
              <button
                type="button"
                onClick={() => handleMove(index, index + 1)}
                aria-label="下移"
                style={baseItemStyle}
                disabled={index === items.length - 1}
              >
                ↓
              </button>
              <button
                type="button"
                onClick={() => {
                  if (window.confirm(`确定从常用应用移除「${app.name}」吗？`)) draft.remove(app.formId);
                }}
                aria-label="移除"
                style={baseItemStyle}
              >
                移除
              </button>
            </li>
          ))}
        </ul>
      ) : null}
      <div style={{ display: 'flex', gap: 12, marginTop: 16 }}>
        <button type="button" onClick={() => navigate('/apps')} style={ghostStyle}>
          返回选择
        </button>
        <button
          type="button"
          onClick={() => mutation.mutate(Array.from(draft.ids))}
          disabled={!draft.isDirty || mutation.isPending}
          style={primaryStyle(!draft.isDirty || mutation.isPending)}
        >
          完成
        </button>
      </div>
    </AppPage>
  );
}

export default FavoriteAppsPage;
