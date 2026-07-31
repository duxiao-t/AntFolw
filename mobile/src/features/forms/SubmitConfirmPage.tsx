import { useMemo, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useNavigate, useParams } from "react-router-dom";
import { queryKeys } from "../../shared/api/queryKeys";
import { AppPage } from "../../shared/ui/AppPage";
import { PageEmpty, PageError, PageSkeleton } from "../../shared/ui/PageStates";
import { useAuthStore } from "../auth/auth.store";
import { summarizeSchemaRows } from "./components/ConfirmSummaryList";
import { fetchMobileForm } from "./drafts.api";
import { removeRecoveryDraft } from "./recoveryDraft.store";
import { startMobileInstance, submitMobileFormData } from "./start.api";
import { resubmitReworkTask } from "./rework.api";
import { clearIdempotencyKeyForPayload, findSelfSelectRules, formSchemaWithoutSelfSelectRules, idempotencyKeyForPayload, selectedAssigneeNames, useSubmitFlowStore } from "./submitFlow.store";

export function SubmitConfirmPage() {
  const { code = "" } = useParams();
  const navigate = useNavigate();
  const user = useAuthStore((state) => state.user);
  const flow = useSubmitFlowStore();
  const resetFlow = useSubmitFlowStore((state) => state.reset);
  const [error, setError] = useState("");
  const [confirmed, setConfirmed] = useState(true);
  const formQuery = useQuery({ queryKey: queryKeys.form(code), queryFn: () => fetchMobileForm(code), enabled: code.length > 0, retry: 0 });
  const selfSelectedRows = useMemo(() => selectedAssigneeNames(findSelfSelectRules(formQuery.data?.process), flow.selfSelected), [flow.selfSelected, formQuery.data?.process]);
  const formName = formQuery.data?.name ?? "申请";
  const workflowEnabled = formQuery.data?.settings?.workflowEnabled !== false;
  const summaryRows = summarizeSchemaRows(formSchemaWithoutSelfSelectRules(formQuery.data?.schema ?? []), flow.values);
  const submitMutation = useMutation({
    mutationFn: async () => {
      setError("");
      if (flow.reworkTaskId != null) {
        const result = await resubmitReworkTask(flow.reworkTaskId, flow.values,
          idempotencyKeyForPayload(currentPayload()));
        return { mode: "workflow" as const, id: result.instanceId };
      }
      if (!workflowEnabled) { const result = await submitMobileFormData({ formCode: flow.formCode ?? code, values: flow.values }); return { mode: "direct" as const, id: result.dataId }; }
      const result = await startMobileInstance({ formCode: flow.formCode ?? code, values: flow.values, selfSelected: flow.selfSelected, draftId: flow.draftId, idempotencyKey: idempotencyKeyForPayload(currentPayload()) });
      return { mode: "workflow" as const, id: result.instanceId };
    },
    onSuccess(result) {
      if (result.mode === "workflow") clearIdempotencyKeyForPayload(currentPayload());
      if (user && flow.formCode) removeRecoveryDraft(user.id, flow.formCode,
        flow.reworkTaskId == null ? flow.draftId : -flow.reworkTaskId);
      resetFlow();
      void navigate(`/forms/${encodeURIComponent(code)}/success/${result.id}?mode=${result.mode}`, { replace: true });
    },
    onError(errorValue) { setError(errorValue instanceof Error ? errorValue.message : "提交失败"); },
  });

  if (!flow.formCode || flow.formCode !== code) return <AppPage title="确认提交"><PageEmpty title="提交信息已失效" hint="请返回表单重新提交。" action={<button className="btn btn--primary" type="button" onClick={() => navigate(editPath())}>返回表单</button>} /></AppPage>;
  if (formQuery.isPending) return <PageSkeleton rows={5} />;
  if (formQuery.isError) return <PageError onRetry={() => void formQuery.refetch()} />;

  return (
    <AppPage title="确认提交" contentStyle={{ paddingBottom: 0 }}>
      <div style={{ margin: "8px 0 14px" }}><h3 style={{ margin: 0, fontSize: 20, fontWeight: 800 }}>请确认本次申请</h3><small className="muted">提交后将按以下流程流转，可在“我发起的”中跟进。</small></div>
      <div className="confirm-card"><div style={{ padding: "12px 14px", borderBottom: "1px solid var(--af-color-line)" }}><div style={{ fontSize: 13, fontWeight: 700 }}>{formName}</div><small className="muted">{flow.reworkTaskId ? "本次提交将保留原单号" : "表单编号将在提交后生成"}</small></div>{summaryRows.map((row) => <div className="confirm-row" key={row.id}><span className="confirm-row__k">{row.label}</span><span className="confirm-row__v">{row.value || "未填写"}</span></div>)}</div>
      {selfSelectedRows.length > 0 ? <><h4 style={{ fontSize: 13, color: "var(--af-color-muted)", margin: "18px 4px 8px", fontWeight: 600 }}>审批流（{selfSelectedRows.length} 个节点）</h4><div className="list-card">{selfSelectedRows.map((row, index) => <div className="list-item" key={row.nodeId}><span className={`list-item__avatar flow-person-avatar avatar-tone avatar-tone--${index % 2 === 0 ? "blue" : "mint"}`}>{row.names[0]?.slice(0, 1) || "审"}</span><div className="list-item__main"><b>{row.names.join("、")} · {row.name}</b><small>审批节点 {index + 1}</small></div><span className={`chip ${index === 0 ? "chip--soft" : "chip--ghost"}`}>{index === 0 ? "待审" : "后续"}</span></div>)}</div></> : null}
      <div style={{ margin: "18px 0", padding: "12px 14px", border: "1px dashed var(--af-color-border)", borderRadius: 10, background: "var(--af-color-surface)" }}><label style={{ display: "flex", gap: 8, alignItems: "flex-start", fontSize: 12, color: "var(--af-color-text-secondary)" }}><input type="checkbox" checked={confirmed} onChange={(event) => setConfirmed(event.currentTarget.checked)} /><span>我已确认信息无误，提交后将按上述审批流转入下一节点。</span></label></div>
      {error ? <p role="alert" className="status-notice status-notice--danger">{error}</p> : null}
      <div className="action-bar"><button type="button" className="btn btn--ghost btn--lg" onClick={() => navigate(editPath())}>返回编辑</button><button type="button" className="btn btn--success btn--lg" disabled={!confirmed || submitMutation.isPending} onClick={() => submitMutation.mutate()}>{submitMutation.isPending ? "提交中..." : error ? "重试提交" : flow.reworkTaskId ? "确认重提" : "确认提交"}</button></div>
    </AppPage>
  );

  function currentPayload() { return JSON.stringify({ formCode: flow.formCode ?? code, data: flow.values, selfSelected: flow.selfSelected, draftId: flow.draftId, reworkTaskId: flow.reworkTaskId }); }
  function editPath() { const base = `/forms/${encodeURIComponent(code)}`; return flow.reworkTaskId == null ? base : `${base}?reworkTaskId=${flow.reworkTaskId}`; }
}

export default SubmitConfirmPage;
