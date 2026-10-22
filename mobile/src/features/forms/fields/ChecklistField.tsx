import { useMemo } from 'react';
import { fieldDescription } from '../schema/validators';
import type { MobileFieldProps } from '../schema/types';
import { CheckCard, ChecklistReadonlyList } from './ChecklistCard';
import { checklistItems, checklistResults } from './checklistValue';
import { fieldError, fieldLabel, isRequired } from './fieldShared';
import { useChecklistEntries } from './useChecklistEntries';

export type {
  ChecklistEntry,
  ChecklistItemDef,
  ChecklistResultOption,
  ChecklistValue,
} from './checklistValue';
// 值解析与摘要被 fieldRegistry、确认页直接具名导入，路径与符号保持不变
export {
  checklistItems,
  checklistResults,
  checklistSummary,
  entriesFromValue,
} from './checklistValue';

export function ChecklistField(props: MobileFieldProps) {
  const label = fieldLabel(props.node);
  const items = useMemo(() => checklistItems(props.node), [props.node]);
  const results = useMemo(() => checklistResults(props.node), [props.node]);
  const oneClick = props.node.props?.oneClick !== false;
  const {
    entries,
    expandedIds,
    expandItem,
    collapseItem,
    updateEntry,
    setStatus,
    setAll,
    clearAll,
  } = useChecklistEntries({
    nodeId: props.node.id,
    value: props.value,
    items,
    onValueChange: props.onValueChange,
  });
  const allChecked =
    entries.length > 0 &&
    entries.every((entry) => entry.status === (results[0]?.id ?? ''));

  const error = fieldError(props);
  const required = isRequired(props.node);
  const description = fieldDescription(props.node);

  if (props.mode === 'readonly') {
    return (
      <div className="af-checklist">
        <div className="af-checklist__header">
          <strong className="af-checklist__title">{label}</strong>
        </div>
        {description ? <p className="af-checklist__description">{description}</p> : null}
        <ChecklistReadonlyList items={items} entries={entries} results={results} />
      </div>
    );
  }

  return (
    <div className="af-checklist">
      <div className="af-checklist__header">
        <strong className="af-checklist__title">
          {label}
          {required ? <span className="af-checklist__required">*</span> : null}
        </strong>
        {oneClick && results.length > 0 ? (
          <button
            type="button"
            className="af-checklist__check-all"
            onClick={() => {
              if (allChecked) {
                clearAll();
              } else {
                const first = results[0];
                if (first) setAll(first.id);
              }
            }}
          >
            {allChecked ? '一键取消' : '一键勾选'}
          </button>
        ) : null}
      </div>
      {description ? <p className="af-checklist__description">{description}</p> : null}
      {error ? <div className="af-checklist__error">{error}</div> : null}
      <div className="af-checklist__list">
        {items.length === 0 ? (
          <div className="af-checklist__empty">尚未配置检查项</div>
        ) : null}
        {items.map((item) => {
          const entry = entries.find((e) => e.id === item.id) ?? {
            id: item.id,
            name: item.label,
            status: null,
            description: '',
            images: [],
          };
          const selectedResult = results.find((result) => result.id === entry.status);
          const expanded = expandedIds.has(item.id) && selectedResult != null;
          return (
            <CheckCard
              key={item.id}
              item={item}
              entry={entry}
              results={results}
              selectedResult={selectedResult}
              expanded={expanded}
              allowDescription={props.node.props?.allowDescription !== false}
              photoMaxCount={
                typeof props.node.props?.photoMaxCount === 'number'
                  ? props.node.props.photoMaxCount
                  : 9
              }
              onStatus={(status) => setStatus(item.id, status)}
              onExpand={(next) => {
                if (next) expandItem(item.id);
                else collapseItem(item.id);
              }}
              onEntry={(patch) => updateEntry(item.id, patch)}
            />
          );
        })}
      </div>
    </div>
  );
}
