import { Link } from "react-router-dom";
import type { TaskCenterItem, TaskListItem, StartedProcessItem } from "./tasks.api";

export function TaskCard({ item, returnSearch }: { item: TaskCenterItem; returnSearch: string }) {
  if (item.kind === "process") {
    return <StartedProcessCard process={item.process} returnSearch={returnSearch} />;
  }
  return <ApprovalTaskCard task={item.task} returnSearch={returnSearch} />;
}

function ApprovalTaskCard({ task, returnSearch }: { task: TaskListItem; returnSearch: string }) {
  return (
    <Link to={`/tasks/${task.id}?${returnSearch}`} className="af-task-card">
      <div className="af-task-card__meta">
        <span>{task.formName}</span>
        <span>{formatTime(task.createdAt)}</span>
      </div>
      <strong className="af-task-card__title">{task.applicantName}的{task.formName}</strong>
      <p className="af-task-card__summary">节点：{task.nodeName}</p>
      <div className="af-task-card__foot">
        <span className="af-task-card__avatar" aria-hidden="true">{task.applicantName.slice(0, 1)}</span>
        <span style={{ fontSize: 11 }}>
          {task.applicantDepartment ? `${task.applicantDepartment} · ` : ""}
          {task.applicantName}
        </span>
        <span className="af-task-card__tag-spacer" />
        <span className="af-tag af-tag--warning">待审批</span>
      </div>
    </Link>
  );
}

function StartedProcessCard({ process, returnSearch }: { process: StartedProcessItem; returnSearch: string }) {
  const tone =
    process.status === "APPROVED"
      ? "af-tag--success"
      : process.status === "REJECTED" || process.status === "WITHDRAWN"
        ? "af-tag--neutral"
        : "";
  return (
    <Link to={`/processes/${process.id}?${returnSearch}`} className="af-task-card">
      <div className="af-task-card__meta">
        <span>{process.formName}</span>
        <span>{formatTime(process.startedAt)}</span>
      </div>
      <strong className="af-task-card__title">我发起的{process.formName}</strong>
      <p className="af-task-card__summary">
        当前：{process.currentNodeName ?? "已结束"}
      </p>
      <div className="af-task-card__foot">
        <span className={`af-tag ${tone}`}>{instanceStatusLabel(process.status)}</span>
        <span className="af-task-card__tag-spacer" />
        <span style={{ color: "var(--af-color-muted)" }}>查看进度 {"\u203A"}</span>
      </div>
    </Link>
  );
}

export function taskStatusLabel(status: string): string {
  const labels: Record<string, string> = {
    PENDING: "待审批",
    APPROVED: "已同意",
    REJECTED: "已驳回",
    SKIPPED: "已跳过",
    CC: "抄送",
  };
  return labels[status] ?? status;
}

export function instanceStatusLabel(status: string): string {
  const labels: Record<string, string> = {
    RUNNING: "进行中",
    APPROVED: "已通过",
    REJECTED: "已拒绝",
    WITHDRAWN: "已撤回",
  };
  return labels[status] ?? status;
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

export default TaskCard;
