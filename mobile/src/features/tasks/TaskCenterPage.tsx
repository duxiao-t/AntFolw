import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { queryKeys } from '../../shared/api/queryKeys';
import { AppPage } from '../../shared/ui/AppPage';
import { PageEmpty, PageError, PageSkeleton } from '../../shared/ui/PageStates';
import { TaskCard } from './TaskCard';
import { TaskFilters } from './TaskFilters';
import { fetchTaskCenterItems, type TaskView } from './tasks.api';

const PAGE_SIZE = 20;

const listStyle: React.CSSProperties = {
  display: 'grid',
  gap: 10,
};

const contentStyle: React.CSSProperties = {
  display: 'grid',
  gap: 12,
};

export function TaskCenterPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const view = parseView(searchParams.get('view'));
  const keyword = searchParams.get('keyword') ?? '';
  const status = searchParams.get('status') ?? undefined;
  const [visiblePages, setVisiblePages] = useState(1);

  const filters = useMemo(() => ({
    view,
    keyword: keyword.trim() || undefined,
    status,
    page: visiblePages,
    size: PAGE_SIZE,
  }), [keyword, status, view, visiblePages]);
  const pageFilters = useMemo(
    () => Array.from({ length: visiblePages }, (_, index) => ({
      view,
      keyword: keyword.trim() || undefined,
      status,
      page: index + 1,
      size: PAGE_SIZE,
    })),
    [keyword, status, view, visiblePages],
  );
  const returnSearch = useMemo(
    () => returnParams({ view, keyword, status }).toString(),
    [keyword, status, view],
  );

  const query = useQuery({
    queryKey: queryKeys.tasks(filters),
    queryFn: async () => {
      const pages = await Promise.all(pageFilters.map((entry) => fetchTaskCenterItems(entry)));
      return {
        items: pages.flatMap((page) => page.items),
        hasMore: pages.at(-1)?.hasMore ?? false,
      };
    },
    retry: 0,
  });

  return (
    <AppPage
      title="任务中心"
      description="待办、已处理和我发起的流程"
      toolbar={
        <div style={{ marginTop: 12 }}>
          <TaskFilters
            view={view}
            keyword={keyword}
            status={status ?? ''}
            onViewChange={changeView}
            onKeywordChange={changeKeyword}
            onStatusChange={changeStatus}
          />
        </div>
      }
    >
      <div style={contentStyle}>
        {query.isPending ? <PageSkeleton rows={5} /> : null}
        {query.isError ? <PageError onRetry={() => void query.refetch()} /> : null}
        {query.data && query.data.items.length === 0 ? (
          <PageEmpty title={emptyTitle(view)} hint="筛选条件会自动保留，返回后仍可继续查看。" />
        ) : null}
        {query.data && query.data.items.length > 0 ? (
          <div style={listStyle}>
            {query.data.items.map((item) => (
              <TaskCard
                key={item.kind === 'task' ? `task-${item.task.id}` : `process-${item.process.id}`}
                item={item}
                returnSearch={returnSearch}
              />
            ))}
            {query.data.hasMore ? (
              <LoadMoreButton onClick={() => setVisiblePages((current) => current + 1)} />
            ) : null}
          </div>
        ) : null}
      </div>
    </AppPage>
  );

  function changeView(nextView: TaskView) {
    setVisiblePages(1);
    setSearchParams(nextParams({
      view: nextView,
      keyword,
      status: isStatusAllowedForView(status, nextView) ? status : undefined,
    }));
  }

  function changeKeyword(nextKeyword: string) {
    setVisiblePages(1);
    setSearchParams(nextParams({ view, keyword: nextKeyword, status }));
  }

  function changeStatus(nextStatus: string) {
    setVisiblePages(1);
    setSearchParams(nextParams({ view, keyword, status: nextStatus || undefined }));
  }
}

function LoadMoreButton({ onClick }: { onClick: () => void }) {
  return (
    <div style={{ display: 'grid', gap: 8, justifyItems: 'center' }}>
      <p style={{ margin: '4px 0 0', color: 'rgba(0,0,0,0.55)', textAlign: 'center' }}>
        还有更多任务，请继续下拉加载
      </p>
      <button
        type="button"
        onClick={onClick}
        style={{
          minHeight: 40,
          padding: '6px 16px',
          borderRadius: 8,
          border: '1px solid var(--af-color-border)',
          background: 'var(--af-color-surface)',
          color: 'var(--af-color-text)',
          font: 'inherit',
          cursor: 'pointer',
        }}
      >
        加载更多
      </button>
    </div>
  );
}

function returnParams({ view, keyword, status }: { view: TaskView; keyword: string; status?: string }) {
  const params = new URLSearchParams();
  params.set('returnView', view);
  if (keyword.trim()) {
    params.set('returnKeyword', keyword.trim());
  }
  if (status) {
    params.set('returnStatus', status);
  }
  return params;
}

function parseView(value: string | null): TaskView {
  return value === 'done' || value === 'process' ? value : 'pending';
}

function nextParams({ view, keyword, status }: { view: TaskView; keyword: string; status?: string }) {
  const params = new URLSearchParams();
  params.set('view', view);
  if (keyword.trim()) {
    params.set('keyword', keyword.trim());
  }
  if (status) {
    params.set('status', status);
  }
  return params;
}

function emptyTitle(view: TaskView) {
  if (view === 'done') {
    return '暂无已处理任务';
  }
  if (view === 'process') {
    return '暂无发起流程';
  }
  return '暂无待办任务';
}

function isStatusAllowedForView(status: string | undefined, view: TaskView) {
  if (!status) {
    return true;
  }
  if (view === 'done') {
    return ['APPROVED', 'REJECTED', 'SKIPPED', 'CC'].includes(status);
  }
  return ['RUNNING', 'APPROVED', 'REJECTED', 'WITHDRAWN'].includes(status);
}

export default TaskCenterPage;
