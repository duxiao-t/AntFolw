import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { queryKeys } from "../../shared/api/queryKeys";
import { AppPage } from "../../shared/ui/AppPage";
import { PageEmpty, PageError, PageSkeleton } from "../../shared/ui/PageStates";
import { useAuthStore } from "../auth/auth.store";
import { deleteMobileDraft, fetchMobileDrafts, type MobileDraft } from "./drafts.api";
import { removeRecoveryDraft } from "./recoveryDraft.store";
import type { MobileSchemaNode } from "./schema/types";

const TONES = ["info", "success", "warning"];

export function DraftListPage() {
  const user = useAuthStore((state) => state.user);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [deletedIds, setDeletedIds] = useState<number[]>([]);
  const draftsQuery = useQuery({ queryKey: queryKeys.drafts, queryFn: fetchMobileDrafts, retry: 0, refetchOnMount: "always" });
  const deleteMutation = useMutation({ mutationFn: deleteMobileDraft, async onSuccess() { await Promise.all([queryClient.invalidateQueries({ queryKey: queryKeys.drafts }), queryClient.invalidateQueries({ queryKey: queryKeys.bootstrap })]); } });
  const drafts = useMemo(() => (draftsQuery.data ?? []).filter((draft) => !deletedIds.includes(draft.id)), [deletedIds, draftsQuery.data]);

  if (draftsQuery.isPending) return <PageSkeleton rows={4} />;
  if (draftsQuery.isError) return <PageError onRetry={() => void draftsQuery.refetch()} />;

  return (
    <AppPage title="我的草稿" action={<button className="app-bar__action" type="button">编辑</button>}>
      <p className="muted small" style={{ margin: "4px 4px 14px" }}>未提交的草稿，将在 72 小时后自动归档。</p>
      {drafts.length === 0 ? <PageEmpty title="暂无草稿" hint="新表单中途退出时会自动保存到这里。" /> : <section style={{ display: "grid", gap: 10 }}>{drafts.map((draft, index) => { const count = completionCount(draft); return <div className={`draft-card draft-card--${TONES[index % TONES.length]}`} key={draft.id}><span className="draft-card__icon">{draft.formName.trim().charAt(0) || "稿"}</span><button type="button" className="draft-card__main draft-card__open" aria-label={`继续填写 ${draft.formName}`} onClick={() => navigate(`/forms/${draft.formCode}?draftId=${draft.id}`)}><b>{draft.formName}</b><small>{formatUpdatedAt(draft.updatedAt)} · 步骤 {count.filled}/{count.total}</small></button><span className="draft-card__chev">›</span><button className="draft-card__del" type="button" aria-label={`删除 ${draft.formName}`} disabled={deleteMutation.isPending} onClick={() => deleteDraft(draft)}>删除</button></div>; })}</section>}
      {drafts.length > 0 ? <div className="empty-hero"><div className="empty-hero__art"><svg viewBox="0 0 120 120" fill="none" aria-hidden="true"><circle cx="60" cy="60" r="44" fill="#f1f5f9" /><path d="M40 50h40M40 60h28M40 70h36" stroke="#94a3b8" strokeWidth="4" strokeLinecap="round" /></svg></div><h3>已加载全部草稿</h3><p>新表单中途退出时会自动保存到这里。</p></div> : null}
    </AppPage>
  );

  function deleteDraft(draft: MobileDraft) {
    if (typeof window.confirm === "function" && !window.confirm("确认删除该草稿？")) return;
    setDeletedIds((current) => [...current, draft.id]);
    if (user) removeRecoveryDraft(user.id, draft.formCode, draft.id);
    void deleteMutation.mutateAsync(draft.id);
  }
}

function completionCount(draft: MobileDraft) { const data = draft.data ?? {}; const ids = draft.schema ? leafFieldIds(draft.schema) : Object.keys(data); const total = Math.max(ids.length, 1); return { filled: ids.filter((id) => hasValue(data[id])).length, total }; }
function leafFieldIds(schema: MobileSchemaNode[]) { const ids: string[] = []; for (const node of schema) { if (node.type === "span_layout") ids.push(...leafFieldIds(node.children ?? [])); else if (node.type === "table_list") ids.push(node.id); else if (node.type !== "description") ids.push(node.id); } return ids; }
function hasValue(value: unknown) { if (value == null) return false; if (typeof value === "string") return value.trim().length > 0; if (Array.isArray(value)) return value.length > 0; if (typeof value === "object") return Object.keys(value).length > 0; return true; }
function formatUpdatedAt(value?: string) { if (!value) return "保存时间未知"; const date = new Date(value); if (Number.isNaN(date.getTime())) return `保存于${value}`; const now = new Date(); const hh = String(date.getHours()).padStart(2, "0"); const mm = String(date.getMinutes()).padStart(2, "0"); if (date.toDateString() === now.toDateString()) return `保存于今天 ${hh}:${mm}`; if (new Date(now.getTime() - 86400000).toDateString() === date.toDateString()) return `保存于昨天 ${hh}:${mm}`; return `保存于${date.getMonth() + 1}月${date.getDate()}日`; }

export default DraftListPage;
