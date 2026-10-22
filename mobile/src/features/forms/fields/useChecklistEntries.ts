import { useEffect, useState } from 'react';
import type { ChecklistEntry, ChecklistItemDef, ChecklistValue } from './checklistValue';
import { entriesFromValue, entryHasContent } from './checklistValue';

type Patch = Partial<Omit<ChecklistEntry, 'id' | 'name'>>;

/**
 * 检查项列表的本地状态：entries 与展开态。
 * 写值一律"先 setEntries 再回写表单"，避免父级 props.value 回流把中间态吃掉。
 */
export function useChecklistEntries({
  nodeId,
  value,
  items,
  onValueChange,
}: {
  nodeId: string;
  value: unknown;
  items: ChecklistItemDef[];
  onValueChange(fieldId: string, value: unknown): void;
}) {
  const [expandedIds, setExpandedIds] = useState<ReadonlySet<string>>(new Set());
  const [entries, setEntries] = useState<ChecklistValue>(() => entriesFromValue(value, items));

  useEffect(() => {
    setEntries(entriesFromValue(value, items));
  }, [value, items]);

  const expandItem = (id: string) => {
    setExpandedIds((prev) => {
      const next = new Set(prev);
      next.add(id);
      // 展开新项时，只自动收回"没有内容"的空展开项；有内容的一直保持展开
      for (const entry of entries) {
        if (entry.id !== id && next.has(entry.id) && !entryHasContent(entry)) {
          next.delete(entry.id);
        }
      }
      return next;
    });
  };

  const collapseItem = (id: string) => {
    setExpandedIds((prev) => {
      if (!prev.has(id)) return prev;
      const next = new Set(prev);
      next.delete(id);
      return next;
    });
  };

  const commit = (next: ChecklistValue) => {
    setEntries(next);
    onValueChange(nodeId, next);
  };

  const updateEntry = (id: string, patch: Patch) => {
    commit(entries.map((entry) => (entry.id === id ? { ...entry, ...patch } : entry)));
  };

  const setStatus = (id: string, status: string | null) => {
    commit(entries.map((entry) =>
      entry.id === id
        ? { ...entry, status, description: status ? entry.description : '' }
        : entry,
    ));
  };

  /** 批量设置结果：只改状态，保留描述与照片（"一键取消"走 clearAll）。 */
  const setAll = (resultId: string) => {
    commit(entries.map((entry) => ({ ...entry, status: resultId })));
  };

  /** 一键取消：清空状态，但不动描述（与单选"取消选择"清空描述不同）。 */
  const clearAll = () => {
    commit(entries.map((entry) => ({ ...entry, status: null })));
  };

  return {
    entries,
    expandedIds,
    expandItem,
    collapseItem,
    updateEntry,
    setStatus,
    setAll,
    clearAll,
  };
}
