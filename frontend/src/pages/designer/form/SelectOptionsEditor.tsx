import {
  closestCenter,
  DndContext,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import {
  arrayMove,
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import {
  Button,
  Checkbox,
  Input,
  Modal,
  Popover,
  Radio,
  Space,
  Tooltip,
  Typography,
} from 'antd';
import {
  CloseOutlined,
  EyeInvisibleOutlined,
  EyeOutlined,
  HolderOutlined,
  PlusOutlined,
  QuestionCircleOutlined,
} from '@ant-design/icons';
import { useMemo, useState } from 'react';
import {
  createSelectOption,
  defaultValues,
  mergeSelectOptions,
  normalizeSelectOptions,
  normalizeDefaultValue,
  OTHER_OPTION_VALUE,
  parseBulkSelectOptions,
  SELECT_OPTION_COLORS,
  type SelectOption,
  type SelectOptionValue,
} from '../../../registry/selectOptions';

type SelectOptionsEditorProps = {
  value?: unknown;
  multiple?: boolean;
  defaultValue?: unknown;
  enableColors?: boolean;
  onChange(options: SelectOption[]): void;
  onDefaultChange(value: SelectOptionValue | SelectOptionValue[] | undefined): void;
  onEnableColorsChange(enabled: boolean, options: SelectOption[]): void;
};

type SortableOptionRowProps = {
  option: SelectOption;
  multiple: boolean;
  isDefault: boolean;
  enableColors: boolean;
  onFocus(): void;
  onChange(patch: Partial<SelectOption>): void;
  onDefaultChange(): void;
  onRemove(): void;
  onAddAfter(): void;
  onPaste(text: string): void;
  onToggleHidden(): void;
  onColorChange(color: string): void;
};

function ColorPicker({
  color,
  onChange,
}: {
  color?: string;
  onChange(color: string): void;
}) {
  const content = (
    <div className="select-options-editor__color-menu">
      {SELECT_OPTION_COLORS.map((item) => (
        <button
          key={item.value}
          type="button"
          className={`select-options-editor__color-choice${color === item.value ? ' is-selected' : ''}`}
          aria-label={item.name}
          title={item.name}
          onClick={() => onChange(item.value)}
        >
          <span style={{ backgroundColor: item.value }} />
        </button>
      ))}
    </div>
  );
  return (
    <Popover trigger="click" content={content} placement="bottomRight">
      <button
        type="button"
        className="select-options-editor__color-trigger"
        aria-label="选择选项颜色"
        title="选择选项颜色"
      >
        <span style={{ backgroundColor: color ?? SELECT_OPTION_COLORS[0].value }} />
      </button>
    </Popover>
  );
}

function SortableOptionRow({
  option,
  multiple,
  isDefault,
  enableColors,
  onFocus,
  onChange,
  onDefaultChange,
  onRemove,
  onAddAfter,
  onPaste,
  onToggleHidden,
  onColorChange,
}: SortableOptionRowProps) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: option.id ?? String(option.value),
  });
  const rowStyle = {
    transform: CSS.Transform.toString(transform),
    transition,
  };

  return (
    <div
      ref={setNodeRef}
      style={rowStyle}
      className={`select-options-editor__row${isDragging ? ' is-dragging' : ''}${option.hidden ? ' is-hidden' : ''}${option.isOther ? ' is-other' : ''}`}
      onFocus={onFocus}
    >
      <div className="select-options-editor__row-main">
        <button
          type="button"
          className="select-options-editor__drag-handle"
          aria-label={`拖动${option.label}`}
          {...attributes}
          {...listeners}
        >
          <HolderOutlined />
        </button>
        <Tooltip title={option.isOther ? '其他项不能设为默认' : '设置为默认'}>
          <span className="select-options-editor__default-control">
            {multiple ? (
              <Checkbox
                aria-label={`将${option.label}设为默认`}
                checked={isDefault}
                disabled={option.isOther || option.hidden}
                onChange={onDefaultChange}
              />
            ) : (
              <Radio
                aria-label={`将${option.label}设为默认`}
                checked={isDefault}
                disabled={option.isOther || option.hidden}
                onChange={onDefaultChange}
              />
            )}
          </span>
        </Tooltip>
        {enableColors && (
          <ColorPicker color={option.color} onChange={onColorChange} />
        )}
        <Input
          className="select-options-editor__label-input"
          aria-label={`选项${option.label}`}
          value={option.label}
          onChange={(event) => onChange({ label: event.target.value })}
          onPaste={(event) => {
            const text = event.clipboardData.getData('text');
            if (text.includes('\n') || text.includes('\r')) {
              event.preventDefault();
              onPaste(text);
            }
          }}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault();
              onAddAfter();
            }
          }}
        />
        <div className="select-options-editor__row-actions">
          <button
            type="button"
            className="select-options-editor__icon-button"
            aria-label={option.hidden ? `显示${option.label}` : `隐藏${option.label}`}
            title={option.hidden ? `显示${option.label}` : `隐藏${option.label}`}
            onClick={onToggleHidden}
          >
            {option.hidden ? <EyeInvisibleOutlined /> : <EyeOutlined />}
          </button>
          <button
            type="button"
            className="select-options-editor__icon-button is-danger"
            aria-label={`删除${option.label}`}
            title={`删除${option.label}`}
            onClick={onRemove}
          >
            <CloseOutlined />
          </button>
        </div>
      </div>
      {option.isOther && (
        <div className="select-options-editor__other-preview" aria-hidden="true">
          请输入
        </div>
      )}
    </div>
  );
}

export function SelectOptionsEditor({
  value,
  multiple = false,
  defaultValue,
  enableColors = false,
  onChange,
  onDefaultChange,
  onEnableColorsChange,
}: SelectOptionsEditorProps) {
  const options = normalizeSelectOptions(value);
  const [bulkOpen, setBulkOpen] = useState(false);
  const [bulkText, setBulkText] = useState('');
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 4 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );
  const selectedDefaults = useMemo(
    () => defaultValues(defaultValue, multiple),
    [defaultValue, multiple],
  );

  const commit = (next: SelectOption[]) => onChange(normalizeSelectOptions(next));
  const addAfter = (index: number, initial?: SelectOption) => {
    const next = [...options];
    const option = initial ?? createSelectOption(options.length + 1, enableColors ? SELECT_OPTION_COLORS[0].value : undefined);
    next.splice(index, 0, option);
    commit(next);
  };

  const handleDragEnd = ({ active, over }: DragEndEvent) => {
    if (!over || active.id === over.id) return;
    const oldIndex = options.findIndex((option) => option.id === active.id);
    const newIndex = options.findIndex((option) => option.id === over.id);
    if (oldIndex < 0 || newIndex < 0) return;
    commit(arrayMove(options, oldIndex, newIndex));
  };

  const updateOption = (index: number, patch: Partial<SelectOption>) => {
    const current = options[index];
    if (!current) return;
    const next = options.map((option, itemIndex) => {
      if (itemIndex !== index) return option;
      return { ...option, ...patch };
    });
    commit(next);
  };

  const toggleDefault = (option: SelectOption) => {
    if (option.isOther || option.hidden) return;
    const values = selectedDefaults;
    if (multiple) {
      const next = values.includes(option.value)
        ? values.filter((item) => item !== option.value)
        : [...values, option.value];
      onDefaultChange(normalizeDefaultValue(next, options, true));
      return;
    }
    onDefaultChange(values[0] === option.value ? undefined : option.value);
  };

  const removeOption = (index: number) => {
    const removed = options[index];
    if (!removed) return;
    commit(options.filter((_, itemIndex) => itemIndex !== index));
    if (multiple) {
      onDefaultChange(selectedDefaults.filter((item) => item !== removed.value));
    } else if (selectedDefaults[0] === removed.value) {
      onDefaultChange(undefined);
    }
  };

  const toggleHidden = (index: number) => {
    const option = options[index];
    if (!option) return;
    updateOption(index, { hidden: !option.hidden });
    if (!option.hidden) {
      if (multiple) {
        onDefaultChange(selectedDefaults.filter((item) => item !== option.value));
      } else if (selectedDefaults[0] === option.value) {
        onDefaultChange(undefined);
      }
    }
  };

  const applyBulk = (mode: 'append' | 'replace') => {
    const parsed = parseBulkSelectOptions(bulkText);
    if (parsed.length === 0) return;
    const next = mode === 'replace' ? parsed : mergeSelectOptions(options, parsed);
    commit(next);
    setBulkText('');
    setBulkOpen(false);
  };

  const addOther = () => {
    if (options.some((option) => option.isOther)) return;
    addAfter(options.length, {
      id: `other_${Date.now()}`,
      label: '其他',
      value: OTHER_OPTION_VALUE,
      isOther: true,
    });
  };

  return (
    <div className="select-options-editor" onPointerDown={(event) => event.stopPropagation()}>
      <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
        <SortableContext
          items={options.map((option) => option.id ?? String(option.value))}
          strategy={verticalListSortingStrategy}
        >
          <div className="select-options-editor__rows">
            {options.map((option, index) => (
              <SortableOptionRow
                key={option.id ?? `${option.value}_${index}`}
                option={option}
                multiple={multiple}
                isDefault={selectedDefaults.includes(option.value)}
                enableColors={enableColors}
                onFocus={() => undefined}
                onChange={(patch) => updateOption(index, patch)}
                onDefaultChange={() => toggleDefault(option)}
                onRemove={() => removeOption(index)}
                onAddAfter={() => addAfter(index + 1)}
                onPaste={(text) => {
                  const parsed = parseBulkSelectOptions(text);
                  if (!parsed.length) return;
                  const next = [...options];
                  next.splice(index, 1, ...parsed);
                  commit(next);
                }}
                onToggleHidden={() => toggleHidden(index)}
                onColorChange={(color) => updateOption(index, { color })}
              />
            ))}
          </div>
        </SortableContext>
      </DndContext>

      <Button
        block
        className="select-options-editor__add-button"
        icon={<PlusOutlined />}
        onClick={() => addAfter(options.length)}
      >
        添加选项
      </Button>

      <div className="select-options-editor__footer-actions">
        <button type="button" onClick={() => setBulkOpen(true)}>
          批量添加
        </button>
        <span aria-hidden="true">|</span>
        <button
          type="button"
          disabled={options.some((option) => option.isOther)}
          onClick={addOther}
        >
          添加其他项
        </button>
        <span aria-hidden="true">|</span>
        <button
          type="button"
          className={enableColors ? 'is-enabled' : ''}
          onClick={() => {
            const nextEnabled = !enableColors;
            const nextOptions = nextEnabled
              ? options.map((option) => ({
                  ...option,
                  color: option.color ?? SELECT_OPTION_COLORS[0].value,
                }))
              : options;
            onEnableColorsChange(nextEnabled, nextOptions);
          }}
        >
          启用选项颜色
        </button>
        <Tooltip title="开启后可为每个选项设置颜色">
          <QuestionCircleOutlined aria-label="选项颜色说明" />
        </Tooltip>
      </div>
      <Typography.Text type="secondary" className="select-options-editor__hint">
        回车自动生成选项，支持粘贴多行文本
      </Typography.Text>

      <Modal
        title="批量添加选项"
        open={bulkOpen}
        onCancel={() => setBulkOpen(false)}
        footer={[
          <Button key="cancel" onClick={() => setBulkOpen(false)}>取消</Button>,
          <Button key="append" disabled={!parseBulkSelectOptions(bulkText).length} onClick={() => applyBulk('append')}>追加</Button>,
          <Button key="replace" type="primary" disabled={!parseBulkSelectOptions(bulkText).length} onClick={() => applyBulk('replace')}>覆盖</Button>,
        ]}
      >
        <Space direction="vertical" style={{ width: '100%' }} size={10}>
          <Input.TextArea
            rows={8}
            value={bulkText}
            placeholder={'北京\n上海\n广州，深圳\n苏州,Hangzhou\n\n或：bj|北京\n或从 Excel 复制两列'}
            onChange={(event) => setBulkText(event.target.value)}
          />
          <Typography.Text type="secondary">
            支持每行一个选项、value|label 或 Excel 两列复制；逗号作为选项正文保留。
          </Typography.Text>
        </Space>
      </Modal>
    </div>
  );
}
