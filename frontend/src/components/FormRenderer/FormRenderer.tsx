import { useDraggable } from '@dnd-kit/core';
import { EyeInvisibleOutlined } from '@ant-design/icons';
import { Checkbox, type CheckboxProps } from 'antd';
import { useLayoutEffect, useRef } from 'react';
import { formRegistry } from '../../registry/formRegistry';
import type { FieldMode, SchemaNode } from '../../registry/types';
import './FormRenderer.less';

type Props = {
  schema: SchemaNode[];
  mode: FieldMode;
  value?: any;
  onChange?(v: any): void;
  recentlyDroppedId?: string | null;
  onDropAnimationEnd?(id: string): void;
  activeCanvasNodeId?: string | null;
  dropIndex?: number | null;
  onDesignerNodeChange?(node: SchemaNode): void;
};

function useDesignerListFlip(isEnabled: boolean, deps: unknown[]) {
  const listRef = useRef<HTMLDivElement | null>(null);
  const previousTopsRef = useRef<Map<string, number>>(new Map());

  useLayoutEffect(() => {
    const list = listRef.current;
    if (!list || !isEnabled) return;

    const fieldEls = Array.from(
      list.querySelectorAll<HTMLElement>('[data-designer-field-id]'),
    );
    const nextTops = new Map(
      fieldEls.map((el) => [
        el.dataset.designerFieldId ?? '',
        el.offsetTop,
      ]),
    );

    fieldEls.forEach((el) => {
      const id = el.dataset.designerFieldId;
      if (!id) return;
      const previousTop = previousTopsRef.current.get(id);
      const nextTop = nextTops.get(id);
      if (previousTop == null || nextTop == null) return;

      const deltaY = previousTop - nextTop;
      if (Math.abs(deltaY) < 1) return;

      el.style.transition = 'none';
      el.style.setProperty('--form-renderer-field-flip-y', `${deltaY}px`);
      requestAnimationFrame(() => {
        el.style.transition = '';
        el.style.setProperty('--form-renderer-field-flip-y', '0px');
      });
    });

    previousTopsRef.current = nextTops;
  }, deps);

  return listRef;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}

function getPreviewSchema({
  activeCanvasNodeId,
  dropIndex,
  schema,
}: {
  activeCanvasNodeId?: string | null;
  dropIndex?: number | null;
  schema: SchemaNode[];
}) {
  if (!activeCanvasNodeId) return schema;

  const activeNode = schema.find((node) => node.id === activeCanvasNodeId);
  if (!activeNode) return schema;

  const withoutActiveNode = schema.filter(
    (node) => node.id !== activeCanvasNodeId,
  );
  if (dropIndex == null) return withoutActiveNode;

  const targetIndex = clamp(dropIndex, 0, withoutActiveNode.length);
  return [
    ...withoutActiveNode.slice(0, targetIndex),
    activeNode,
    ...withoutActiveNode.slice(targetIndex),
  ];
}

function isEmptyValue(value: any) {
  return (
    value == null ||
    value === '' ||
    (Array.isArray(value) && value.length === 0)
  );
}

function matchesDisplayCondition(
  condition: Record<string, any> | undefined,
  formValue: Record<string, any>,
) {
  if (!condition?.fieldId) return true;
  const sourceValue = formValue?.[condition.fieldId];
  const targetValue = condition.value;

  switch (condition.operator ?? 'eq') {
    case 'ne':
      return String(sourceValue ?? '') !== String(targetValue ?? '');
    case 'contains':
      return Array.isArray(sourceValue)
        ? sourceValue.map(String).includes(String(targetValue ?? ''))
        : String(sourceValue ?? '').includes(String(targetValue ?? ''));
    case 'empty':
      return isEmptyValue(sourceValue);
    case 'notEmpty':
      return !isEmptyValue(sourceValue);
    default:
      return String(sourceValue ?? '') === String(targetValue ?? '');
  }
}

function shouldRenderNode(
  node: SchemaNode,
  mode: FieldMode,
  formValue: Record<string, any>,
) {
  if (mode === 'designer-preview') return true;
  if (node.props?.hidden) return false;
  return matchesDisplayCondition(node.props?.displayCondition, formValue);
}

function canRequireNode(type: string) {
  return !['section', 'span_layout', 'table_list', 'description'].includes(type);
}

function isFlatContainerNode(type: string) {
  return type === 'section' || type === 'span_layout';
}

function usesOwnDesignerChrome(type: string) {
  return type === 'section';
}

export function DesignerFieldPreview({
  children,
  node,
}: {
  children: React.ReactNode;
  node: SchemaNode;
}) {
  const ownsDesignerChrome = usesOwnDesignerChrome(node.type);
  const fieldType = formRegistry[node.type];
  const fieldName = node.label || fieldType?.label || node.type;
  const questionDescription =
    node.props?.questionDescription || node.props?.description || '题干说明';
  const showTitle = node.props?.showTitle !== false;
  const showDescription = node.props?.showDescription !== false;

  if (ownsDesignerChrome) {
    return (
      <div className="form-renderer__drag-card form-renderer__drag-card--bare">
        {children}
      </div>
    );
  }

  return (
    <div className="form-renderer__drag-card">
      {showTitle && (
        <div className="form-renderer__designer-title">{fieldName}</div>
      )}
      {showDescription && (
        <div className="form-renderer__designer-description">
          {questionDescription}
        </div>
      )}
      <div className="form-renderer__designer-control">{children}</div>
    </div>
  );
}

function DesignerFieldFrame({
  children,
  node,
  recentlyDroppedId,
  onDropAnimationEnd,
  onNodeChange,
  bare = false,
}: {
  children: React.ReactNode;
  node: SchemaNode;
  recentlyDroppedId?: string | null;
  onDropAnimationEnd?(id: string): void;
  onNodeChange?(node: SchemaNode): void;
  bare?: boolean;
}) {
  const { attributes, isDragging, listeners, setNodeRef } = useDraggable({
    id: `field:${node.id}`,
    data: { source: 'canvas', node },
  });

  const fieldType = formRegistry[node.type];
  const fieldName = node.label || fieldType?.label || node.type;
  const questionDescription =
    node.props?.questionDescription || node.props?.description || '题干说明';
  const showTitle = node.props?.showTitle !== false;
  const showDescription = node.props?.showDescription !== false;
  const hidden = !!node.props?.hidden;
  const canRequire = canRequireNode(node.type);
  const updateProp = (key: string, checked: boolean) => {
    onNodeChange?.({
      ...node,
      props: {
        ...node.props,
        [key]: checked,
      },
    });
  };
  const onCheckboxChange =
    (key: string): CheckboxProps['onChange'] =>
    (event) => {
      updateProp(key, event.target.checked);
    };
  const rootClassName = [
    'form-renderer__field',
    'form-renderer__field--designer',
    bare ? 'form-renderer__field--designer-bare' : '',
    isDragging ? 'form-renderer__field--dragging' : '',
    recentlyDroppedId === node.id ? 'form-renderer__field--drop-in' : '',
  ]
    .filter(Boolean)
    .join(' ');

  if (bare) {
    return (
      <div
        ref={setNodeRef}
        data-designer-field-id={node.id}
        className={rootClassName}
        onAnimationEnd={() => onDropAnimationEnd?.(node.id)}
      >
        <button
          type="button"
          className="form-renderer__designer-drag-handle form-renderer__designer-drag-handle--bare"
          aria-label="拖动字段"
          {...attributes}
          {...listeners}
        >
          <span aria-hidden="true">•••</span>
        </button>
        {hidden && (
          <EyeInvisibleOutlined className="form-renderer__designer-hidden-icon form-renderer__designer-hidden-icon--bare" />
        )}
        {children}
      </div>
    );
  }

  return (
    <div
      ref={setNodeRef}
      data-designer-field-id={node.id}
      className={rootClassName}
      onAnimationEnd={() => onDropAnimationEnd?.(node.id)}
    >
      <div className="form-renderer__designer-card">
        <button
          type="button"
          className="form-renderer__designer-drag-handle"
          aria-label="拖动字段"
          {...attributes}
          {...listeners}
        >
          <span aria-hidden="true">•••</span>
        </button>
        {hidden && (
          <EyeInvisibleOutlined className="form-renderer__designer-hidden-icon" />
        )}
        {showTitle && (
          <div className="form-renderer__designer-title">
            {node.props?.required && (
              <span className="form-renderer__designer-required">*</span>
            )}
            {fieldName}
          </div>
        )}
        {showDescription && (
          <div className="form-renderer__designer-description">
            {questionDescription}
          </div>
        )}
        <div className="form-renderer__designer-control">
          {children}
        </div>
        <div className="form-renderer__designer-options">
          {canRequire && (
            <div
              className="form-renderer__designer-option"
              onPointerDown={(event) => event.stopPropagation()}
            >
              <Checkbox
                checked={!!node.props?.required}
                onChange={onCheckboxChange('required')}
              >
                是否必填
              </Checkbox>
            </div>
          )}
          <div
            className="form-renderer__designer-option"
            onPointerDown={(event) => event.stopPropagation()}
          >
            <Checkbox
              checked={showDescription}
              onChange={onCheckboxChange('showDescription')}
            >
              题干说明
            </Checkbox>
          </div>
          <div
            className="form-renderer__designer-option"
            onPointerDown={(event) => event.stopPropagation()}
          >
            <Checkbox
              checked={showTitle}
              onChange={onCheckboxChange('showTitle')}
            >
              显示标题
            </Checkbox>
          </div>
          <div
            className="form-renderer__designer-option"
            onPointerDown={(event) => event.stopPropagation()}
          >
            <Checkbox checked={hidden} onChange={onCheckboxChange('hidden')}>
              隐藏组件
            </Checkbox>
          </div>
        </div>
      </div>
    </div>
  );
}

export function FormRenderer({
  activeCanvasNodeId,
  dropIndex,
  schema,
  mode,
  value,
  onChange,
  onDesignerNodeChange,
  recentlyDroppedId,
  onDropAnimationEnd,
}: Props) {
  const isDesigner = mode === 'designer-preview';
  const renderSchema = isDesigner
    ? getPreviewSchema({ activeCanvasNodeId, dropIndex, schema })
    : schema;
  const listRef = useDesignerListFlip(isDesigner, [
    isDesigner,
    schema,
    renderSchema,
    activeCanvasNodeId,
    dropIndex,
  ]);
  return (
    <div ref={listRef} data-canvas={isDesigner ? 'true' : undefined}>
      {renderSchema.map((node) => {
        const ft = formRegistry[node.type];
        if (!ft) return null;
        if (!shouldRenderNode(node, mode, value ?? {})) return null;
        const flatContainer = isFlatContainerNode(node.type);
        const nodeValue = flatContainer
          ? value ?? {}
          : value?.[node.id] ?? node.props?.defaultValue;
        const ownsDesignerChrome = usesOwnDesignerChrome(node.type);
        const renderNode = isDesigner
          ? {
              ...node,
              label: ownsDesignerChrome ? node.label : '',
              props: {
                ...node.props,
                required: false,
              },
            }
          : node;
        const field = (
          <ft.Component
            node={renderNode}
            mode={mode}
            value={nodeValue}
            onChange={(v: any) => {
              if (flatContainer) {
                onChange?.(v);
                return;
              }
              onChange?.({ ...(value ?? {}), [node.id]: v });
            }}
          />
        );
        if (isDesigner) {
          return (
            <DesignerFieldFrame
              key={node.id}
              node={node}
              recentlyDroppedId={recentlyDroppedId}
              onDropAnimationEnd={onDropAnimationEnd}
              onNodeChange={onDesignerNodeChange}
              bare={ownsDesignerChrome}
            >
              {field}
            </DesignerFieldFrame>
          );
        }
        return (
          <div
            key={node.id}
            className="form-renderer__field"
            style={{ margin: '8px 0' }}
          >
            {field}
          </div>
        );
      })}
    </div>
  );
}
