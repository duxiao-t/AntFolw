// 详情页的状态徽标文案与时间格式化。只依赖 `Date`，不碰流程树，
// 因此 Hero / 轨迹 / 自动化 / 列表页可以单独引它而不拉进其余逻辑。

export function instanceStatus(status: string, currentNodeId?: string | null) {
  if (currentNodeId === '__rework__') return { label: '待修改', tone: 'warning' as const };
  return ({
    RUNNING: { label: '审批中', tone: 'processing' as const },
    APPROVED: { label: '已通过', tone: 'success' as const },
    REJECTED: { label: '已驳回', tone: 'error' as const },
    WITHDRAWN: { label: '已撤回', tone: 'default' as const },
  } as const)[status] ?? { label: status || '状态未知', tone: 'default' as const };
}

export function formatDateTime(value?: string | null) {
  if (!value) return '未记录';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  });
}

export function formatDuration(start?: string | null, end?: string | null, now = Date.now()) {
  if (!start) return '未记录';
  const startedAt = Date.parse(start);
  const finishedAt = end ? Date.parse(end) : now;
  if (!Number.isFinite(startedAt) || !Number.isFinite(finishedAt) || finishedAt < startedAt) {
    return '未记录';
  }
  let seconds = Math.floor((finishedAt - startedAt) / 1000);
  const days = Math.floor(seconds / 86400);
  seconds %= 86400;
  const hours = Math.floor(seconds / 3600);
  seconds %= 3600;
  const minutes = Math.floor(seconds / 60);
  if (days > 0) return `${days}天 ${hours}小时`;
  if (hours > 0) return `${hours}小时 ${minutes}分钟`;
  if (minutes > 0) return `${minutes}分钟`;
  return `${seconds}秒`;
}
