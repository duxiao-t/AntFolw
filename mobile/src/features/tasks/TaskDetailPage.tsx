import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { isApiError } from "../../shared/api/errors";
import { queryKeys } from "../../shared/api/queryKeys";
import { AppPage } from "../../shared/ui/AppPage";
import { PageError, PageSkeleton } from "../../shared/ui/PageStates";
import { DynamicFormRenderer } from "../forms/components/DynamicFormRenderer";
import type { MobileFormValues, MobileSchemaNode } from "../forms/schema/types";
import { ApproveSheet } from "./ApproveSheet";
import { RejectSheet } from "./RejectSheet";
import { TaskTimeline } from "./TaskTimeline";
import {
  fetchTaskDetail,
  runTaskAction,
  type TaskActionPayload,
} from "./tasks.api";

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
  const formInitial = "假";

  const detailQuery = useQuery({
    queryKey: queryKeys.taskDetail(numericTaskId),
    queryFn: () => fetchTaskDetail(numericTaskId),
    enabled: Number.isSafeInteger(numericTaskId) && numericTaskId > 0,
    retry: 0,
  });

  const actionMutation = useMutation({
    mutationFn: async ({
      action,
      payload,
      idempotencyKey,
    }: {
      action: "approve" | "reject";
      payload: TaskActionPayload;
      idempotencyKey: string;
    }) => runTaskAction(numericTaskId, action, payload, idempotencyKey),
    async onSuccess() {
      setActionError("");
      setStatusNotice("");
      setApproveOpen(false);
      setRejectOpen(false);
      await invalidateTaskCaches(queryClient, numericTaskId, detailQuery.data?.task.instanceId);
      navigate(returnPath(searchParams), { replace: true });
    },
    async onError(error) {
      if (isApiError(error) && error.status === 409) {
        setApproveOpen(false);
        setRejectOpen(false);
        setActionError("");
        setStatusNotice("任务状态已更新");
        await detailQuery.refetch();
        return;
      }
      setActionError(error instanceof Error ? error.message : "操作失败");
    },
  });

  const schema = useMemo(
    () => normalizeSchema(detailQuery.data?.schema),
    [detailQuery.data?.schema],
  );
  const values = useMemo(
    () => normalizeValues(detailQuery.data?.formData),
    [detailQuery.data?.formData],
  );
  const allowedActions = detailQuery.data?.allowedActions ?? [];
  const canApprove = allowedActions.includes("APPROVE");
  const canReject = allowedActions.includes("REJECT");
  const showActions = canApprove || canReject;

  if (!Number.isSafeInteger(numericTaskId) || numericTaskId <= 0) {
    return <PageError title="任务不存在" message="请返回任务中心重新打开。" />;
  }

  if (detailQuery.isPending) {
    return <PageSkeleton rows={5} />;
  }

  if (detailQuery.isError || !detailQuery.data) {
    return <PageError onRetry={() => void detailQuery.refetch()} />;
  }

  const detail = detailQuery.data;
  const task = detail.task;

  return (
    <AppPage
      title="审批详情"
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
        <section className="af-detail-head">
          <div className="af-detail-head__row">
            <span className="af-app-grid__icon" aria-hidden="true">{formInitial}</span>
            <div>
              <b>{task.applicantName}的{task.formName}</b>
              <small>
                {task.applicantDepartment ? `${task.applicantDepartment} · ` : ""}
                {formatTime(task.createdAt)}
              </small>
            </div>
          </div>
          <div className="af-detail-head__status">
            <span>当前节点：{task.nodeName}</span>
            <span className="af-tag af-tag--warning">待你处理</span>
          </div>
        </section>

        {statusNotice ? (
          <p role="status" style={{ margin: 0, padding: "8px 10px", borderRadius: 6, background: "var(--af-color-primary-soft)", color: "var(--af-color-primary)", fontSize: 11 }}>
            {statusNotice}
          </p>
        ) : null}

        <section className="af-card">
          <div className="af-card__title"><span>申请内容</span></div>
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
          <div className="af-card__title"><span>审批进度</span></div>
          <TaskTimeline history={detail.history} processSnapshot={detail.processSnapshot} />
        </section>

        <section className="af-card">
          <div className="af-card__title"><span>附件</span></div>
          {detail.files.length === 0 ? (
            <p style={{ margin: 0, fontSize: 11, color: "var(--af-color-muted)" }}>暂无附件</p>
          ) : (
            <ul style={{ listStyle: "none", margin: 0, padding: 0, display: "grid", gap: 6 }}>
              {detail.files.map((file) => (
                <li key={file.id} className="af-recent-list__item" style={{ padding: "6px 0", borderTop: "0" }}>
                  <i className="af-recent-list__dot" />
                  <span className="af-recent-list__main">
                    <b>{file.name}</b>
                    <small>{file.size ? `${Math.round(file.size / 1024)} KB` : ""}</small>
                  </span>
                  <span className="af-tag">查看</span>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>

      {showActions ? (
        <div className="af-action-bar af-action-bar--tri">
          <button
            type="button"
            className="af-btn af-btn--ghost"
            onClick={() => navigate(returnPath(searchParams))}
          >
            更多
          </button>
          {canReject ? (
            <button
              type="button"
              className="af-btn af-btn--danger"
              disabled={actionMutation.isPending}
              onClick={() => {
                setActionError("");
                setRejectOpen(true);
              }}
            >
              驳回
            </button>
          ) : (
            <span />
          )}
          {canApprove ? (
            <button
              type="button"
              className="af-btn"
              disabled={actionMutation.isPending}
              onClick={() => {
                setActionError("");
                setApproveOpen(true);
              }}
            >
              同意
            </button>
          ) : null}
        </div>
      ) : null}

      <ApproveSheet
        open={approveOpen}
        loading={actionMutation.isPending}
        error={approveOpen ? actionError : undefined}
        onClose={() => {
          if (!actionMutation.isPending) {
            setApproveOpen(false);
            setActionError("");
          }
        }}
        onSubmit={(payload, idempotencyKey) => {
          actionMutation.mutate({ action: "approve", payload, idempotencyKey });
        }}
      />

      <RejectSheet
        open={rejectOpen}
        loading={actionMutation.isPending}
        error={rejectOpen ? actionError : undefined}
        rejectTargets={detail.rejectTargets}
        onClose={() => {
          if (!actionMutation.isPending) {
            setRejectOpen(false);
            setActionError("");
          }
        }}
        onSubmit={(payload, idempotencyKey) => {
          actionMutation.mutate({ action: "reject", payload, idempotencyKey });
        }}
      />
    </AppPage>
  );
}

async function invalidateTaskCaches(
  queryClient: ReturnType<typeof useQueryClient>,
  taskId: number,
  instanceId?: number,
) {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: queryKeys.bootstrap }),
    queryClient.invalidateQueries({ queryKey: ["mobile", "tasks"] }),
    queryClient.invalidateQueries({ queryKey: queryKeys.taskDetail(taskId) }),
    instanceId
      ? queryClient.invalidateQueries({ queryKey: queryKeys.instance(instanceId) })
      : Promise.resolve(),
  ]);
}

function returnPath(searchParams: URLSearchParams): string {
  const params = new URLSearchParams();
  const view = searchParams.get("returnView");
  const keyword = searchParams.get("returnKeyword");
  const status = searchParams.get("returnStatus");
  if (view) params.set("view", view);
  if (keyword) params.set("keyword", keyword);
  if (status) params.set("status", status);
  const query = params.toString();
  return query ? `/tasks?${query}` : "/tasks";
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

export default TaskDetailPage;
