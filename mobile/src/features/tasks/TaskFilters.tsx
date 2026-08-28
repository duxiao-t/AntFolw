import type { TaskView } from "./tasks.api";

const FILTERS: Record<TaskView, Array<{ value: string; label: string }>> = {
  pending: [{ value: "", label: "全部" }, { value: "RUNNING", label: "进行中" }, { value: "APPROVED", label: "已通过" }, { value: "PENDING", label: "我审批" }, { value: "CC", label: "抄送" }, { value: "URGENT", label: "紧急" }],
  process: [{ value: "", label: "全部" }, { value: "RUNNING", label: "审批中" }, { value: "APPROVED", label: "通过" }, { value: "REJECTED", label: "驳回" }, { value: "WITHDRAWN", label: "已撤回" }],
  done: [{ value: "", label: "全部" }, { value: "APPROVED", label: "通过" }, { value: "REJECTED", label: "驳回" }, { value: "SKIPPED", label: "跳过" }, { value: "CC", label: "抄送" }],
};

export function TaskFilters({ keyword, status, view, onKeywordChange, onStatusChange }: { keyword: string; status: string; view: TaskView; onKeywordChange: (keyword: string) => void; onStatusChange: (status: string) => void }) {
  return (
    <>
      <label className="searchbar" style={{ marginBottom: 10 }}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><circle cx="11" cy="11" r="7" /><path d="m21 21-4.3-4.3" /></svg>
        <input type="search" aria-label="搜索申请人或表单名" placeholder="搜索申请人 / 表单名" value={keyword} onChange={(event) => onKeywordChange(event.currentTarget.value)} />
      </label>
      <div className="chip-row">
        {FILTERS[view].map((option) => <button key={option.value || "all"} type="button" className={`chip${status === option.value ? " is-active" : ""}`} aria-pressed={status === option.value} onClick={() => onStatusChange(option.value)}>{option.label}</button>)}
      </div>
    </>
  );
}

export const STATUS_LABELS: Record<string, string> = { RUNNING: "进行中", APPROVED: "已通过", REJECTED: "已拒绝", WITHDRAWN: "已撤回" };
