import type { TaskView } from './tasks.api';

const tabs: Array<{ key: TaskView; label: string }> = [
  { key: 'pending', label: '待办' },
  { key: 'done', label: '已处理' },
  { key: 'process', label: '我发起的' },
];

const toolbarStyle: React.CSSProperties = {
  display: 'grid',
  gap: 10,
};

const tabListStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(3, minmax(0, 1fr))',
  gap: 6,
  padding: 4,
  borderRadius: 8,
  background: 'var(--af-color-surface)',
};

const inputStyle: React.CSSProperties = {
  minHeight: 44,
  border: '1px solid var(--af-color-border)',
  borderRadius: 8,
  padding: '0 12px',
  font: 'inherit',
  background: 'var(--af-color-surface)',
  color: 'var(--af-color-text)',
};

const filterRowStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'minmax(0, 1fr) 136px',
  gap: 8,
};

const selectStyle: React.CSSProperties = {
  minHeight: 44,
  border: '1px solid var(--af-color-border)',
  borderRadius: 8,
  padding: '0 10px',
  font: 'inherit',
  background: 'var(--af-color-surface)',
  color: 'var(--af-color-text)',
};

export function TaskFilters({
  view,
  keyword,
  status,
  onViewChange,
  onKeywordChange,
  onStatusChange,
}: {
  view: TaskView;
  keyword: string;
  status: string;
  onViewChange: (view: TaskView) => void;
  onKeywordChange: (keyword: string) => void;
  onStatusChange: (status: string) => void;
}) {
  return (
    <div style={toolbarStyle}>
      <div role="tablist" aria-label="任务视图" style={tabListStyle}>
        {tabs.map((tab) => (
          <button
            key={tab.key}
            type="button"
            role="tab"
            aria-selected={view === tab.key}
            onClick={() => onViewChange(tab.key)}
            style={tabButtonStyle(view === tab.key)}
          >
            {tab.label}
          </button>
        ))}
      </div>
      <div style={filterRowStyle}>
        <input
          type="search"
          aria-label="搜索任务"
          placeholder="搜索表单、申请人或节点"
          value={keyword}
          onChange={(event) => onKeywordChange(event.currentTarget.value)}
          style={inputStyle}
        />
        <select
          aria-label="状态筛选"
          value={status}
          onChange={(event) => onStatusChange(event.currentTarget.value)}
          style={selectStyle}
        >
          <option value="">全部状态</option>
          {statusOptions(view).map((option) => (
            <option key={option.value} value={option.value}>{option.label}</option>
          ))}
        </select>
      </div>
    </div>
  );
}

function statusOptions(view: TaskView) {
  if (view === 'done') {
    return [
      { value: 'APPROVED', label: '已同意' },
      { value: 'REJECTED', label: '已驳回' },
      { value: 'SKIPPED', label: '已跳过' },
      { value: 'CC', label: '抄送' },
    ];
  }
  return [
    { value: 'RUNNING', label: '进行中' },
    { value: 'APPROVED', label: '已通过' },
    { value: 'REJECTED', label: '已拒绝' },
    { value: 'WITHDRAWN', label: '已撤回' },
  ];
}

function tabButtonStyle(active: boolean): React.CSSProperties {
  return {
    minHeight: 36,
    border: 'none',
    borderRadius: 6,
    background: active ? 'var(--af-color-primary)' : 'transparent',
    color: active ? 'var(--af-color-on-primary)' : 'var(--af-color-text)',
    font: 'inherit',
    cursor: 'pointer',
  };
}
