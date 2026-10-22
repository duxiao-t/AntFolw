import { useMemo, useState } from 'react';
import type { MobileSchemaNode } from '../forms/schema/types';
import { RecordCard, RecordItem } from './ApprovalRecordCard';
import { RecordSheet } from './ApprovalRecordSheet';
import { buildTimeline } from './approvalRecordsTimeline';
import type {
  ApprovalRecord,
  MobileHistoryItem,
} from './tasks.api';

// 保持对外契约：调用方与测试仍从本模块取汇总文案。
export { approvalSummaryLabel } from './approvalRecordLabels';

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

export default ApprovalRecords;
