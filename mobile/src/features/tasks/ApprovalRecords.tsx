import {
  LoopOutline,
  MailOutline,
  SetOutline,
  UserOutline,
} from 'antd-mobile-icons';
import { type ReactNode, useMemo, useState } from 'react';
import type { MobileFlowNode, MobileSchemaNode } from '../forms/schema/types';
import type {
  ApprovalRecord,
  ApprovalSummary,
  MobileHistoryItem,
} from './tasks.api';

type TimelineRecord = ApprovalRecord & {
  conditionKeys: string[];
  conditionLabels: string[];
};
type ParallelBranch = { id: string; label: string; records: TimelineRecord[] };
type TimelineBlock =
  | { kind: 'round'; key: string; roundNo: number }
  | { kind: 'condition'; key: string; label: string }
  | { kind: 'record'; record: TimelineRecord }
  | { kind: 'parallel'; key: string; branches: ParallelBranch[] };

type Props = {
  records: ApprovalRecord[];
  processSnapshot?: unknown;
  schema?: MobileSchemaNode[] | null;
  history?: MobileHistoryItem[] | null;
};

export function ApprovalRecords({
  records,
  processSnapshot,
  schema = [],
  history = [],
}: Props) {
  const [selected, setSelected] = useState<ApprovalRecord | null>(null);
  const blocks = useMemo(
    () => buildTimeline(records, processSnapshot, schema ?? [], history ?? []),
    [history, processSnapshot, records, schema],
  );
  if (blocks.length === 0) return <p className="muted small">暂无流转记录</p>;
  return (
    <>
      <ol className="approval-records__list">
        {blocks.map((block) => {
          if (block.kind === 'round') {
            return (
              <li key={block.key} className="approval-records__round">
                <strong>第 {block.roundNo} 次提交</strong>
              </li>
            );
          }
          if (block.kind === 'condition') {
            return (
              <li
                key={block.key}
                className="approval-records__gateway approval-records__gateway--condition"
              >
                <span
                  className="approval-records__gateway-marker"
                  aria-hidden="true"
                />
                <strong>{block.label}</strong>
              </li>
            );
          }
          if (block.kind === 'parallel') {
            const layout =
              block.branches.length === 2
                ? ' approval-records__parallel-grid--two'
                : ' approval-records__parallel-grid--stacked';
            return (
              <li
                key={block.key}
                className="approval-records__item approval-records__item--parallel"
              >
                <span
                  className="approval-records__marker approval-records__marker--parallel"
                  aria-hidden="true"
                >
                  并
                </span>
                <div className={`approval-records__parallel-grid${layout}`}>
                  {block.branches.map((branch) => (
                    <section
                      className="approval-records__parallel-branch"
                      key={`${block.key}-${branch.id}`}
                    >
                      <h4>{branch.label}</h4>
                      {branch.records.map((record) => (
                        <RecordCard
                          key={record.id}
                          record={record}
                          compact
                          onSelect={setSelected}
                        />
                      ))}
                    </section>
                  ))}
                </div>
              </li>
            );
          }
          return (
            <RecordItem
              key={block.record.id}
              record={block.record}
              onSelect={setSelected}
            />
          );
        })}
      </ol>
      {selected ? (
        <RecordSheet record={selected} onClose={() => setSelected(null)} />
      ) : null}
    </>
  );
}

function RecordItem({
  record,
  onSelect,
}: {
  record: TimelineRecord;
  onSelect: (record: ApprovalRecord) => void;
}) {
  return (
    <li className={`approval-records__item${itemTone(record)}`}>
      <TrackMarker record={record} />
      <RecordCard record={record} onSelect={onSelect} />
    </li>
  );
}

function TrackMarker({ record }: { record: ApprovalRecord }) {
  const kind = visualKind(record);
  let icon: ReactNode = null;
  if (kind === 'operation') icon = <UserOutline />;
  else if (kind === 'cc') icon = <MailOutline />;
  else if (kind === 'automation') icon = <SetOutline />;
  else if (kind === 'rejected') icon = <LoopOutline />;
  return (
    <span
      className={`approval-records__marker approval-records__marker--${kind}`}
      aria-hidden="true"
    >
      {icon}
    </span>
  );
}

function RecordCard({
  record,
  compact = false,
  onSelect,
}: {
  record: TimelineRecord;
  compact?: boolean;
  onSelect: (record: ApprovalRecord) => void;
}) {
  const submitted = record.status === 'SUBMITTED';
  const automation = record.recordKind === 'AUTOMATION';
  const kind = visualKind(record);
  const tag = operationTag(record);
  const content = (
    <>
      <div className="approval-record-card__top">
        <div>
          <span className="approval-record-card__node">{record.nodeName}</span>
          <strong>
            {record.operatorName || '未记录'}
            {record.sourceOperatorName ? (
              <em>
                （{operationSourceLabel(record.operationKind)}：
                {record.sourceOperatorName}）
              </em>
            ) : null}
          </strong>
        </div>
        <span
          className={`approval-record-card__status approval-record-card__status--${statusTone(record)}`}
        >
          {recordStatusLabel(record)}
        </span>
      </div>
      <p className="approval-record-card__comment">{record.comment || defaultComment(record)}</p>
      <footer>
        <span className="approval-record-card__meta">
          <span>
            {record.department || (automation ? '系统' : '未记录部门')} ·{' '}
            {record.employeeNo || (automation ? '自动执行' : '未分配工号')}
          </span>
          <time>
            {formatRecordTime(
              record.completedAt || record.receivedAt,
              record.status === 'PROCESSING' || record.status === 'RETURNED',
            )}
          </time>
        </span>
        {tag ? (
          <span
            className={`approval-record-card__tag approval-record-card__tag--${kind}`}
          >
            {tag}
          </span>
        ) : null}
      </footer>
    </>
  );
  const className = `approval-record-card approval-record-card--${kind}${compact ? ' approval-record-card--compact' : ''}`;
  return submitted || automation ? (
    <article className={className}>{content}</article>
  ) : (
    <button
      type="button"
      className={`${className} approval-record-card--button`}
      onClick={() => onSelect(record)}
    >
      {content}
    </button>
  );
}

export function approvalSummaryLabel(summary: ApprovalSummary) {
  return summary.complete
    ? '已完成'
    : `${summary.completedCount} 已完成 · ${summary.processingCount} 处理中`;
}

function RecordSheet({
  record,
  onClose,
}: {
  record: ApprovalRecord;
  onClose: () => void;
}) {
  const processing =
    record.status === 'PROCESSING' || record.status === 'RETURNED';
  const rejected =
    record.status === 'REJECTED' ||
    record.status === 'FAILED' ||
    record.operationKind === 'INVALIDATED';
  const chipTone = rejected
    ? ' chip--danger-soft'
    : processing
      ? ' chip--soft'
      : ' chip--success-soft';
  const personLabel =
    record.recordKind === 'CC'
      ? '抄送人'
      : record.recordKind === 'AUTOMATION'
        ? '执行者'
        : '审批人';
  return (
    <>
      <button
        type="button"
        className="sheet-mask is-open"
        aria-label="关闭审批记录详情"
        onClick={onClose}
      />
      <div
        className="sheet is-open"
        role="dialog"
        aria-modal="true"
        aria-label="审批记录详情"
      >
        <div className="sheet__inner">
          <div className="sheet__title">
            <h3>{processing ? '当前节点详情' : '审批记录详情'}</h3>
            <span className={`chip${chipTone} record-sheet__status`}>
              {recordStatusLabel(record)}
            </span>
          </div>
          <dl className="record-sheet__list">
            <div>
              <dt>审批节点</dt>
              <dd>{record.nodeName}</dd>
            </div>
            <div>
              <dt>{personLabel}</dt>
              <dd>
                {record.operatorName} · {record.employeeNo || '未分配工号'}
              </dd>
            </div>
            <div>
              <dt>所属部门</dt>
              <dd>{record.department || '未记录'}</dd>
            </div>
            <div>
              <dt>接收时间</dt>
              <dd>{formatDateTime(record.receivedAt)}</dd>
            </div>
            {record.completedAt ? (
              <div>
                <dt>完成时间</dt>
                <dd>{formatDateTime(record.completedAt)}</dd>
              </div>
            ) : (
              <div>
                <dt>当前状态</dt>
                <dd>{processing ? '等待处理' : recordStatusLabel(record)}</dd>
              </div>
            )}
            <div className="record-sheet__stack">
              <dt>审批意见</dt>
              <dd>{record.comment || defaultComment(record)}</dd>
            </div>
          </dl>
          <button
            className="btn btn--ghost btn--block record-sheet__close"
            type="button"
            onClick={onClose}
          >
            关闭
          </button>
        </div>
      </div>
    </>
  );
}

function buildTimeline(
  records: ApprovalRecord[],
  processSnapshot: unknown,
  schema: MobileSchemaNode[],
  history: MobileHistoryItem[],
): TimelineBlock[] {
  const root = normalizeSnapshot(processSnapshot);
  const enriched = mergeCc([...records, ...automationRecords(history, root)])
    .sort((a, b) => Date.parse(a.receivedAt) - Date.parse(b.receivedAt))
    .map((record) => enrichRecord(record, root, schema));
  const rawBlocks: Exclude<TimelineBlock, { kind: 'condition' | 'round' }>[] = [];
  for (let index = 0; index < enriched.length; ) {
    const record = enriched[index];
    if (!record) break;
    if (!record.parallelId) {
      rawBlocks.push({ kind: 'record', record });
      index++;
      continue;
    }
    const group: TimelineRecord[] = [];
    while (
      enriched[index]?.parallelId === record.parallelId &&
      (enriched[index]?.roundNo ?? 1) === (record.roundNo ?? 1)
    ) {
      const item = enriched[index++];
      if (item) group.push(item);
    }
    const branchNodes = parallelBranches(root, record.parallelId);
    const branchOrder = branchNodes.map((branch) => branch.id);
    const byBranch = new Map<string, TimelineRecord[]>();
    for (const item of group) {
      const key = item.branchId || 'default';
      byBranch.set(key, [...(byBranch.get(key) ?? []), item]);
    }
    const branches = [...byBranch.entries()]
      .sort(
        ([left], [right]) =>
          orderOf(branchOrder, left) - orderOf(branchOrder, right),
      )
      .map(([id, value], index) => ({
        id,
        label: nodeName(
          branchNodes.find((branch) => branch.id === id) ?? null,
          `分支 ${index + 1}`,
        ),
        records: value,
      }));
    rawBlocks.push({
      kind: 'parallel',
      key: `parallel-${record.parallelId}-${record.id}`,
      branches,
    });
  }
  const blocks: TimelineBlock[] = [];
  const showRounds = enriched.some((record) => (record.roundNo ?? 1) > 1);
  let previousRound = 0;
  let previousConditionKeys: string[] = [];
  for (const [blockIndex, block] of rawBlocks.entries()) {
    const first =
      block.kind === 'record' ? block.record : block.branches[0]?.records[0];
    const roundNo = first?.roundNo ?? 1;
    if (showRounds && roundNo !== previousRound) {
      blocks.push({ kind: 'round', key: `round-${roundNo}-${blockIndex}`, roundNo });
      previousConditionKeys = [];
    }
    const conditionKeys = first?.conditionKeys ?? [];
    const shared = commonPrefixLength(previousConditionKeys, conditionKeys);
    if (first) {
      first.conditionLabels.slice(shared).forEach((label, offset) => {
        const index = shared + offset;
        blocks.push({
          kind: 'condition',
          key: `condition-${first.conditionKeys[index]}-${blockIndex}`,
          label,
        });
      });
    }
    blocks.push(block);
    previousRound = roundNo;
    previousConditionKeys = conditionKeys;
  }
  return blocks;
}

function commonPrefixLength(left: string[], right: string[]) {
  let index = 0;
  while (index < left.length && left[index] === right[index]) index++;
  return index;
}

function automationRecords(
  history: MobileHistoryItem[],
  root: MobileFlowNode | null,
): ApprovalRecord[] {
  const records: ApprovalRecord[] = [];
  const open = new Map<string, number>();
  for (const event of [...history].sort(
    (a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt),
  )) {
    const nodeId = event.toNodeId || event.fromNodeId;
    const node = nodeId ? findNode(root, nodeId) : null;
    const start =
      event.action === 'DELAY_SCHEDULED' || event.action === 'TRIGGER_QUEUED';
    const end =
      event.action === 'DELAY_COMPLETED' ||
      event.action === 'TRIGGER_SUCCEEDED' ||
      event.action === 'TRIGGER_FAILED';
    const autoPass = event.action === 'AUTO_PASS';
    if (!nodeId || (!start && !end && !autoPass)) continue;
    const context = parallelContext(root, nodeId);
    if (start || autoPass) {
      const record: ApprovalRecord = {
        id: `history-${event.id}`,
        nodeId,
        nodeName: nodeName(node, autoPass ? '自动通过' : '自动节点'),
        recordKind: 'AUTOMATION',
        nodeType: node?.type || (autoPass ? 'APPROVAL' : 'TRIGGER'),
        parallelId: context?.parallelId,
        branchId: context?.branchId,
        status: autoPass ? 'COMPLETED' : 'PROCESSING',
        operatorName: '系统',
        comment: autoPass
          ? '系统已自动通过该节点。'
          : automationComment(event.action),
        receivedAt: event.createdAt,
        completedAt: autoPass ? event.createdAt : null,
      };
      records.push(record);
      if (!autoPass) open.set(nodeId, records.length - 1);
      continue;
    }
    const existing = open.get(nodeId);
    if (existing != null) {
      const record = records[existing];
      if (record)
        records[existing] = {
          ...record,
          status: event.action === 'TRIGGER_FAILED' ? 'FAILED' : 'COMPLETED',
          comment: automationComment(event.action),
          completedAt: event.createdAt,
        };
      open.delete(nodeId);
    } else {
      records.push({
        id: `history-${event.id}`,
        nodeId,
        nodeName: nodeName(node, '自动节点'),
        recordKind: 'AUTOMATION',
        nodeType: node?.type || 'TRIGGER',
        parallelId: context?.parallelId,
        branchId: context?.branchId,
        status: event.action === 'TRIGGER_FAILED' ? 'FAILED' : 'COMPLETED',
        operatorName: '系统',
        comment: automationComment(event.action),
        receivedAt: event.createdAt,
        completedAt: event.createdAt,
      });
    }
  }
  return records;
}

function mergeCc(records: ApprovalRecord[]): ApprovalRecord[] {
  const merged: ApprovalRecord[] = [];
  for (const record of records.sort(
    (a, b) => Date.parse(a.receivedAt) - Date.parse(b.receivedAt),
  )) {
    const previous = merged.at(-1);
    if (
      record.recordKind === 'CC' &&
      previous?.recordKind === 'CC' &&
      record.nodeId === previous.nodeId &&
      record.parallelId === previous.parallelId &&
      record.branchId === previous.branchId
      && record.roundNo === previous.roundNo
    ) {
      previous.operatorName = [previous.operatorName, record.operatorName]
        .filter(Boolean)
        .join('、');
      previous.department =
        previous.department === record.department
          ? previous.department
          : `${previous.department || record.department || '未记录部门'}等`;
      previous.status =
        previous.status === 'APPROVED' && record.status === 'APPROVED'
          ? 'APPROVED'
          : 'PROCESSING';
      previous.completedAt =
        previous.status === 'APPROVED' ? record.completedAt : null;
      continue;
    }
    merged.push({ ...record });
  }
  return merged;
}

function enrichRecord(
  record: ApprovalRecord,
  root: MobileFlowNode | null,
  schema: MobileSchemaNode[],
): TimelineRecord {
  const node = record.nodeId ? findNode(root, record.nodeId) : null;
  const context = record.nodeId ? parallelContext(root, record.nodeId) : null;
  const conditions = record.nodeId
    ? conditionContexts(root, record.nodeId, schema)
    : [];
  return {
    ...record,
    nodeType: record.nodeType || node?.type,
    parallelId: record.parallelId || context?.parallelId,
    branchId: record.branchId || context?.branchId,
    conditionKeys: conditions.map((item) => item.key),
    conditionLabels: conditions.map((item) => item.label),
  };
}

function conditionContexts(
  root: MobileFlowNode | null,
  nodeId: string,
  schema: MobileSchemaNode[],
) {
  const path: MobileFlowNode[] = [];
  if (!findPath(root, nodeId, path)) return [];
  const result: Array<{ key: string; label: string }> = [];
  for (let index = 0; index < path.length - 1; index++) {
    const gateway = path[index];
    const branch = path[index + 1];
    if (gateway?.type === 'CONDITIONS' && branch?.type === 'CONDITION')
      result.push({
        key: `${gateway.id}:${branch.id}`,
        label: `条件判断：${conditionLabel(branch, schema)}`,
      });
  }
  return result;
}

function conditionLabel(branch: MobileFlowNode, schema: MobileSchemaNode[]) {
  if (branch.props?.isDefault === true) return '其他情况';
  const groups = Array.isArray(branch.props?.groups)
    ? (branch.props.groups as Array<Record<string, unknown>>)
    : [];
  const groupType = branch.props?.groupsType === 'AND' ? ' 且 ' : ' 或 ';
  const labels = groups
    .map((group) => {
      const conditions = Array.isArray(group.conditions)
        ? (group.conditions as Array<Record<string, unknown>>)
        : [];
      const joiner = group.groupType === 'OR' ? ' 或 ' : ' 且 ';
      return conditions
        .map((condition) => formatCondition(condition, schema))
        .join(joiner);
    })
    .filter(Boolean);
  return labels.join(groupType) || branch.name || '已命中';
}

function formatCondition(
  condition: Record<string, unknown>,
  schema: MobileSchemaNode[],
) {
  const field = findSchemaNode(schema, String(condition.field ?? ''));
  const operator =
    (
      {
        '==': '=',
        '!=': '≠',
        '>': '>',
        '>=': '≥',
        '<': '<',
        '<=': '≤',
        in: '属于',
        contains: '包含',
      } as Record<string, string>
    )[String(condition.operator)] ?? String(condition.operator ?? '');
  const values = Array.isArray(condition.value)
    ? condition.value
    : [condition.value];
  const options = Array.isArray(field?.props?.options)
    ? (field.props.options as Array<Record<string, unknown>>)
    : [];
  const formatted = values
    .map(
      (value) =>
        options.find((option) => option.value === value)?.label ?? value,
    )
    .join('、');
  const suffix =
    typeof field?.props?.suffix === 'string'
      ? field.props.suffix
      : typeof field?.props?.unit === 'string'
        ? field.props.unit
        : '';
  return `${field?.label || condition.field || '字段'} ${operator} ${formatted}${suffix}`.trim();
}

function normalizeSnapshot(value: unknown): MobileFlowNode | null {
  if (typeof value === 'string') {
    try {
      return JSON.parse(value) as MobileFlowNode;
    } catch {
      return null;
    }
  }
  return value && typeof value === 'object' ? (value as MobileFlowNode) : null;
}

function findNode(
  node: MobileFlowNode | null,
  id: string,
): MobileFlowNode | null {
  if (!node) return null;
  if (node.id === id) return node;
  for (const branch of node.branchs ?? node.branches ?? []) {
    const hit = findNode(branch, id);
    if (hit) return hit;
  }
  const children = Array.isArray(node.children)
    ? node.children
    : node.children
      ? [node.children]
      : [];
  for (const child of children) {
    const hit = findNode(child, id);
    if (hit) return hit;
  }
  return null;
}

function findPath(
  node: MobileFlowNode | null,
  id: string,
  path: MobileFlowNode[],
): boolean {
  if (!node) return false;
  path.push(node);
  if (node.id === id) return true;
  for (const branch of node.branchs ?? node.branches ?? [])
    if (findPath(branch, id, path)) return true;
  const children = Array.isArray(node.children)
    ? node.children
    : node.children
      ? [node.children]
      : [];
  for (const child of children) if (findPath(child, id, path)) return true;
  path.pop();
  return false;
}

function parallelContext(
  root: MobileFlowNode | null,
  nodeId: string,
  current: { parallelId: string; branchId?: string } | null = null,
): { parallelId: string; branchId?: string } | null {
  if (!root) return null;
  if (root.id === nodeId) return current;
  for (const branch of root.branchs ?? root.branches ?? []) {
    const context =
      root.type === 'PARALLEL'
        ? { parallelId: root.id, branchId: branch.id }
        : current;
    const hit = parallelContext(branch, nodeId, context);
    if (hit) return hit;
  }
  const children = Array.isArray(root.children)
    ? root.children
    : root.children
      ? [root.children]
      : [];
  for (const child of children) {
    const hit = parallelContext(child, nodeId, current);
    if (hit) return hit;
  }
  return null;
}

function parallelBranches(root: MobileFlowNode | null, parallelId: string) {
  const parallel = findNode(root, parallelId);
  return parallel?.branchs ?? parallel?.branches ?? [];
}
function orderOf(order: string[], id: string) {
  const index = order.indexOf(id);
  return index < 0 ? Number.MAX_SAFE_INTEGER : index;
}
function findSchemaNode(
  nodes: MobileSchemaNode[],
  id: string,
): MobileSchemaNode | null {
  for (const node of nodes) {
    if (node.id === id) return node;
    const child = findSchemaNode(node.children ?? [], id);
    if (child) return child;
  }
  return null;
}
function nodeName(node: MobileFlowNode | null, fallback: string) {
  return (
    node?.name ||
    (typeof node?.props?.name === 'string' ? node.props.name : null) ||
    node?.label ||
    fallback
  );
}

function visualKind(record: ApprovalRecord) {
  if (
    record.status === 'REJECTED' ||
    record.status === 'FAILED' ||
    record.status === 'RETURNED' ||
    record.operationKind === 'INVALIDATED'
  )
    return 'rejected';
  if (record.operationKind) return 'operation';
  if (record.recordKind === 'CC' || record.nodeType === 'CC') return 'cc';
  if (record.recordKind === 'AUTOMATION') return 'automation';
  if (record.status === 'PROCESSING') return 'current';
  return 'done';
}

function itemTone(record: ApprovalRecord) {
  return ` approval-records__item--${visualKind(record)}`;
}
function statusTone(record: ApprovalRecord) {
  return record.status === 'REJECTED' ||
    record.status === 'FAILED' ||
    record.operationKind === 'INVALIDATED'
    ? 'rejected'
    : record.status === 'PROCESSING' || record.status === 'RETURNED'
      ? 'current'
      : 'done';
}
function operationTag(record: ApprovalRecord) {
  if (record.status === 'REJECTED') return '重提交';
  return (
    (
      {
        TRANSFER: '转交记录',
        DELEGATE: '委派记录',
        ADD_ASSIGNEE: '加签记录',
        INVALIDATED: '审批作废',
      } as Record<string, string>
    )[record.operationKind || ''] || ''
  );
}
function operationSourceLabel(kind?: string | null) {
  return kind === 'TRANSFER'
    ? '转交自'
    : kind === 'ADD_ASSIGNEE'
      ? '加签人'
      : '委派自';
}

function recordStatusLabel(record: ApprovalRecord) {
  if (record.recordKind === 'CC')
    return record.status === 'PROCESSING' ? '待查收' : '已抄送';
  if (record.operationKind === 'INVALIDATED') return '已作废';
  return (
    (
      {
        SUBMITTED: '已提交',
        PROCESSING: '审批中',
        APPROVED: '已通过',
        REJECTED: '已驳回',
        RETURNED: '待修改',
        RESUBMITTED: '已重新提交',
        COMPLETED: '已完成',
        FAILED: '执行失败',
      } as Record<string, string>
    )[record.status] ?? record.status
  );
}

function defaultComment(record: ApprovalRecord) {
  if (record.recordKind === 'CC')
    return record.status === 'PROCESSING'
      ? '等待查收抄送内容。'
      : '已抄送相关同事。';
  if (record.operationKind === 'INVALIDATED')
    return '并行分支已驳回，本次同意已作废。';
  return (
    (
      {
        SUBMITTED: '已完成表单填写并提交审批。',
        PROCESSING: '等待处理当前审批节点。',
        APPROVED: '已完成本节点审批。',
        REJECTED: '已驳回至直接上一级。',
        RETURNED: '原单已退回，等待修改后重新提交。',
        RESUBMITTED: '原单修改完成并重新提交审批。',
        COMPLETED: '系统已完成自动处理。',
        FAILED: '自动处理失败。',
      } as Record<string, string>
    )[record.status] ?? '审批节点状态已更新。'
  );
}

function automationComment(action: string) {
  return (
    (
      {
        DELAY_SCHEDULED: '等待设定时间后自动继续。',
        DELAY_COMPLETED: '延时结束，流程已自动继续。',
        TRIGGER_QUEUED: '自动任务等待执行。',
        TRIGGER_SUCCEEDED: '系统已完成自动处理。',
        TRIGGER_FAILED: '自动任务执行失败。',
      } as Record<string, string>
    )[action] ?? '系统已自动处理。'
  );
}

function formatRecordTime(value: string, received: boolean) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const dateTime = `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
  return received ? `${dateTime} 接收` : dateTime;
}

function formatDateTime(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleString('zh-CN', { hour12: false });
}

export default ApprovalRecords;
