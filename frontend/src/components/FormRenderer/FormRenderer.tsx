import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
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
  sortableIds?: string[];
  placeholderId?: string | null;
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
  return !['span_layout', 'table_list', 'description'].includes(type);
}

function isFlatContainerNode(type: string) {
  return type === 'span_layout';
}

export function DesignerFieldPreview({
  children,
  node,
}: {
  children: React.ReactNode;
  node: SchemaNode;
}) {
  const fieldType = formRegistry[node.type];
  const fieldName = node.label || fieldType?.label || node.type;
  const questionDescription =
    node.props?.questionDescription || node.props?.description || '题干说明';
  const showTitle = node.props?.showTitle !== false;
  const showDescription = node.props?.showDescription !== false;

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

function SortablePlaceholder({ id }: { id: string }) {
  const { setNodeRef, transform, transition } = useSortable({ id });
  return (
    <div
      ref={setNodeRef}
      data-designer-placeholder-id={id}
      className="form-renderer__field form-renderer__field--designer form-renderer__placeholder"
      style={{ transform: CSS.Transform.toString(transform), transition }}
    >
      <div className="form-renderer__designer-card form-renderer__placeholder-card">
        松开放入此处
      </div>
    </div>
  );
}
function DesignerFieldFrame({
  children,
  node,
  onNodeChange,
  bare = false,
}: {
  children: React.ReactNode;
  node: SchemaNode;
  onNodeChange?(node: SchemaNode): void;
  bare?: boolean;
}) {
  const {
    attributes,
    isDragging,
    listeners,
    setNodeRef,
    transform,
    transition,
  } = useSortable({
    id: node.id,
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
  ]
    .filter(Boolean)
    .join(' ');

  if (bare) {
    return (
      <div
        ref={setNodeRef}
        data-designer-field-id={node.id}
        className={rootClassName}
        style={{ transform: CSS.Transform.toString(transform), transition }}
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
      style={{ transform: CSS.Transform.toString(transform), transition }}
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
  schema,
  mode,
  value,
  onChange,
  onDesignerNodeChange,
  sortableIds,
  placeholderId,
}: Props) {
  const isDesigner = mode === 'designer-preview';
  const listRef = useDesignerListFlip(isDesigner, [isDesigner, schema, sortableIds]);
  const renderIds =
    isDesigner && sortableIds ? sortableIds : schema.map((node) => node.id);
  return (
    <div ref={listRef} data-canvas={isDesigner ? 'true' : undefined}>
      {renderIds.map((id) => {
        if (isDesigner && placeholderId && id === placeholderId) {
          return <SortablePlaceholder key={id} id={id} />;
        }
        const node = schema.find((item) => item.id === id);
        if (!node) return null;
        const ft = formRegistry[node.type];
        if (!ft) return null;
        if (!shouldRenderNode(node, mode, value ?? {})) return null;
        const flatContainer = isFlatContainerNode(node.type);
        const nodeValue = flatContainer
          ? value ?? {}
          : value?.[node.id] ?? node.props?.defaultValue;
        const renderNode = isDesigner
          ? {
              ...node,
              label: '',
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
              onNodeChange={onDesignerNodeChange}
              bare={false}
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