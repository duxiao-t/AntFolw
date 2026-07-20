import classes from './RecentProcesses.module.css';
import type { RecentProcess } from '../../../shared/api/types';

const STATUS_LABEL: Record<RecentProcess['status'], string> = {
  RUNNING: '审批中',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  WITHDRAWN: '已撤回',
};

export interface RecentProcessesProps {
  processes: ReadonlyArray<RecentProcess>;
  onSelect?: (process: RecentProcess) => void;
}

export function RecentProcesses({ processes, onSelect }: RecentProcessesProps) {
  if (processes.length === 0) {
    return null;
  }
  return (
    <ul className={classes.list} aria-label="最近流程">
      {processes.map((process) => (
        <li key={process.instanceId}>
          <button
            type="button"
            className={classes.item}
            onClick={() => onSelect?.(process)}
            style={{ minHeight: 56, minWidth: 44 }}
          >
            <span style={{ display: 'flex', flexDirection: 'column', gap: 4, flex: 1 }}>
              <span className={classes.title}>{process.formTitle}</span>
              <span className={classes.meta}>{process.formCode}</span>
            </span>
            <span className={`${classes.statusBadge} ${classes[`status_${process.status}`]}`}>
              {STATUS_LABEL[process.status]}
            </span>
          </button>
        </li>
      ))}
    </ul>
  );
}

export default RecentProcesses;
