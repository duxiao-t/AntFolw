import { Link } from 'react-router-dom';
import type { TaskCenterItem, TaskListItem, StartedProcessItem } from './tasks.api';

const cardStyle: React.CSSProperties = {
  display: 'grid',
  gap: 8,
  padding: 12,
  borderRadius: 8,
  background: 'var(--af-color-surface)',
  color: 'inherit',
  textDecoration: 'none',
  border: '1px solid var(--af-color-border)',
};

const headerStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  justifyContent: 'space-between',
  gap: 10,
};

const titleStyle: React.CSSProperties = {
  fontSize: '1rem',
  fontWeight: 600,
};

const metaStyle: React.CSSProperties = {
  color: 'rgba(0,0,0,0.56)',
  fontSize: '0.8125rem',
};

const badgeRowStyle: React.CSSProperties = {
  display: 'flex',
  gap: 6,
  flexWrap: 'wrap',
};

export function TaskCard({ item, returnSearch }: { item: TaskCenterItem; returnSearch: string }) {
  if (item.kind === 'process') {
    return <StartedProcessCard process={item.process} returnSearch={returnSearch} />;
  }
  return <ApprovalTaskCard task={item.task} returnSearch={returnSearch} />;
}

function ApprovalTaskCard({ task, returnSearch }: { task: TaskListItem; returnSearch: string }) {
  return (
    <Link to={`/tasks/${task.id}?${returnSearch}`} style={cardStyle}>
      <div style={headerStyle}>
        <span style={titleStyle}>{task.formName}</span>
        <span style={metaStyle}>{formatDate(task.createdAt)}</span>
      </div>
      <span style={metaStyle}>{task.applicantName}{task.applicantDepartment ? ` · ${task.applicantDepartment}` : ''}</span>
      <span style={metaStyle}>节点：{task.nodeName}</span>
      <div style={badgeRowStyle}>
        <StatusBadge tone={task.taskStatus === 'PENDING' ? 'warning' : 'primary'}>
          {taskStatusLabel(task.taskStatus)}
        </StatusBadge>
        <StatusBadge tone="neutral">{instanceStatusLabel(task.instanceStatus)}</StatusBadge>
      </div>
    </Link>
  );
}

function StartedProcessCard({ process, returnSearch }: { process: StartedProcessItem; returnSearch: string }) {
  return (
    <Link to={`/processes/${process.id}?${returnSearch}`} style={cardStyle}>
      <div style={headerStyle}>
        <span style={titleStyle}>{process.formName}</span>
        <span style={metaStyle}>{formatDate(process.startedAt)}</span>
      </div>
      <span style={metaStyle}>当前节点：{process.currentNodeName ?? '已结束'}</span>
      <div style={badgeRowStyle}>
        <StatusBadge tone="neutral">{instanceStatusLabel(process.status)}</StatusBadge>
      </div>
    </Link>
  );
}

function StatusBadge({ children, tone }: { children: React.ReactNode; tone: 'primary' | 'warning' | 'neutral' }) {
  const colors = {
    primary: ['rgba(22,119,255,0.12)', 'var(--af-color-primary)'],
    warning: ['rgba(245,158,11,0.14)', 'var(--af-color-warning)'],
    neutral: ['rgba(0,0,0,0.06)', 'rgba(0,0,0,0.72)'],
  } satisfies Record<typeof tone, [string, string]>;
  const [background, color] = colors[tone];
  return (
    <span style={{
      minHeight: 24,
      display: 'inline-flex',
      alignItems: 'center',
      padding: '2px 8px',
      borderRadius: 6,
      background,
      color,
      fontSize: '0.75rem',
      lineHeight: 1.4,
    }}>
      {children}
    </span>
  );
}

export function taskStatusLabel(status: TaskListItem['taskStatus']) {
  const labels: Record<string, string> = {
    PENDING: '待审批',
    APPROVED: '已同意',
    REJECTED: '已驳回',
    SKIPPED: '已跳过',
    CC: '抄送',
  };
  return labels[status] ?? status;
}

export function instanceStatusLabel(status: string) {
  const labels: Record<string, string> = {
    RUNNING: '进行中',
    APPROVED: '已通过',
    REJECTED: '已拒绝',
    WITHDRAWN: '已撤回',
  };
  return labels[status] ?? status;
}

function formatDate(value: string) {
  if (!value) {
    return '';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return `${date.getMonth() + 1}-${String(date.getDate()).padStart(2, '0')}`;
}
