import { useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useParams } from "react-router-dom";
import { isApiError } from "../../shared/api/errors";
import { queryKeys } from "../../shared/api/queryKeys";
import { createClientId } from "../../shared/clientId";
import { AppPage } from "../../shared/ui/AppPage";
import { PageError, PageSkeleton } from "../../shared/ui/PageStates";
import { DynamicFormRenderer } from "../forms/components/DynamicFormRenderer";
import { summarizeSchemaRows } from "../forms/components/ConfirmSummaryList";
import type { MobileFormValues, MobileSchemaNode } from "../forms/schema/types";
import { ApprovalRecords, approvalSummaryLabel } from "../tasks/ApprovalRecords";
import { fetchMobileInstanceDetail, withdrawMobileInstance } from "./processes.api";

export function ProcessDetailPage() {
  const { instanceId = "" } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const numericInstanceId = Number(instanceId);
  const [statusNotice, setStatusNotice] = useState("");
  const [actionError, setActionError] = useState("");
  const withdrawKeyRef = useRef(createIdempotencyKey());
  const instanceQuery = useQuery({ queryKey: queryKeys.instance(numericInstanceId), queryFn: () => fetchMobileInstanceDetail(numericInstanceId), enabled: Number.isSafeInteger(numericInstanceId) && numericInstanceId > 0, retry: 0 });
  const withdrawMutation = useMutation({
    mutationFn: () => withdrawMobileInstance(numericInstanceId, withdrawKeyRef.current),
    async onSuccess() { setActionError(""); setStatusNotice(""); await invalidateProcessCaches(queryClient, numericInstanceId); navigate("/tasks?view=pending", { replace: true }); },
    async onError(error) { if (isApiError(error) && (error.status === 409 || error.body.code === "ALREADY_ACTED")) { setActionError(""); setStatusNotice("流程状态已更新"); await instanceQuery.refetch(); return; } setActionError(error instanceof Error ? error.message : "撤回失败"); },
  });
  const schema = useMemo(() => normalizeSchema(instanceQuery.data?.schema), [instanceQuery.data?.schema]);
  const values = useMemo(() => normalizeValues(instanceQuery.data?.formData), [instanceQuery.data?.formData]);

  if (!Number.isSafeInteger(numericInstanceId) || numericInstanceId <= 0) return <PageError title="流程不存在" message="请返回列表重新打开。" />;
  if (instanceQuery.isPending) return <PageSkeleton rows={4} />;
  if (instanceQuery.isError || !instanceQuery.data) return <PageError onRetry={() => void instanceQuery.refetch()} />;

  const instance = instanceQuery.data;
  const summaryOnly = instance.visibility === "SUMMARY";
  const rows = summarizeSchemaRows(schema, values);
  const approvalRecords = Array.isArray(instance.approvalRecords) ? instance.approvalRecords : [];
  const approvalSummary = instance.approvalSummary ?? fallbackApprovalSummary(approvalRecords.length);
  return (
    <AppPage
      title={summaryOnly ? "流程摘要" : "审批详情"}
      contentClassName="approval-detail-page"
      action={summaryOnly ? undefined : <button className="app-bar__action" type="button" aria-label="分享" onClick={() => shareProcess(instance.formName ?? `流程 #${instance.id}`)}><ShareIcon /></button>}
      bottomBar={!summaryOnly && instance.canWithdraw ? <div className="action-bar process-action-bar"><button className="btn btn--danger btn--lg" type="button" disabled={withdrawMutation.isPending} onClick={() => { if (typeof window.confirm === "function" && !window.confirm("确认撤回并进入待修改？原流程和单号会保留，可修改后重新提交。已执行的外部 Webhook 不会回滚。")) return; setActionError(""); withdrawKeyRef.current = createIdempotencyKey(); withdrawMutation.mutate(); }}>{withdrawMutation.isPending ? "撤回中..." : "撤回流程"}</button></div> : null}
    >
      <section className="approval-hero detail-hero--bleed">
        <div className="approval-hero__title-block"><span className="approval-hero__label">表单名称</span><h1>{instance.formName ?? `流程 #${instance.id}`}</h1></div>
        <div className="approval-hero__applicant"><div className="approval-hero__avatar">{avatarText(instance.applicantName)}</div><div><span>发起人</span><strong>{instance.applicantName || "未记录"}</strong></div></div>
        <dl className="approval-hero__meta"><div><dt>工号</dt><dd>{instance.applicantEmployeeNo || "未分配"}</dd></div><div><dt>部门</dt><dd>{instance.applicantDepartment || "未记录"}</dd></div><div className="approval-hero__meta-wide"><dt>发起时间</dt><dd>{instance.startedAt ? formatDateTime(instance.startedAt) : "未记录"}</dd></div></dl>
        <div className="approval-hero__current"><span className="approval-hero__current-dot" /><span>当前审批节点</span><strong>{instance.currentNodeName || statusLabel(instance.status)}</strong></div>
      </section>

      {summaryOnly ? <p className="status-notice" role="status">当前账号仅可查看流程摘要。</p> : null}
      {statusNotice ? <p className="status-notice" role="status">{statusNotice}</p> : null}
      {actionError ? <p className="status-notice status-notice--danger" role="alert">{actionError}</p> : null}

      {!summaryOnly ? <section className="approval-panel form-detail-panel">
        <header className="approval-panel__head form-detail-panel__head"><div><h2>表单详情</h2><p>单号 <strong>{instance.businessNo}</strong></p></div><div className="field-total"><span>字段总数</span><strong>{rows.length}</strong></div></header>
        {schema.length > 0 ? (
          <DynamicFormRenderer
            schema={schema}
            values={values}
            mode="readonly"
            onValueChange={() => undefined}
          />
        ) : <p className="muted small">暂无表单字段</p>}
      </section> : null}

      <section className="approval-panel approval-records"><header className="approval-panel__head"><div><h2>审批记录</h2><p>已流转 {approvalSummary.flowedCount} 个节点</p></div><span className="approval-panel__summary">{approvalSummaryLabel(approvalSummary)}</span></header><ApprovalRecords records={approvalRecords} processSnapshot={instance.processSnapshot} schema={schema} history={instance.history} /></section>

    </AppPage>
  );
}

async function invalidateProcessCaches(queryClient: ReturnType<typeof useQueryClient>, id: number) { await Promise.all([queryClient.invalidateQueries({ queryKey: queryKeys.bootstrap }), queryClient.invalidateQueries({ queryKey: queryKeys.taskRoot }), queryClient.invalidateQueries({ queryKey: queryKeys.instance(id) })]); }
function normalizeSchema(schema: unknown): MobileSchemaNode[] { return Array.isArray(schema) ? schema as MobileSchemaNode[] : []; }
function normalizeValues(data?: Record<string, unknown> | null): MobileFormValues { return data && typeof data === "object" && !Array.isArray(data) ? data : {}; }
function statusLabel(status: string) { return ({ RUNNING: "审批中", APPROVED: "已通过", REJECTED: "已驳回", WITHDRAWN: "已撤回" } as Record<string, string>)[status] ?? status; }
function formatDateTime(value: string) { const date = new Date(value); return Number.isNaN(date.getTime()) ? value : date.toLocaleString("zh-CN", { hour12: false }); }
function fallbackApprovalSummary(flowedCount: number) { return { flowedCount, completedCount: flowedCount, processingCount: 0, complete: false }; }
function createIdempotencyKey() { return createClientId("withdraw"); }
function avatarText(name?: string | null) { return name?.trim().slice(0, 1) || "—"; }
function shareProcess(title: string) { if (typeof navigator.share === "function") void navigator.share({ title, url: window.location.href }).catch(() => undefined); }
function ShareIcon() { return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><circle cx="18" cy="5" r="3" /><circle cx="6" cy="12" r="3" /><circle cx="18" cy="19" r="3" /><path d="m8.59 13.51 6.83 3.98M15.41 6.51l-6.82 3.98" /></svg>; }

export default ProcessDetailPage;
