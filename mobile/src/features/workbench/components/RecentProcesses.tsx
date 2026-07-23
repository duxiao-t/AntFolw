import { useNavigate } from "react-router-dom";
import type { RecentProcess } from "../../../shared/api/types";

const STATUS_LABEL: Record<RecentProcess["status"], string> = {
  RUNNING: "进行中",
  APPROVED: "已通过",
  REJECTED: "已驳回",
  WITHDRAWN: "已撤回",
};

export interface RecentProcessesProps {
  processes: ReadonlyArray<RecentProcess>;
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
  return `${date.getMonth() + 1}月${date.getDate()}日 ${hh}:${mm}`;
}

export function RecentProcesses({ processes }: RecentProcessesProps) {
  const navigate = useNavigate();

  if (processes.length === 0) {
    return (
      <p style={{ margin: 0, fontSize: 11, color: "var(--af-color-muted)" }}>还没有最近的流程</p>
    );
  }

  return (
    <ul className="af-recent-list" aria-label="最近流程">
      {processes.map((process) => {
        const isSuccess = process.status === "APPROVED";
        return (
          <li key={process.instanceId}>
            <button
              type="button"
              className="af-recent-list__item"
              onClick={() => navigate(`/processes/${process.instanceId}`)}
            >
              <i className={`af-recent-list__dot${isSuccess ? " af-recent-list__dot--success" : ""}`} />
              <span className="af-recent-list__main">
                <b>{process.formTitle}</b>
                <small>
                  {isSuccess ? "审批完成" : "等待审批"} · {formatTime(process.startedAt)}
                </small>
              </span>
              <span className={`af-tag${isSuccess ? " af-tag--success" : ""}`}>
                {STATUS_LABEL[process.status]}
              </span>
            </button>
          </li>
        );
      })}
    </ul>
  );
}

export default RecentProcesses;
