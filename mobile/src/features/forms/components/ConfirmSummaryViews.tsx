import { useMemo, useState, type ReactNode } from 'react';
import type { SummaryEntry } from './confirmSummaryEntries';
import { compactLabels, pickerIds } from './confirmSummaryFormat';
import { identityMeta, usePickerDepartments, usePickerUsers } from './usePickerIdentities';

/** 左字段、右结果的一行；只有存在详情时才变成可展开按钮。 */
export function SummaryItemFrame({
  id,
  label,
  value,
  detail,
}: {
  id: string;
  label: string;
  value: ReactNode;
  detail: ReactNode;
}) {
  const [expanded, setExpanded] = useState(false);
  const expandable = detail != null;
  const detailId = `summary-detail-${id}`;
  const content = (
    <>
      <span className="confirm-row__k">{label}</span>
      <span className="confirm-row__value-wrap">
        <span className="confirm-row__v">{value}</span>
        {expandable ? <span className={`confirm-row__chevron${expanded ? ' is-open' : ''}`} aria-hidden="true">⌄</span> : null}
      </span>
    </>
  );
  return (
    <div className={`confirm-summary-item${expanded ? ' is-expanded' : ''}`} data-testid={`summary-${id}`}>
      {expandable ? (
        <button
          type="button"
          className="confirm-row confirm-row--expandable"
          aria-expanded={expanded}
          aria-controls={detailId}
          onClick={() => setExpanded((current) => !current)}
        >
          {content}
        </button>
      ) : <div className="confirm-row">{content}</div>}
      {expanded ? <div id={detailId} className="confirm-row__detail">{detail}</div> : null}
    </div>
  );
}

export function UserSummaryItem({ entry }: { entry: SummaryEntry }) {
  const endpoint = String(entry.node.props?.searchEndpoint ?? '/api/mobile/users');
  const multiple = entry.node.props?.multiple === true;
  const ids = useMemo(() => pickerIds(entry.rawValue, multiple), [entry.rawValue, multiple]);
  const users = usePickerUsers(endpoint, ids);
  const names = users.map((user) => user.displayName || `用户#${user.id}`);
  const detail = ids.length > 0 ? (
    <div className="confirm-summary-people confirm-summary-people--end">
      {users.map((user) => (
        <div key={user.id} className="confirm-summary-person">
          <strong>{user.displayName || `用户#${user.id}`}</strong>
          <small>{identityMeta(user, user.id)}</small>
        </div>
      ))}
    </div>
  ) : null;
  return (
    <SummaryItemFrame
      id={entry.id}
      label={entry.label}
      value={compactLabels(names)}
      detail={detail}
    />
  );
}

export function DepartmentSummaryItem({ entry }: { entry: SummaryEntry }) {
  const endpoint = String(entry.node.props?.searchEndpoint ?? '/api/mobile/departments');
  const multiple = entry.node.props?.multiple === true;
  const ids = useMemo(() => pickerIds(entry.rawValue, multiple), [entry.rawValue, multiple]);
  const departments = usePickerDepartments(endpoint, ids);
  const names = departments.map((department) => department.name || `部门 #${department.id}`);
  const detail = ids.length === 0 ? null : (
    <div className="confirm-summary-definition-list">
      {departments.flatMap((department) => [
        <div key={`${department.id}-name`}><span>部门名称</span><strong>{department.name || `部门 #${department.id}`}</strong></div>,
        <div key={`${department.id}-id`}><span>部门编号</span><strong>{department.id}</strong></div>,
      ])}
    </div>
  );
  return <SummaryItemFrame id={entry.id} label={entry.label} value={compactLabels(names)} detail={detail} />;
}
