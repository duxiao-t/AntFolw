import { Link } from "react-router-dom";
import type { StartedProcessItem, TaskCenterItem, TaskListItem } from "./tasks.api";

export function TaskCard({ item, returnSearch }: { item: TaskCenterItem; returnSearch: string }) {
  return item.kind === "process" ? <StartedProcessCard process={item.process} returnSearch={returnSearch} /> : <ApprovalTaskCard task={item.task} returnSearch={returnSearch} />;
}

function ApprovalTaskCard({ task, returnSearch }: { task: TaskListItem; returnSearch: string }) {
  const unreadCc = task.taskStatus === "CC" && !task.readAt;
  const success = task.taskStatus === "APPROVED" || (task.instanceStatus === "APPROVED" && !unreadCc);
  const rework = task.taskType === "REWORK";
  const danger = rework || task.taskStatus === "REJECTED" || task.instanceStatus === "REJECTED";
  const tone = success ? " task-card--success" : danger ? " task-card--danger" : task.instanceStatus === "RUNNING" ? " task-card--info" : " task-card--muted";
  const chipTone = success ? " chip--success-soft" : danger ? " chip--danger-soft" : " chip--soft";
  const status = rework ? "待修改" : task.taskStatus === "PENDING" ? "待审批"
    : unreadCc ? "待查阅" : taskStatusLabel(task.taskStatus);
  const target = rework
    ? `/forms/${encodeURIComponent(task.formCode)}?reworkTaskId=${task.id}`
    : `/tasks/${task.id}?${returnSearch}`;
  return (
    <Link to={target} className={`task-card${tone}`}>
      <div className="task-card__main">
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 8 }}><div className="task-card__title">{task.formName}</div><span className={`chip${chipTone}`}>{status}</span></div>
        <div className="task-card__sub">当前节点:<b style={{ color: danger ? "var(--af-color-danger)" : "var(--af-color-primary)" }}>{task.nodeName}</b></div>
        <div className={`task-progress${success ? " task-progress--success" : danger ? " task-progress--danger" : ""}`}><i style={{ width: success ? "100%" : danger ? "35%" : "50%" }} /></div>
        <div className="task-card__footer"><span>发起人 <b style={{ color: "var(--af-color-text)" }}>{task.applicantName}</b></span><span>{formatTime(task.createdAt)}</span></div>
      </div><span className="task-card__chev">›</span>
    </Link>
  );
}

function StartedProcessCard({ process, returnSearch }: { process: StartedProcessItem; returnSearch: string }) {
  const success = process.status === "APPROVED";
  const danger = process.status === "REJECTED";
  const muted = process.status === "WITHDRAWN";
  const tone = success ? " task-card--success" : danger ? " task-card--danger" : muted ? " task-card--muted" : " task-card--info";
  const chipTone = success ? " chip--success-soft" : danger ? " chip--danger-soft" : muted ? " chip--ghost" : " chip--soft";
  return (
    <Link to={`/processes/${process.id}?${returnSearch}`} className={`task-card${tone}`}>
      <div className="task-card__main">
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 8 }}><div className="task-card__title">{process.formName}</div><span className={`chip${chipTone}`}>{instanceStatusLabel(process.status)}</span></div>
        <div className="task-card__sub">{process.currentNodeName ? <>当前节点:<b style={{ color: "var(--af-color-primary)" }}>{process.currentNodeName}</b></> : "流程已结束"}</div>
        <div className={`task-progress${success ? " task-progress--success" : danger ? " task-progress--danger" : muted ? " task-progress--ghost" : ""}`}><i style={{ width: success ? "100%" : danger ? "25%" : muted ? "33%" : "50%" }} /></div>
        <div className="task-card__footer"><span>发起人 <b style={{ color: "var(--af-color-text)" }}>我</b></span><span>{formatTime(process.startedAt)}</span></div>
      </div><span className="task-card__chev">›</span>
    </Link>
  );
}

export function taskStatusLabel(status: string) { return ({ PENDING: "待审批", APPROVED: "已完成", REJECTED: "已驳回", RESUBMITTED: "已重新提交", SKIPPED: "跳过", CC: "抄送" } as Record<string, string>)[status] ?? status; }
export function instanceStatusLabel(status: string) { return ({ RUNNING: "审批中", APPROVED: "已完成", REJECTED: "已驳回", WITHDRAWN: "已撤回" } as Record<string, string>)[status] ?? status; }
function formatTime(value: string) { const date = new Date(value); if (Number.isNaN(date.getTime())) return value; const hh = String(date.getHours()).padStart(2, "0"); const mm = String(date.getMinutes()).padStart(2, "0"); return `${date.getMonth() + 1}月${date.getDate()}日 ${hh}:${mm}`; }

export default TaskCard;
