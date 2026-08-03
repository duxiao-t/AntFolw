import { useNavigate } from "react-router-dom";
import type { RecentProcess } from "../../../shared/api/types";

const LABELS: Record<RecentProcess["status"], string> = { RUNNING: "审批中", APPROVED: "通过", REJECTED: "驳回", WITHDRAWN: "已撤回" };

export function RecentProcesses({ processes }: { processes: ReadonlyArray<RecentProcess> }) {
  const navigate = useNavigate();
  if (processes.length === 0) return <div className="recents"><p className="muted small" style={{ padding: 14, margin: 0 }}>还没有最近的流程</p></div>;
  return (
    <div className="recents">
      {processes.map((process) => {
        const success = process.status === "APPROVED";
        const danger = process.status === "REJECTED";
        const tone = success ? " chip--success-soft" : danger ? " chip--danger-soft" : process.status === "WITHDRAWN" ? " chip--ghost" : " chip--soft";
        return <button key={process.instanceId} className="recents__item" type="button" onClick={() => navigate(`/processes/${process.instanceId}`)}><span className={`recents__dot${success ? " recents__dot--success" : danger ? " recents__dot--danger" : ""}`} /><span className="recents__main"><b>{process.formTitle}</b><small>{success ? "审批完成" : danger ? "流程已驳回" : "等待审批"} · {formatTime(process.updatedAt || process.startedAt || "")}</small></span><span className={`chip${tone}`}>{LABELS[process.status]}</span><span className="recents__chev">›</span></button>;
      })}
    </div>
  );
}

function formatTime(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const now = new Date();
  const hh = String(date.getHours()).padStart(2, "0");
  const mm = String(date.getMinutes()).padStart(2, "0");
  if (date.toDateString() === now.toDateString()) return `今天 ${hh}:${mm}`;
  if (new Date(now.getTime() - 86400000).toDateString() === date.toDateString()) return `昨天 ${hh}:${mm}`;
  return `${date.getMonth() + 1}月${date.getDate()}日`;
}

export default RecentProcesses;
