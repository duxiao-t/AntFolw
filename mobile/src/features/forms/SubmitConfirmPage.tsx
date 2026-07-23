import { useMemo, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useNavigate, useParams } from "react-router-dom";
import { queryKeys } from "../../shared/api/queryKeys";
import { AppPage } from "../../shared/ui/AppPage";
import { PageError, PageSkeleton } from "../../shared/ui/PageStates";
import { useAuthStore } from "../auth/auth.store";
import { fetchMobileForm } from "./drafts.api";
import { removeRecoveryDraft } from "./recoveryDraft.store";
import { getFieldDefinition } from "./schema/fieldRegistry";
import type { MobileFormValues, MobileSchemaNode } from "./schema/types";
import { startMobileInstance } from "./start.api";
import {
  clearIdempotencyKeyForPayload,
  findSelfSelectRules,
  formSchemaWithoutSelfSelectRules,
  idempotencyKeyForPayload,
  selectedAssigneeNames,
  useSubmitFlowStore,
} from "./submitFlow.store";

export function SubmitConfirmPage() {
  const { code = "" } = useParams();
  const navigate = useNavigate();
  const user = useAuthStore((state) => state.user);
  const flow = useSubmitFlowStore();
  const resetFlow = useSubmitFlowStore((state) => state.reset);
  const [error, setError] = useState("");
  const formQuery = useQuery({
    queryKey: queryKeys.form(code),
    queryFn: () => fetchMobileForm(code),
    enabled: code.length > 0,
    retry: 0,
  });

  const formRows = useMemo(
    () => summarizeRows(formSchemaWithoutSelfSelectRules(formQuery.data?.schema ?? []), flow.values),
    [flow.values, formQuery.data?.schema],
  );
  const selfSelectedRows = useMemo(
    () => selectedAssigneeNames(findSelfSelectRules(formQuery.data?.process), flow.selfSelected),
    [flow.selfSelected, formQuery.data?.process],
  );
  const formName = formQuery.data?.name ?? "申请";
  const formInitial = formName.trim().charAt(0) || "申";

  const submitMutation = useMutation({
    mutationFn: () => {
      setError("");
      return startMobileInstance({
        formCode: flow.formCode ?? code,
        values: flow.values,
        selfSelected: flow.selfSelected,
        draftId: flow.draftId,
        idempotencyKey: idempotencyKeyForCurrentPayload(),
      });
    },
    onSuccess(result) {
      clearIdempotencyKeyForPayload(currentPayload());
      if (user && flow.formCode) {
        removeRecoveryDraft(user.id, flow.formCode, flow.draftId);
      }
      resetFlow();
      void navigate(`/forms/${encodeURIComponent(code)}/success/${result.instanceId}`, { replace: true });
    },
    onError(errorValue) {
      setError(errorValue instanceof Error ? errorValue.message : "提交失败");
    },
  });

  if (!flow.formCode || flow.formCode !== code) {
    return (
      <AppPage title="确认提交">
        <p>当前提交信息已失效，请返回表单重新提交。</p>
        <button
          type="button"
          className="af-btn af-btn--block"
          onClick={() => navigate(`/forms/${encodeURIComponent(code)}`)}
        >
          返回表单
        </button>
      </AppPage>
    );
  }

  if (formQuery.isPending) {
    return <PageSkeleton rows={5} />;
  }

  if (formQuery.isError) {
    return <PageError onRetry={() => void formQuery.refetch()} />;
  }

  return (
    <AppPage title="确认提交">
      <div className="af-stack">
        <section className="af-card" style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <span className="af-app-grid__icon" aria-hidden="true">{formInitial}</span>
          <div style={{ display: "grid", gap: 2 }}>
            <strong style={{ fontSize: 13 }}>{formName}</strong>
            <small style={{ fontSize: 10, color: "var(--af-color-muted)" }}>提交后将进入审批流程</small>
          </div>
        </section>

        <section className="af-card">
          <div className="af-card__title">
            <span>申请摘要</span>
            <button
              type="button"
              className="af-link-button"
              style={{ fontSize: 11, fontWeight: 400 }}
              onClick={() => navigate(`/forms/${encodeURIComponent(code)}`)}
            >
              修改
            </button>
          </div>
          {formRows.map((row) => (
            <div key={row.id} className="af-kv">
              <span className="af-kv__label">{row.label}</span>
              <span className="af-kv__value">{row.value || "未填写"}</span>
            </div>
          ))}
        </section>

        {selfSelectedRows.length > 0 ? (
          <section className="af-card">
            <div className="af-card__title">
              <span>审批流程</span>
            </div>
            <div className="af-timeline">
              {selfSelectedRows.map((row, index) => (
                <div
                  key={row.nodeId}
                  className={`af-timeline__event${index === 0 ? "" : " af-timeline__event--future"}`}
                >
                  <b>{row.name}</b>
                  <small>{row.names.join("、")}</small>
                </div>
              ))}
            </div>
          </section>
        ) : null}
      </div>

      {error ? (
        <p role="alert" style={{ margin: "8px 0", color: "var(--af-color-danger)", fontSize: 12 }}>
          {error}
        </p>
      ) : null}

      <div className="af-bottom-bar">
        <button
          type="button"
          className="af-btn af-btn--block"
          disabled={submitMutation.isPending}
          onClick={() => submitMutation.mutate()}
        >
          {error ? "重试提交" : submitMutation.isPending ? "提交中..." : "确认提交"}
        </button>
      </div>
    </AppPage>
  );

  function idempotencyKeyForCurrentPayload() {
    return idempotencyKeyForPayload(currentPayload());
  }

  function currentPayload() {
    return JSON.stringify({
      formCode: flow.formCode ?? code,
      data: flow.values,
      selfSelected: flow.selfSelected,
      draftId: flow.draftId,
    });
  }
}

function summarizeRows(schema: MobileSchemaNode[], values: MobileFormValues) {
  return schema.flatMap((node) => summarizeNode(node, values));
}

function summarizeNode(node: MobileSchemaNode, values: MobileFormValues): Array<{
  id: string;
  label: string;
  value: string;
}> {
  if (node.type === "description") {
    return [];
  }
  if (node.children && node.type !== "table_list") {
    return node.children.flatMap((child) => summarizeNode(child, values));
  }
  return [
    {
      id: node.id,
      label: node.label ?? node.id,
      value: getFieldDefinition(node.type).summarize(node, values[node.id]),
    },
  ];
}

export default SubmitConfirmPage;
