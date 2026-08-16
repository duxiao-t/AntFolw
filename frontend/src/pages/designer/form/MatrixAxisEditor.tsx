import {
  DeleteOutlined,
  HolderOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import {
  DndContext,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import {
  arrayMove,
  SortableContext,
  useSortable,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { Button, Input, Modal, Popconfirm, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import {
  createMatrixAxisId,
  type MatrixAxis,
  type MatrixAxisItem,
} from '../../../components/form-fields/matrixFill';

type MatrixAxisEditorProps = {
  axis: MatrixAxis;
  items: MatrixAxisItem[];
  max: number;
  onChange(items: MatrixAxisItem[]): void;
};

function SortableAxisItem({
  item,
  index,
  draft,
  onDraftChange,
  onCommit,
  onDelete,
  canDelete,
}: {
  item: MatrixAxisItem;
  index: number;
  draft: string;
  onDraftChange(value: string): void;
  onCommit(): void;
  onDelete(): void;
  canDelete: boolean;
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: item.id,
  });
  return (
    <div
      ref={setNodeRef}
      className={`matrix-axis-editor__row${isDragging ? ' matrix-axis-editor__row--dragging' : ''}`}
      style={{ transform: CSS.Transform.toString(transform), transition }}
    >
      <button
        type="button"
        className="matrix-axis-editor__handle"
        aria-label={`拖动第${index + 1}项`}
        {...attributes}
        {...listeners}
      >
        <HolderOutlined />
      </button>
      <Input
        value={draft}
        aria-label={`${index + 1}名称`}
        maxLength={100}
        onChange={(event) => onDraftChange(event.target.value)}
        onBlur={onCommit}
        onPressEnter={onCommit}
      />
      <Popconfirm
        title="删除这一项？"
        okText="删除"
        cancelText="取消"
        onConfirm={onDelete}
        disabled={!canDelete}
      >
        <Button
          type="text"
          danger
          icon={<DeleteOutlined />}
          aria-label={`删除${item.label}`}
          disabled={!canDelete}
        />
      </Popconfirm>
    </div>
  );
}

export function MatrixAxisEditor({ axis, items, max, onChange }: MatrixAxisEditorProps) {
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 4 } }));
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [bulkOpen, setBulkOpen] = useState(false);
  const [bulkText, setBulkText] = useState('');

  useEffect(() => {
    setDrafts(Object.fromEntries(items.map((item) => [item.id, item.label])));
  }, [items]);

  const bulkValidation = useMemo(() => validateBulkText(bulkText, max), [bulkText, max]);

  const addItem = () => {
    if (items.length >= max) return;
    let nextIndex = items.length + 1;
    let defaultLabel = axis === 'row' ? `矩阵行${nextIndex}` : `值${nextIndex}`;
    while (items.some((item) => item.label === defaultLabel)) {
      nextIndex += 1;
      defaultLabel = axis === 'row' ? `矩阵行${nextIndex}` : `值${nextIndex}`;
    }
    onChange([...items, { id: createMatrixAxisId(axis), label: defaultLabel }]);
  };

  const commitRename = (item: MatrixAxisItem) => {
    const label = (drafts[item.id] ?? item.label).trim();
    if (!label || items.some((candidate) => candidate.id !== item.id && candidate.label === label)) {
      setDrafts((current) => ({ ...current, [item.id]: item.label }));
      return;
    }
    if (label !== item.label) {
      onChange(items.map((candidate) => candidate.id === item.id ? { ...candidate, label } : candidate));
    }
  };

  const handleDragEnd = ({ active, over }: DragEndEvent) => {
    if (!over || active.id === over.id) return;
    const oldIndex = items.findIndex((item) => item.id === active.id);
    const newIndex = items.findIndex((item) => item.id === over.id);
    if (oldIndex >= 0 && newIndex >= 0) onChange(arrayMove(items, oldIndex, newIndex));
  };

  const openBulk = () => {
    setBulkText(items.map((item) => item.label).join('\n'));
    setBulkOpen(true);
  };

  const commitBulk = () => {
    if (bulkValidation) return;
    const labels = bulkText.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
    onChange(labels.map((label, index) => ({
      id: items[index]?.id ?? createMatrixAxisId(axis),
      label,
    })));
    setBulkOpen(false);
  };

  return (
    <div className="matrix-axis-editor">
      <DndContext sensors={sensors} onDragEnd={handleDragEnd}>
        <SortableContext items={items.map((item) => item.id)} strategy={verticalListSortingStrategy}>
          {items.map((item, index) => (
            <SortableAxisItem
              key={item.id}
              item={item}
              index={index}
              draft={drafts[item.id] ?? item.label}
              onDraftChange={(value) => setDrafts((current) => ({ ...current, [item.id]: value }))}
              onCommit={() => commitRename(item)}
              onDelete={() => onChange(items.filter((candidate) => candidate.id !== item.id))}
              canDelete={items.length > 1}
            />
          ))}
        </SortableContext>
      </DndContext>
      <div className="matrix-axis-editor__actions">
        <Button size="small" type="dashed" icon={<PlusOutlined />} disabled={items.length >= max} onClick={addItem}>
          新增{axis === 'row' ? '行' : '列'}
        </Button>
        <Button size="small" onClick={openBulk}>批量编辑</Button>
        <Typography.Text type="secondary">{items.length} / {max}</Typography.Text>
      </div>
      <Modal
        title={`批量编辑${axis === 'row' ? '行' : '列'}`}
        open={bulkOpen}
        okText="应用"
        cancelText="取消"
        okButtonProps={{ disabled: !!bulkValidation }}
        onOk={commitBulk}
        onCancel={() => setBulkOpen(false)}
      >
        <Typography.Text type="secondary">每行填写一个名称，保存后按当前位置保留现有 ID。</Typography.Text>
        <Input.TextArea
          aria-label={`批量${axis === 'row' ? '行' : '列'}名称`}
          rows={8}
          value={bulkText}
          placeholder={axis === 'row' ? '矩阵行1\n矩阵行2' : '值1\n值2'}
          status={bulkValidation ? 'error' : undefined}
          onChange={(event) => setBulkText(event.target.value)}
          style={{ marginTop: 12 }}
        />
        {bulkValidation ? <Typography.Text type="danger">{bulkValidation}</Typography.Text> : null}
      </Modal>
    </div>
  );
}

function validateBulkText(text: string, max: number) {
  const labels = text.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  if (labels.length === 0) return '至少保留一项';
  if (labels.length > max) return `最多保留${max}项`;
  if (new Set(labels).size !== labels.length) return '名称不能重复';
  return null;
}
