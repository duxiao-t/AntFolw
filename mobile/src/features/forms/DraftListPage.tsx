import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { AppPage } from "../../shared/ui/AppPage";
import { PageEmpty, PageError, PageSkeleton } from "../../shared/ui/PageStates";
import { queryKeys } from "../../shared/api/queryKeys";
import { useAuthStore } from "../auth/auth.store";
import { removeRecoveryDraft } from "./recoveryDraft.store";
import { deleteMobileDraft, fetchMobileDrafts, type MobileDraft } from "./drafts.api";
import type { MobileSchemaNode } from "./schema/types";

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
        <ul style={{ listStyle: "none", margin: 0, padding: 0 }}>
          {drafts.map((draft) => {
            const count = completionCount(draft);
            return (
              <li key={draft.id} className="af-draft-card">
                <h4>{draft.formName}</h4>
                <p><span>{formatUpdatedAt(draft.updatedAt)}</span><span> · 已填写 {count.filled}/{count.total}</span></p>
                <div className="af-draft-card__actions">
                  <button
                    type="button"
                    aria-label={`删除 ${draft.formName}`}
                    className="af-btn af-btn--danger"
                    style={{ height: 26, padding: "0 12px" }}
                    onClick={() => deleteDraft(draft)}
                    disabled={deleteMutation.isPending}
                  >
                    删除
                  </button>
                  <Link
                    aria-label={`继续填写 ${draft.formName}`}
                    className="af-btn"
                    style={{ height: 26, padding: "0 12px", textDecoration: "none" }}
                    to={`/forms/${draft.formCode}?draftId=${draft.id}`}
                  >
                    继续填写
                  </Link>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </AppPage>
  );

  function deleteDraft(draft: MobileDraft) {
    if (!confirmDialog("确认删除该草稿？")) {
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
    if (node.type === "span_layout") {
      ids.push(...leafFieldIds(node.children ?? []));
      continue;
    }
    if (node.type === "table_list") {
      ids.push(node.id);
      continue;
    }
    if (node.type !== "description") {
      ids.push(node.id);
    }
  }
  return ids;
}

function hasValue(value: unknown) {
  if (value == null) {
    return false;
  }
  if (typeof value === "string") {
    return value.trim().length > 0;
  }
  if (Array.isArray(value)) {
    return value.length > 0;
  }
  if (typeof value === "object") {
    return Object.keys(value).length > 0;
  }
  return true;
}

function formatUpdatedAt(value: string | undefined) {
  if (!value) {
    return "保存时间未知";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return `保存于${value}`;
  }
  const now = new Date();
  const sameDay = date.toDateString() === now.toDateString();
  const yesterday = new Date(now.getTime() - 86400000).toDateString() === date.toDateString();
  const hh = String(date.getHours()).padStart(2, "0");
  const mm = String(date.getMinutes()).padStart(2, "0");
  if (sameDay) return `保存于今天 ${hh}:${mm}`;
  if (yesterday) return `保存于昨天 ${hh}:${mm}`;
  return `保存于${date.getMonth() + 1}月${date.getDate()}日`;
}

function confirmDialog(message: string) {
  if (typeof window.confirm === "function") {
    return window.confirm(message);
  }
  return true;
}

export default DraftListPage;
