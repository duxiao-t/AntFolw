import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { isApiError } from "../../shared/api/errors";
import { queryKeys } from "../../shared/api/queryKeys";
import { AppPage } from "../../shared/ui/AppPage";
import { PageError, PageSkeleton } from "../../shared/ui/PageStates";
import { summarizeSchemaRows } from "../forms/components/ConfirmSummaryList";
import { DynamicFormRenderer } from "../forms/components/DynamicFormRenderer";
import type {
  FieldMode,
  MobileFormValues,
  MobileSchemaNode,
} from "../forms/schema/types";
import { collectVisibleValues } from "../forms/schema/validators";
import { ApproveSheet } from "./ApproveSheet";
import { ApprovalRecords, approvalSummaryLabel } from "./ApprovalRecords";
import { RejectSheet } from "./RejectSheet";
import { fetchTaskDetail, markTaskRead, runTaskAction, type TaskActionPayload } from "./tasks.api";

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
  const acknowledgeMutation = useMutation({
    mutationFn: () => markTaskRead(numericTaskId),
    async onSuccess() {
      setActionError("");
      await invalidateTaskCaches(queryClient, numericTaskId, detailQuery.data?.task.instanceId);
      navigate(returnPath(searchParams), { replace: true });
    },
    onError(error) {
      setActionError(error instanceof Error ? error.message : "操作失败");
    },
  });
  const schema = useMemo(() => normalizeSchema(detailQuery.data?.schema), [detailQuery.data?.schema]);
  const values = useMemo(() => normalizeValues(detailQuery.data?.formData), [detailQuery.data?.formData]);
  const [editableValues, setEditableValues] = useState<MobileFormValues>({});
  useEffect(() => {
    setEditableValues(values);
  }, [values]);
  const fieldModes = useMemo(() => {
    const modes: Record<string, FieldMode> = {};
    const rawSnapshot = detailQuery.data?.processSnapshot;
    if (!rawSnapshot) return modes;
    const snapshot =
      typeof rawSnapshot === "string" ? safeParse(rawSnapshot) : rawSnapshot;
    const nodeId = detailQuery.data?.task.nodeId;
    if (!snapshot || !nodeId) return modes;
    const node = findProcessNode(snapshot, nodeId);
    for (const entry of node?.props?.formPerms ?? []) {
      if (entry.mode === "HIDDEN") modes[entry.fieldId] = "hidden";
      else if (entry.mode === "EDITABLE") modes[entry.fieldId] = "fill";
      else modes[entry.fieldId] = "readonly";
    }
    return modes;
  }, [detailQuery.data]);
  const hasEditableFields = Object.values(fieldModes).includes("fill");
  const editablePayload = Object.fromEntries(
    Object.entries(collectVisibleValues(schema, editableValues))
      .filter(([fieldId]) => fieldModes[fieldId] === "fill"),
  );

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
  const rejectDisabled = detail.rejectDisabled === true;
  const canAcknowledge = detail.allowedActions.includes("ACKNOWLEDGE");
  const showActions = canApprove || canReject || rejectDisabled || canAcknowledge;
  const isCc = task.taskStatus === "CC";

  return (
    <AppPage
      title={isCc ? "抄送详情" : "审批详情"}
      contentClassName="approval-detail-page"
      action={<button type="button" className="app-bar__action" aria-label="分享" onClick={() => shareTask(task.formName)}><ShareIcon /></button>}
      bottomBar={showActions ? <div className="action-bar approval-action-bar">{canReject || rejectDisabled ? <button type="button" className="btn btn--ghost btn--lg" disabled={rejectDisabled || actionMutation.isPending} title={rejectDisabled ? "并行审批节点不允许驳回" : undefined} onClick={() => { setActionError(""); setRejectOpen(true); }}>驳回</button> : <span />}{canApprove ? <button type="button" className="btn btn--success btn--lg" disabled={actionMutation.isPending} onClick={() => { setActionError(""); setApproveOpen(true); }}>同意</button> : null}{canAcknowledge ? <button type="button" className="btn btn--success btn--lg" disabled={acknowledgeMutation.isPending} onClick={() => acknowledgeMutation.mutate()}>同意</button> : null}</div> : null}
    >
      <section className="approval-hero detail-hero--bleed">
        <div className="approval-hero__title-block"><span className="approval-hero__label">表单名称</span><h1>{task.formName}</h1></div>
        <div className="approval-hero__applicant"><div className="approval-hero__avatar">{task.applicantName.slice(0, 1)}</div><div><span>申请人</span><strong>{task.applicantName}</strong></div></div>
        <dl className="approval-hero__meta"><div><dt>工号</dt><dd>{task.applicantEmployeeNo || "未分配"}</dd></div><div><dt>部门</dt><dd>{task.applicantDepartment || "未填写"}</dd></div><div className="approval-hero__meta-wide"><dt>提交时间</dt><dd>{formatDateTime(approvalRecords[0]?.receivedAt || task.createdAt)}</dd></div></dl>
        <div className="approval-hero__current"><span className="approval-hero__current-dot" /><span>{isCc ? "抄送节点" : "当前审批节点"}</span><strong>{task.nodeName}</strong></div>
      </section>

      {statusNotice ? <p className="status-notice" role="status">{statusNotice}</p> : null}
      <section className="approval-panel form-detail-panel">
        <header className="approval-panel__head form-detail-panel__head"><div><h2>表单详情</h2><p>单号 <strong>{task.businessNo}</strong></p></div><div className="field-total"><span>字段总数</span><strong>{rows.length}</strong></div></header>
        {hasEditableFields ? <p className="muted small">以下字段可在审批时修改</p> : null}
        {schema.length > 0 ? (
          <DynamicFormRenderer
            schema={schema}
            values={editableValues}
            mode="readonly"
            showDescriptions={false}
            modeOverride={fieldModes}
            onValueChange={(fieldId, value) =>
              setEditableValues((previous) => ({ ...previous, [fieldId]: value }))
            }
          />
        ) : <p className="muted small">暂无表单字段</p>}
      </section>

      <section className="approval-panel approval-records"><header className="approval-panel__head"><div><h2>审批记录</h2><p>已流转 {approvalSummary.flowedCount} 个节点</p></div><span className="approval-panel__summary">{approvalSummaryLabel(approvalSummary)}</span></header><ApprovalRecords records={approvalRecords} processSnapshot={detail.processSnapshot} schema={schema} history={detail.history} /></section>

      <ApproveSheet open={approveOpen} loading={actionMutation.isPending} error={approveOpen ? actionError : undefined} presets={detail.commentPresets?.approve} onClose={() => { if (!actionMutation.isPending) { setApproveOpen(false); setActionError(""); } }} onSubmit={(payload, idempotencyKey) => actionMutation.mutate({ action: "approve", payload: { ...payload, ...(hasEditableFields ? { data: editablePayload } : {}) }, idempotencyKey })} />
      <RejectSheet open={rejectOpen} loading={actionMutation.isPending} error={rejectOpen ? actionError : undefined} rejectTargets={detail.rejectTargets} presets={detail.commentPresets?.reject} onClose={() => { if (!actionMutation.isPending) { setRejectOpen(false); setActionError(""); } }} onSubmit={(payload, idempotencyKey) => actionMutation.mutate({ action: "reject", payload, idempotencyKey })} />
    </AppPage>
  );
}

async function invalidateTaskCaches(queryClient: ReturnType<typeof useQueryClient>, taskId: number, instanceId?: number) { await Promise.all([queryClient.invalidateQueries({ queryKey: queryKeys.bootstrap }), queryClient.invalidateQueries({ queryKey: queryKeys.taskRoot }), queryClient.invalidateQueries({ queryKey: queryKeys.taskDetail(taskId) }), instanceId ? queryClient.invalidateQueries({ queryKey: queryKeys.instance(instanceId) }) : Promise.resolve()]); }
function returnPath(params: URLSearchParams) { const next = new URLSearchParams(); const view = params.get("returnView"); const keyword = params.get("returnKeyword"); const status = params.get("returnStatus"); if (view) next.set("view", view); if (keyword) next.set("keyword", keyword); if (status) next.set("status", status); return next.size ? `/tasks?${next}` : "/tasks"; }
function normalizeSchema(schema: unknown): MobileSchemaNode[] { return Array.isArray(schema) ? schema as MobileSchemaNode[] : []; }
function normalizeValues(data?: Record<string, unknown> | null): MobileFormValues { return data && typeof data === "object" && !Array.isArray(data) ? data : {}; }
function safeParse(value: string): unknown { try { return JSON.parse(value); } catch { return null; } }
function findProcessNode(node: any, id: string): any {
  if (!node || typeof node !== "object" || !node.id) return null;
  if (node.id === id) return node;
  if (Array.isArray(node.branchs)) {
    for (const branch of node.branchs) {
      const hit = findProcessNode(branch, id);
      if (hit) return hit;
    }
  }
  return findProcessNode(node.children, id);
}
function formatDateTime(value: string) { const date = new Date(value); return Number.isNaN(date.getTime()) ? value : date.toLocaleString("zh-CN", { hour12: false }); }
function fallbackApprovalSummary(flowedCount: number) { return { flowedCount, completedCount: flowedCount, processingCount: 0, complete: false }; }
function shareTask(title: string) { if (typeof navigator.share === "function") void navigator.share({ title, url: window.location.href }).catch(() => undefined); }
function ShareIcon() { return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><circle cx="18" cy="5" r="3" /><circle cx="6" cy="12" r="3" /><circle cx="18" cy="19" r="3" /><path d="m8.59 13.51 6.83 3.98M15.41 6.51l-6.82 3.98" /></svg>; }

export default TaskDetailPage;
