import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from 'antd-mobile';
import { Link } from 'react-router-dom';
import { AppPage } from '../../shared/ui/AppPage';
import { PageEmpty, PageError, PageSkeleton } from '../../shared/ui/PageStates';
import { queryKeys } from '../../shared/api/queryKeys';
import { useAuthStore } from '../auth/auth.store';
import { removeRecoveryDraft } from './recoveryDraft.store';
import { deleteMobileDraft, fetchMobileDrafts, type MobileDraft } from './drafts.api';
import type { MobileSchemaNode } from './schema/types';

export function DraftListPage() {
  const user = useAuthStore((state) => state.user);
  const queryClient = useQueryClient();
  const [deletedIds, setDeletedIds] = useState<number[]>([]);
  const draftsQuery = useQuery({
    queryKey: queryKeys.drafts,
    queryFn: fetchMobileDrafts,
    retry: 0,
  });
  const deleteMutation = useMutation({
    mutationFn: deleteMobileDraft,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.drafts });
    },
  });

  const drafts = useMemo(
    () => (draftsQuery.data ?? []).filter((draft) => !deletedIds.includes(draft.id)),
    [deletedIds, draftsQuery.data],
  );

  if (draftsQuery.isPending) {
    return <PageSkeleton rows={4} />;
  }

  if (draftsQuery.isError) {
    return <PageError onRetry={() => void draftsQuery.refetch()} />;
  }

  return (
    <AppPage title="草稿箱">
      {drafts.length === 0 ? (
        <PageEmpty title="暂无草稿" />
      ) : (
        <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'grid', gap: 8 }}>
          {drafts.map((draft) => {
            const count = completionCount(draft);
            return (
              <li
                key={draft.id}
                style={{
                  background: 'var(--af-color-surface)',
                  borderRadius: 'var(--af-radius-surface)',
                  padding: 12,
                  display: 'grid',
                  gap: 8,
                }}
              >
                <div style={{ display: 'grid', gap: 4 }}>
                  <strong>{draft.formName}</strong>
                  <span style={{ color: 'rgba(0,0,0,0.55)', fontSize: '0.8125rem' }}>
                    {formatUpdatedAt(draft.updatedAt)}
                  </span>
                  <span style={{ color: 'rgba(0,0,0,0.65)', fontSize: '0.875rem' }}>
                    已填写 {count.filled}/{count.total}
                  </span>
                </div>
                <div style={{ display: 'flex', gap: 8 }}>
                  <Link
                    aria-label={`继续填写 ${draft.formName}`}
                    to={`/forms/${draft.formCode}?draftId=${draft.id}`}
                    style={linkButtonStyle}
                  >
                    继续填写
                  </Link>
                  <Button
                    aria-label={`删除 ${draft.formName}`}
                    loading={deleteMutation.isPending}
                    onClick={() => deleteDraft(draft)}
                  >
                    删除
                  </Button>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </AppPage>
  );

  function deleteDraft(draft: MobileDraft) {
    if (!confirmDialog('确认删除该草稿？')) {
      return;
    }
    setDeletedIds((current) => [...current, draft.id]);
    if (user) {
      removeRecoveryDraft(user.id, draft.formCode, draft.id);
    }
    void deleteMutation.mutateAsync(draft.id);
  }
}

function completionCount(draft: MobileDraft) {
  const data = draft.data ?? {};
  const ids = draft.schema ? leafFieldIds(draft.schema) : Object.keys(data);
  const total = Math.max(ids.length, 1);
  const filled = ids.filter((id) => hasValue(data[id])).length;
  return { filled, total };
}

function leafFieldIds(schema: MobileSchemaNode[]) {
  const ids: string[] = [];
  for (const node of schema) {
    if (node.type === 'span_layout') {
      ids.push(...leafFieldIds(node.children ?? []));
      continue;
    }
    if (node.type === 'table_list') {
      ids.push(node.id);
      continue;
    }
    if (node.type !== 'description') {
      ids.push(node.id);
    }
  }
  return ids;
}

function hasValue(value: unknown) {
  if (value == null) {
    return false;
  }
  if (typeof value === 'string') {
    return value.trim().length > 0;
  }
  if (Array.isArray(value)) {
    return value.length > 0;
  }
  if (typeof value === 'object') {
    return Object.keys(value).length > 0;
  }
  return true;
}

function formatUpdatedAt(value: string | undefined) {
  if (!value) {
    return '未记录更新时间';
  }
  return value.replace('T', ' ').replace(/\+\d{2}:\d{2}$/, '');
}

const linkButtonStyle: React.CSSProperties = {
  minHeight: 44,
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  padding: '0 16px',
  borderRadius: 'var(--af-radius-control)',
  background: 'var(--af-color-primary)',
  color: 'var(--af-color-on-primary)',
  textDecoration: 'none',
};

function confirmDialog(message: string) {
  if (typeof window.confirm === 'function') {
    return window.confirm(message);
  }
  return true;
}

export default DraftListPage;
