import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { isApiError } from "../../shared/api/errors";
import { queryKeys } from "../../shared/api/queryKeys";
import { AppPage } from "../../shared/ui/AppPage";
import { PageError, PageSkeleton } from "../../shared/ui/PageStates";
import { summarizeSchemaRows } from "../forms/components/ConfirmSummaryList";
import type { MobileFormValues, MobileSchemaNode } from "../forms/schema/types";
import { ApproveSheet } from "./ApproveSheet";
import { ApprovalRecords, approvalSummaryLabel } from "./ApprovalRecords";
import { RejectSheet } from "./RejectSheet";
import { fetchTaskDetail, runTaskAction, type TaskActionPayload } from "./tasks.api";

export function TaskDetailPage() {
  const { taskId = "" } = useParams();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const numericTaskId = Number(taskId);
  const [approveOpen, setApproveOpen] = useState(false);
  const [rejectOpen, setRejectOpen] = useState(false);
  const [actionError, setActionError] = useState("");
  const [statusNotice, setStatusNotice] = useState("");
  const detailQuery = useQuery({ queryKey: queryKeys.taskDetail(numericTaskId), queryFn: () => fetchTaskDetail(numericTaskId), enabled: Number.isSafeInteger(numericTaskId) && numericTaskId > 0, retry: 0 });
  const actionMutation = useMutation({
    mutationFn: ({ action, payload, idempotencyKey }: { action: "approve" | "reject"; payload: TaskActionPayload; idempotencyKey: string }) => runTaskAction(numericTaskId, action, payload, idempotencyKey),
    async onSuccess() { setActionError(""); setStatusNotice(""); setApproveOpen(false); setRejectOpen(false); await invalidateTaskCaches(queryClient, numericTaskId, detailQuery.data?.task.instanceId); navigate(returnPath(searchParams), { replace: true }); },
    async onError(error) { if (isApiError(error) && error.status === 409) { setApproveOpen(false); setRejectOpen(false); setActionError(""); setStatusNotice("任务状态已更新"); await detailQuery.refetch(); return; } setActionError(error instanceof Error ? error.message : "操作失败"); },
  });
  const schema = useMemo(() => normalizeSchema(detailQuery.data?.schema), [detailQuery.data?.schema]);
  const values = useMemo(() => normalizeValues(detailQuery.data?.formData), [detailQuery.data?.formData]);

  if (!Number.isSafeInteger(numericTaskId) || numericTaskId <= 0) return <PageError title="任务不存在" message="请返回任务中心重新打开。" />;
  if (detailQuery.isPending) return <PageSkeleton rows={5} />;
  if (detailQuery.isError || !detailQuery.data) return <PageError onRetry={() => void detailQuery.refetch()} />;

  const detail = detailQuery.data;
  const task = detail.task;
  const rows = summarizeSchemaRows(schema, values);
  const approvalRecords = Array.isArray(detail.approvalRecords) ? detail.approvalRecords : [];
  const approvalSummary = detail.approvalSummary ?? fallbackApprovalSummary(approvalRecords.length);
  const canApprove = detail.allowedActions.includes("APPROVE");
  const canReject = detail.allowedActions.includes("REJECT");
  const showActions = canApprove || canReject;

  return (
    <AppPage
      title="审批详情"
      contentClassName="approval-detail-page"
      action={<button type="button" className="app-bar__action" aria-label="分享" onClick={() => shareTask(task.formName)}><ShareIcon /></button>}
      bottomBar={showActions ? <div className="action-bar approval-action-bar">{canReject ? <button type="button" className="btn btn--ghost btn--lg" disabled={actionMutation.isPending} onClick={() => { setActionError(""); setRejectOpen(true); }}>驳回</button> : <span />}{canApprove ? <button type="button" className="btn btn--success btn--lg" disabled={actionMutation.isPending} onClick={() => { setActionError(""); setApproveOpen(true); }}>同意</button> : null}</div> : null}
    >
      <section className="approval-hero detail-hero--bleed">
        <div className="approval-hero__title-block"><span className="approval-hero__label">表单名称</span><h1>{task.formName}</h1></div>
        <div className="approval-hero__applicant"><div className="approval-hero__avatar">{task.applicantName.slice(0, 1)}</div><div><span>申请人</span><strong>{task.applicantName}</strong></div></div>
        <dl className="approval-hero__meta"><div><dt>工号</dt><dd>{task.applicantEmployeeNo || "未分配"}</dd></div><div><dt>部门</dt><dd>{task.applicantDepartment || "未填写"}</dd></div><div className="approval-hero__meta-wide"><dt>提交时间</dt><dd>{formatDateTime(approvalRecords[0]?.receivedAt || task.createdAt)}</dd></div></dl>
        <div className="approval-hero__current"><span className="approval-hero__current-dot" /><span>当前审批节点</span><strong>{task.nodeName}</strong></div>
      </section>

      {statusNotice ? <p className="status-notice" role="status">{statusNotice}</p> : null}
      <section className="approval-panel form-detail-panel">
        <header className="approval-panel__head form-detail-panel__head"><div><h2>表单详情</h2><p>单号 <strong>{task.businessNo}</strong></p></div><div className="field-total"><span>字段总数</span><strong>{rows.length}</strong></div></header>
        {rows.length > 0 ? <dl className="form-fields">{rows.map((row, index) => <div key={row.id} className={`form-field-row${index === rows.length - 1 && row.value.length > 24 ? " form-field-row--stack" : ""}`}><dt>{row.label}</dt><dd>{row.value || "未填写"}</dd></div>)}</dl> : <p className="muted small">暂无表单字段</p>}
      </section>

      <section className="approval-panel approval-records"><header className="approval-panel__head"><div><h2>审批记录</h2><p>已流转 {approvalSummary.flowedCount} 个节点</p></div><span className="approval-panel__summary">{approvalSummaryLabel(approvalSummary)}</span></header><ApprovalRecords records={approvalRecords} /></section>

      <section className="approval-panel attachment-panel"><header className="approval-panel__head"><div><h2>附件</h2><p>共 {detail.files.length} 个文件</p></div><span className="approval-panel__summary">合计 {formatSize(detail.files.reduce((sum, file) => sum + (file.size || 0), 0))}</span></header><div className="attachment-list">{detail.files.length === 0 ? <p className="muted small">暂无附件</p> : detail.files.map((file) => <article className="attachment-file" key={file.id}><div className="attachment-file__main"><strong title={file.name}>{file.name}</strong><span><b>文件类型</b> {file.contentType || "未知"}</span><span><b>关联单号</b> {task.businessNo}</span></div><div className="attachment-file__aside"><span>{formatSize(file.size)}</span><a href={file.contentUrl} aria-label={`下载${file.name}`}><DownloadIcon /></a></div></article>)}</div></section>

      <ApproveSheet open={approveOpen} loading={actionMutation.isPending} error={approveOpen ? actionError : undefined} onClose={() => { if (!actionMutation.isPending) { setApproveOpen(false); setActionError(""); } }} onSubmit={(payload, idempotencyKey) => actionMutation.mutate({ action: "approve", payload, idempotencyKey })} />
      <RejectSheet open={rejectOpen} loading={actionMutation.isPending} error={rejectOpen ? actionError : undefined} onClose={() => { if (!actionMutation.isPending) { setRejectOpen(false); setActionError(""); } }} onSubmit={(payload, idempotencyKey) => actionMutation.mutate({ action: "reject", payload, idempotencyKey })} />
    </AppPage>
  );
}

async function invalidateTaskCaches(queryClient: ReturnType<typeof useQueryClient>, taskId: number, instanceId?: number) { await Promise.all([queryClient.invalidateQueries({ queryKey: queryKeys.bootstrap }), queryClient.invalidateQueries({ queryKey: ["mobile", "tasks"] }), queryClient.invalidateQueries({ queryKey: queryKeys.taskDetail(taskId) }), instanceId ? queryClient.invalidateQueries({ queryKey: queryKeys.instance(instanceId) }) : Promise.resolve()]); }
function returnPath(params: URLSearchParams) { const next = new URLSearchParams(); const view = params.get("returnView"); const keyword = params.get("returnKeyword"); const status = params.get("returnStatus"); if (view) next.set("view", view); if (keyword) next.set("keyword", keyword); if (status) next.set("status", status); return next.size ? `/tasks?${next}` : "/tasks"; }
function normalizeSchema(schema: unknown): MobileSchemaNode[] { return Array.isArray(schema) ? schema as MobileSchemaNode[] : []; }
function normalizeValues(data?: Record<string, unknown> | null): MobileFormValues { return data && typeof data === "object" && !Array.isArray(data) ? data : {}; }
function formatDateTime(value: string) { const date = new Date(value); return Number.isNaN(date.getTime()) ? value : date.toLocaleString("zh-CN", { hour12: false }); }
function formatSize(size: number) { if (!size) return "0 KB"; return size >= 1048576 ? `${(size / 1048576).toFixed(1)} MB` : `${Math.max(1, Math.round(size / 1024))} KB`; }
function fallbackApprovalSummary(flowedCount: number) { return { flowedCount, completedCount: flowedCount, processingCount: 0, complete: false }; }
function shareTask(title: string) { if (typeof navigator.share === "function") void navigator.share({ title, url: window.location.href }).catch(() => undefined); }
function ShareIcon() { return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><circle cx="18" cy="5" r="3" /><circle cx="6" cy="12" r="3" /><circle cx="18" cy="19" r="3" /><path d="m8.59 13.51 6.83 3.98M15.41 6.51l-6.82 3.98" /></svg>; }
function DownloadIcon() { return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M12 3v12" /><path d="m7 10 5 5 5-5" /><path d="M5 21h14" /></svg>; }

export default TaskDetailPage;
