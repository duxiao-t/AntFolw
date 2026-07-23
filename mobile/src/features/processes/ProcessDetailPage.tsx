import { useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { isApiError } from "../../shared/api/errors";
import { queryKeys } from "../../shared/api/queryKeys";
import { AppPage } from "../../shared/ui/AppPage";
import { PageError, PageSkeleton } from "../../shared/ui/PageStates";
import { DynamicFormRenderer } from "../forms/components/DynamicFormRenderer";
import type { MobileFormValues, MobileSchemaNode } from "../forms/schema/types";
import { ProcessSnapshotTimeline } from "./ProcessSnapshotTimeline";
import { fetchMobileInstanceDetail, withdrawMobileInstance } from "./processes.api";

export function ProcessDetailPage() {
  const { instanceId = "" } = useParams();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const numericInstanceId = Number(instanceId);
  const [statusNotice, setStatusNotice] = useState("");
  const [actionError, setActionError] = useState("");
  const withdrawKeyRef = useRef(createIdempotencyKey());

  const instanceQuery = useQuery({
    queryKey: queryKeys.instance(numericInstanceId),
    queryFn: () => fetchMobileInstanceDetail(numericInstanceId),
    enabled: Number.isSafeInteger(numericInstanceId) && numericInstanceId > 0,
    retry: 0,
  });

  const withdrawMutation = useMutation({
    mutationFn: () => withdrawMobileInstance(numericInstanceId, withdrawKeyRef.current),
    async onSuccess() {
      setActionError("");
      setStatusNotice("");
      await invalidateProcessCaches(queryClient, numericInstanceId);
      navigate(returnPath(searchParams), { replace: true });
    },
    async onError(error) {
      if (isApiError(error) && (error.status === 409 || error.body.code === "ALREADY_ACTED")) {
        setActionError("");
        setStatusNotice("流程状态已更新");
        await instanceQuery.refetch();
        return;
      }
      setActionError(error instanceof Error ? error.message : "撤回失败");
    },
  });

  const schema = useMemo(
    () => normalizeSchema(instanceQuery.data?.schema),
    [instanceQuery.data?.schema],
  );
  const values = useMemo(
    () => normalizeValues(instanceQuery.data?.formData),
    [instanceQuery.data?.formData],
  );

  if (!Number.isSafeInteger(numericInstanceId) || numericInstanceId <= 0) {
    return <PageError title="流程不存在" message="请返回列表重新打开。" />;
  }

  if (instanceQuery.isPending) {
    return <PageSkeleton rows={4} />;
  }

  if (instanceQuery.isError || !instanceQuery.data) {
    return <PageError onRetry={() => void instanceQuery.refetch()} />;
  }

  const instance = instanceQuery.data;
  const files = instance.files ?? [];
  const canWithdraw = instance.canWithdraw;
  const formInitial = (instance.formName ?? "流程").trim().charAt(0);

  return (
    <AppPage
      title="流程进度"
      action={
        <button
          type="button"
          className="af-link-button"
          style={{ fontSize: 16 }}
          aria-label="更多操作"
        >
          {"\u2022\u2022\u2022"}
        </button>
      }
    >
      <div className="af-section-stack">
        <section className="af-process-summary">
          <div className="af-process-summary__row">
            <span className="af-app-grid__icon" aria-hidden="true">{formInitial}</span>
            <div>
              <b>{instance.formName ?? `流程#${instance.id}`}</b>
              <small>实例 #{instance.id}</small>
            </div>
          </div>
          <div className="af-process-summary__status">
            <span>发起于 {formatTime(instance.startedAt)}</span>
            <span className="af-tag">{instanceStatusLabel(instance.status)}</span>
          </div>
        </section>

        {statusNotice ? (
          <p role="status" style={{ margin: 0, padding: "8px 10px", borderRadius: 6, background: "var(--af-color-primary-soft)", color: "var(--af-color-primary)", fontSize: 11 }}>
            {statusNotice}
          </p>
        ) : null}
        {actionError ? (
          <p role="alert" style={{ margin: 0, color: "var(--af-color-danger)", fontSize: 11 }}>
            {actionError}
          </p>
        ) : null}

        <section className="af-card">
          <div className="af-card__title"><span>审批进度</span></div>
          <ProcessSnapshotTimeline
            history={instance.history ?? []}
            processSnapshot={instance.processSnapshot}
          />
        </section>

        <section className="af-card">
          <div className="af-card__title"><span>申请摘要</span></div>
          {schema.length > 0 ? (
            <DynamicFormRenderer
              schema={schema}
              values={values}
              mode="readonly"
              onValueChange={() => undefined}
            />
          ) : (
            <p style={{ margin: 0, fontSize: 11, color: "var(--af-color-muted)" }}>暂无表单字段</p>
          )}
        </section>

        <section className="af-card">
          <div className="af-card__title"><span>附件</span></div>
          {files.length === 0 ? (
            <p style={{ margin: 0, fontSize: 11, color: "var(--af-color-muted)" }}>暂无附件</p>
          ) : (
            <ul style={{ listStyle: "none", margin: 0, padding: 0, display: "grid", gap: 6 }}>
              {files.map((file) => (
                <li key={file.id} className="af-recent-list__item" style={{ padding: "6px 0", borderTop: "0" }}>
                  <i className="af-recent-list__dot" />
                  <span className="af-recent-list__main">
                    <b>{file.name}</b>
                  </span>
                  <span className="af-tag">查看</span>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>

      {canWithdraw ? (
        <div className="af-bottom-bar">
          <button
            type="button"
            className="af-btn af-btn--danger-solid af-btn--block"
            disabled={withdrawMutation.isPending}
            onClick={() => {
              if (!window.confirm("确认撤回该流程？撤回后不可恢复。")) {
                return;
              }
              setActionError("");
              withdrawKeyRef.current = createIdempotencyKey();
              withdrawMutation.mutate();
            }}
          >
            {withdrawMutation.isPending ? "撤回中..." : "撤回流程"}
          </button>
        </div>
      ) : null}
    </AppPage>
  );
}

async function invalidateProcessCaches(
  queryClient: ReturnType<typeof useQueryClient>,
  instanceId: number,
) {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: queryKeys.bootstrap }),
    queryClient.invalidateQueries({ queryKey: ["mobile", "tasks"] }),
    queryClient.invalidateQueries({ queryKey: queryKeys.instance(instanceId) }),
  ]);
}

function returnPath(searchParams: URLSearchParams): string {
  const params = new URLSearchParams();
  const view = searchParams.get("returnView") ?? "process";
  const keyword = searchParams.get("returnKeyword");
  const status = searchParams.get("returnStatus");
  params.set("view", view);
  if (keyword) params.set("keyword", keyword);
  if (status) params.set("status", status);
  return `/tasks?${params.toString()}`;
}

function normalizeSchema(schema: unknown): MobileSchemaNode[] {
  if (Array.isArray(schema)) {
    return schema as MobileSchemaNode[];
  }
  return [];
}

function normalizeValues(formData: Record<string, unknown> | null | undefined): MobileFormValues {
  if (!formData || typeof formData !== "object" || Array.isArray(formData)) {
    return {};
  }
  return formData;
}

function instanceStatusLabel(status: string): string {
  switch (status) {
    case "RUNNING":
      return "进行中";
    case "APPROVED":
      return "已通过";
    case "REJECTED":
      return "已驳回";
    case "WITHDRAWN":
      return "已撤回";
    default:
      return status;
  }
}

function formatTime(value: string): string {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const now = new Date();
  const sameDay = date.toDateString() === now.toDateString();
  const yesterday = new Date(now.getTime() - 86400000).toDateString() === date.toDateString();
  const hh = String(date.getHours()).padStart(2, "0");
  const mm = String(date.getMinutes()).padStart(2, "0");
  if (sameDay) return `今天 ${hh}:${mm}`;
  if (yesterday) return `昨天 ${hh}:${mm}`;
  return `${date.getMonth() + 1}-${String(date.getDate()).padStart(2, "0")}`;
}

function createIdempotencyKey() {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return `withdraw-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export default ProcessDetailPage;
