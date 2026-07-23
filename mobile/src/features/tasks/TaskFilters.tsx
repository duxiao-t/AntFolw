import type { TaskView } from "./tasks.api";

const STATUS_LABELS: Record<string, string> = {
  RUNNING: "进行中",
  APPROVED: "已通过",
  REJECTED: "已拒绝",
  WITHDRAWN: "已撤回",
};

export function TaskFilters({
  keyword,
  status,
  view,
  onKeywordChange,
  onStatusChange,
}: {
  keyword: string;
  status: string;
  view: TaskView;
  onKeywordChange: (keyword: string) => void;
  onStatusChange: (status: string) => void;
}) {
  return (
    <div className="af-stack" style={{ gap: 8 }}>
      <input
        className="af-input"
        type="search"
        aria-label="搜索表单、申请人或节点"
        placeholder="搜索表单、申请人或节点"
        value={keyword}
        onChange={(event) => onKeywordChange(event.currentTarget.value)}
      />
      <select
        className="af-input"
        aria-label="状态筛选"
        value={status}
        onChange={(event) => onStatusChange(event.currentTarget.value)}
        style={{ height: 36 }}
      >
        <option value="">全部状态</option>
        {(view === "done"
          ? [
              { value: "APPROVED", label: "已同意" },
              { value: "REJECTED", label: "已驳回" },
              { value: "SKIPPED", label: "已跳过" },
              { value: "CC", label: "抄送" },
            ]
          : [
              { value: "RUNNING", label: "进行中" },
              { value: "APPROVED", label: "已通过" },
              { value: "REJECTED", label: "已拒绝" },
              { value: "WITHDRAWN", label: "已撤回" },
            ]
        ).map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </div>
  );
}

export { STATUS_LABELS };
