import {
  DndContext,
  DragOverlay,
  type DragEndEvent,
  type DragStartEvent,
  useDraggable,
  useDroppable,
} from '@dnd-kit/core';
import { App, Button, Space, theme } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useParams, useNavigate } from '@umijs/max';
import { useMutation } from '@tanstack/react-query';
import { request } from '@umijs/max';
import {
  DesignerFieldPreview,
  FormRenderer,
} from '../../../components/FormRenderer/FormRenderer';
import {
  paletteEntries,
  formRegistry,
} from '../../../registry/formRegistry';
import type { SchemaNode } from '../../../registry/types';
import { useFormDesignerStore } from './useFormDesignerStore';
import { Inspector } from './Inspector';
import './form-designer.less';

type PaletteEntry = (typeof paletteEntries)[number];

type ActiveDrag =
  | { source: 'palette'; entry: PaletteEntry }
  | {
      source: 'canvas';
      nodeId: string;
      entry: PaletteEntry;
      node: SchemaNode;
    };

type DropIndicatorState = {
  index: number;
  top: number;
  height: number;
};

type CanvasDragSnapshot = {
  activeNodeId: string;
  activeHeight: number;
  items: Array<{
    id: string;
    top: number;
    bottom: number;
    height: number;
  }>;
};

const FALLBACK_FIELD_GAP = 16;
const AUTO_SCROLL_EDGE = 72;
const AUTO_SCROLL_MAX_SPEED = 18;

const iconGlyphs: Record<string, string> = {
  text: 'T',
  textarea: 'P',
  number: '#',
  money: '$',
  date: 'D',
  date_range: 'R',
  select: 'S',
  multi_select: 'M',
  user_picker: 'U',
  dept_picker: 'O',
  file_upload: 'F',
  span_layout: 'L',
  table_list: 'G',
};

function FieldTypeIcon({ entry }: { entry: PaletteEntry }) {
  return (
    <span className="form-designer__field-icon" aria-hidden="true">
      {iconGlyphs[entry.type] ?? entry.icon?.slice(0, 1).toUpperCase() ?? '?'}
    </span>
  );
}

function PaletteCard({
  entry,
  onAdd,
}: {
  entry: PaletteEntry;
  onAdd(): void;
}) {
  const { attributes, isDragging, listeners, setNodeRef } = useDraggable({
    id: entry.type,
    data: { source: 'palette', entry },
  });
  return (
    <div
      ref={setNodeRef}
      {...attributes}
      {...listeners}
      className={[
        'form-designer__palette-card',
        isDragging ? 'form-designer__palette-card--dragging' : '',
      ]
        .filter(Boolean)
        .join(' ')}
      onDoubleClick={onAdd}
    >
      <FieldTypeIcon entry={entry} />
      <span className="form-designer__palette-card-main">
        <span className="form-designer__palette-card-label">{entry.label}</span>
        <span className="form-designer__palette-card-type">{entry.type}</span>
      </span>
    </div>
  );
}

function createPreviewNode(entry: PaletteEntry): SchemaNode {
  return {
    id: `preview_${entry.type}`,
    type: entry.type,
    label: entry.label,
    props: {
      ...entry.defaultProps,
      showTitle: true,
      showDescription: true,
    },
  };
}

function DragPreview({ drag }: { drag: ActiveDrag }) {
  const node =
    drag.source === 'canvas' ? drag.node : createPreviewNode(drag.entry);
  const ft = formRegistry[node.type];
  if (!ft) return null;
  const renderNode = {
    ...node,
    label: '',
    props: {
      ...node.props,
      required: false,
    },
  };
  return (
    <DesignerFieldPreview
      node={node}
    >
      <ft.Component node={renderNode} mode="designer-preview" value={undefined} />
    </DesignerFieldPreview>
  );
}

function captureCanvasDragSnapshot(nodeId: string): CanvasDragSnapshot | null {
  const allFieldEls = Array.from(
    document.querySelectorAll<HTMLElement>('[data-designer-field-id]'),
  );
  const activeEl = allFieldEls.find(
    (el) => el.dataset.designerFieldId === nodeId,
  );
  const canvas = activeEl?.closest<HTMLElement>('.form-designer__canvas');
  if (!canvas) return null;

  const canvasRect = canvas.getBoundingClientRect();
  const items = Array.from(
    canvas.querySelectorAll<HTMLElement>('[data-designer-field-id]'),
  ).map((el) => {
    const rect = el.getBoundingClientRect();
    const top = rect.top - canvasRect.top + canvas.scrollTop;
    return {
      id: el.dataset.designerFieldId ?? '',
      top,
      bottom: top + rect.height,
      height: rect.height,
    };
  });
  const activeItem = items.find((item) => item.id === nodeId);

  return {
    activeNodeId: nodeId,
    activeHeight: activeItem?.height ?? activeEl?.offsetHeight ?? 172,
    items,
  };
}

function CanvasDrop({
  activeCanvasNodeId,
  canvasDragSnapshot,
  dropIndicator,
  isDragActive,
  onDropIndicatorChange,
  recentlyDroppedId,
  onDropAnimationEnd,
}: {
  activeCanvasNodeId: string | null;
  canvasDragSnapshot: CanvasDragSnapshot | null;
  dropIndicator: DropIndicatorState | null;
  isDragActive: boolean;
  onDropIndicatorChange(next: DropIndicatorState | null): void;
  recentlyDroppedId: string | null;
  onDropAnimationEnd(id: string): void;
}) {
  const { isOver, setNodeRef } = useDroppable({ id: 'canvas' });
  const canvasRef = useRef<HTMLDivElement | null>(null);
  const lastPointerRef = useRef<{ clientX: number; clientY: number } | null>(
    null,
  );
  const dropIndicatorRef = useRef<DropIndicatorState | null>(null);
  const schema = useFormDesignerStore((s) => s.schema);
  const selectedId = useFormDesignerStore((s) => s.selectedId);
  const select = useFormDesignerStore((s) => s.select);
  const updateNode = useFormDesignerStore((s) => s.updateNode);
  const setCanvasRef = (node: HTMLDivElement | null) => {
    canvasRef.current = node;
    setNodeRef(node);
  };

  useEffect(() => {
    dropIndicatorRef.current = dropIndicator;
  }, [dropIndicator]);

  const setNextDropIndicator = useCallback(
    (next: DropIndicatorState | null) => {
      const current = dropIndicatorRef.current;
      const isSame =
        current?.index === next?.index &&
        Math.abs((current?.top ?? -1) - (next?.top ?? -1)) < 0.5 &&
        Math.abs((current?.height ?? -1) - (next?.height ?? -1)) < 0.5;
      if (isSame) return;

      dropIndicatorRef.current = next;
      onDropIndicatorChange(next);
    },
    [onDropIndicatorChange],
  );

  const updateIndicator = useCallback((clientX: number, clientY: number) => {
    const canvas = canvasRef.current;
    if (!canvas || !isDragActive) return;
    const canvasRect = canvas.getBoundingClientRect();
    const isInsideCanvas =
      clientX >= canvasRect.left &&
      clientX <= canvasRect.right &&
      clientY >= canvasRect.top &&
      clientY <= canvasRect.bottom;
    if (!isInsideCanvas) {
      setNextDropIndicator(null);
      return;
    }

    if (
      activeCanvasNodeId &&
      canvasDragSnapshot?.activeNodeId === activeCanvasNodeId
    ) {
      const pointerY = clientY - canvasRect.top + canvas.scrollTop;
      const fieldItems = canvasDragSnapshot.items.filter(
        (item) => item.id !== activeCanvasNodeId,
      );
      const placeholderHeight =
        canvasDragSnapshot.activeHeight || FALLBACK_FIELD_GAP * 10;

      if (fieldItems.length === 0) {
        setNextDropIndicator({
          index: 0,
          top:
            canvas.scrollTop +
            Math.max(24, canvas.clientHeight / 2 - placeholderHeight / 2),
          height: placeholderHeight,
        });
        return;
      }

      let index = fieldItems.length;
      for (let i = 0; i < fieldItems.length; i += 1) {
        const midpoint = fieldItems[i].top + fieldItems[i].height / 2;
        if (pointerY < midpoint) {
          index = i;
          break;
        }
      }

      let centerTop: number;
      if (index === 0) {
        centerTop = fieldItems[0].top - FALLBACK_FIELD_GAP / 2;
      } else if (index >= fieldItems.length) {
        centerTop =
          fieldItems[fieldItems.length - 1].bottom + FALLBACK_FIELD_GAP / 2;
      } else {
        centerTop = (fieldItems[index - 1].bottom + fieldItems[index].top) / 2;
      }

      setNextDropIndicator({
        index,
        top: centerTop - placeholderHeight / 2,
        height: placeholderHeight,
      });
      return;
    }

    const allFieldEls = Array.from(
      canvas.querySelectorAll<HTMLElement>('[data-designer-field-id]'),
    );
    const fieldEls = allFieldEls.filter(
      (el) => el.dataset.designerFieldId !== activeCanvasNodeId,
    );
    const activeEl = activeCanvasNodeId
      ? allFieldEls.find(
          (el) => el.dataset.designerFieldId === activeCanvasNodeId,
        )
      : null;
    const activeLayoutHeight = activeEl?.offsetHeight;
    const placeholderHeight = activeLayoutHeight ?? 172;

    if (fieldEls.length === 0) {
      setNextDropIndicator({
        index: 0,
        top:
          canvas.scrollTop +
          Math.max(24, canvas.clientHeight / 2 - placeholderHeight / 2),
        height: placeholderHeight,
      });
      return;
    }

    const rects = fieldEls.map((el) => el.getBoundingClientRect());
    let index = rects.length;
    for (let i = 0; i < rects.length; i += 1) {
      const midpoint = rects[i].top + rects[i].height / 2;
      if (clientY < midpoint) {
        index = i;
        break;
      }
    }

    const layoutTops = fieldEls.map((el) => el.offsetTop);
    const layoutBottoms = fieldEls.map((el) => el.offsetTop + el.offsetHeight);
    const layoutGaps = layoutTops
      .slice(1)
      .map((top, gapIndex) =>
        Math.max(0, top - layoutBottoms[gapIndex]),
      );
    const averageGap =
      layoutGaps.length > 0
        ? layoutGaps.reduce((sum, gap) => sum + gap, 0) / layoutGaps.length
        : FALLBACK_FIELD_GAP;
    const edgeGap = Math.max(FALLBACK_FIELD_GAP, averageGap);
    let centerTop: number;
    if (index === 0) {
      centerTop = layoutTops[0] - edgeGap / 2;
    } else if (index >= rects.length) {
      centerTop = layoutBottoms[layoutBottoms.length - 1] + edgeGap / 2;
    } else {
      centerTop = (layoutBottoms[index - 1] + layoutTops[index]) / 2;
    }
    setNextDropIndicator({
      index,
      top: centerTop - placeholderHeight / 2,
      height: placeholderHeight,
    });
  }, [
    activeCanvasNodeId,
    canvasDragSnapshot,
    isDragActive,
    setNextDropIndicator,
  ]);

  useEffect(() => {
    if (!isDragActive) return undefined;
    const handlePointerMove = (event: PointerEvent) => {
      lastPointerRef.current = {
        clientX: event.clientX,
        clientY: event.clientY,
      };
      updateIndicator(event.clientX, event.clientY);
    };
    const handlePointerCancel = () => {
      lastPointerRef.current = null;
      setNextDropIndicator(null);
    };
    window.addEventListener('pointermove', handlePointerMove, { passive: true });
    window.addEventListener('pointercancel', handlePointerCancel, {
      passive: true,
    });
    return () => {
      window.removeEventListener('pointermove', handlePointerMove);
      window.removeEventListener('pointercancel', handlePointerCancel);
      lastPointerRef.current = null;
      setNextDropIndicator(null);
    };
  }, [isDragActive, setNextDropIndicator, updateIndicator]);

  useEffect(() => {
    if (!isDragActive) return undefined;
    let frameId = 0;

    const tick = () => {
      const canvas = canvasRef.current;
      const pointer = lastPointerRef.current;
      if (canvas && pointer) {
        const rect = canvas.getBoundingClientRect();
        const isHorizontallyInside =
          pointer.clientX >= rect.left && pointer.clientX <= rect.right;
        let scrollDelta = 0;

        if (isHorizontallyInside) {
          const topDistance = pointer.clientY - rect.top;
          const bottomDistance = rect.bottom - pointer.clientY;

          if (topDistance >= 0 && topDistance < AUTO_SCROLL_EDGE) {
            scrollDelta =
              -AUTO_SCROLL_MAX_SPEED *
              ((AUTO_SCROLL_EDGE - topDistance) / AUTO_SCROLL_EDGE);
          } else if (
            bottomDistance >= 0 &&
            bottomDistance < AUTO_SCROLL_EDGE
          ) {
            scrollDelta =
              AUTO_SCROLL_MAX_SPEED *
              ((AUTO_SCROLL_EDGE - bottomDistance) / AUTO_SCROLL_EDGE);
          }
        }

        if (Math.abs(scrollDelta) > 0.2) {
          const previousScrollTop = canvas.scrollTop;
          canvas.scrollTop += scrollDelta;
          if (canvas.scrollTop !== previousScrollTop) {
            updateIndicator(pointer.clientX, pointer.clientY);
          }
        }
      }

      frameId = requestAnimationFrame(tick);
    };

    frameId = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frameId);
  }, [isDragActive, updateIndicator]);

  useEffect(() => {
    if (!isDragActive) return undefined;

    const recalculate = () => {
      const pointer = lastPointerRef.current;
      if (pointer) updateIndicator(pointer.clientX, pointer.clientY);
    };
    const observer = new ResizeObserver(recalculate);
    const canvas = canvasRef.current;

    if (canvas) observer.observe(canvas);
    window.addEventListener('resize', recalculate);
    return () => {
      observer.disconnect();
      window.removeEventListener('resize', recalculate);
    };
  }, [isDragActive, updateIndicator]);

  useEffect(() => {
    document.querySelectorAll('[data-field-id]').forEach((el) => {
      (el as HTMLElement).style.outline =
        (el as HTMLElement).getAttribute('data-field-id') === selectedId
          ? '2px solid #1677ff'
          : '';
    });
  }, [selectedId]);
  return (
    <div
      ref={setCanvasRef}
      data-canvas
      className={[
        'form-designer__canvas',
        isDragActive ? 'form-designer__canvas--drag-active' : '',
        isOver ? 'form-designer__canvas--over' : '',
      ]
        .filter(Boolean)
        .join(' ')}
      onPointerLeave={() => {
        if (isDragActive) {
          lastPointerRef.current = null;
          setNextDropIndicator(null);
        }
      }}
      onPointerMove={(e) => {
        lastPointerRef.current = {
          clientX: e.clientX,
          clientY: e.clientY,
        };
        updateIndicator(e.clientX, e.clientY);
      }}
      onClick={(e) => {
        const id = (e.target as HTMLElement)
          .closest('[data-field-id]')
          ?.getAttribute('data-field-id');
        if (id) select(id);
      }}
    >
      <FormRenderer
        schema={schema}
        mode="designer-preview"
        value={{}}
        recentlyDroppedId={recentlyDroppedId}
        onDropAnimationEnd={onDropAnimationEnd}
        activeCanvasNodeId={activeCanvasNodeId}
        dropIndex={dropIndicator?.index ?? null}
        onDesignerNodeChange={(node) => updateNode(node.id, node)}
      />
      {(schema.length === 0 || (activeCanvasNodeId && schema.length === 1)) && (
        <div className="form-designer__empty">拖入第一个字段</div>
      )}
      {dropIndicator && !activeCanvasNodeId && (
        <div
          className="form-designer__drop-placeholder"
          style={{ height: dropIndicator.height, top: dropIndicator.top }}
        />
      )}
    </div>
  );
}

type FormDefinition = {
  id: number;
  code: string;
  name: string;
  description?: string;
  schema?: any[] | string;
  settings?: Record<string, any> | string;
};

function parseJsonValue<T>(value: T | string | undefined, fallback: T): T {
  if (typeof value !== 'string') return value ?? fallback;
  try {
    return JSON.parse(value);
  } catch {
    return fallback;
  }
}

export function FormDesignerSurface({
  formId,
  embedded = false,
  onSaved,
}: {
  formId?: string | number;
  embedded?: boolean;
  onSaved?: (form: FormDefinition) => void;
}) {
  const routeParams = useParams();
  const id = String(formId ?? routeParams.id ?? 'new');
  const navigate = useNavigate();
  const { message } = App.useApp();
  const { token } = theme.useToken();
  const { schema, loadSchema, addNode, insertNode, moveNode, undo, redo } =
    useFormDesignerStore();
  const [definition, setDefinition] = useState<FormDefinition | null>(null);
  const [activeDrag, setActiveDrag] = useState<ActiveDrag | null>(null);
  const [canvasDragSnapshot, setCanvasDragSnapshot] =
    useState<CanvasDragSnapshot | null>(null);
  const [dropIndicator, setDropIndicator] =
    useState<DropIndicatorState | null>(null);
  const [recentlyDroppedId, setRecentlyDroppedId] = useState<string | null>(null);
  const activeCanvasNodeId =
    activeDrag?.source === 'canvas' ? activeDrag.nodeId : null;

  // Load existing definition when id is provided (not 'new').
  useEffect(() => {
    if (!id || id === 'new') return;
    (async () => {
      try {
        const fd = await request<FormDefinition>(`/api/forms/definitions/${id}`);
        setDefinition(fd);
        loadSchema(parseJsonValue(fd.schema, []));
      } catch (_error) {
        message.error('加载表单失败');
      }
    })();
  }, [id, loadSchema, message]);

  const save = useMutation({
    mutationFn: () =>
      request('/api/forms/definitions', {
        method: 'POST',
        data: {
          id: id === 'new' ? null : Number(id),
          code: definition?.code ?? `form_${Date.now()}`,
          name: definition?.name ?? '未命名表单',
          description: definition?.description ?? '',
          schema,
          settings: parseJsonValue(definition?.settings, {}),
        },
      }),
    onSuccess: (res: any) => {
      setDefinition(res);
      onSaved?.(res);
      if (id === 'new' && !embedded) navigate(`/designer/form/${res.id}`);
      message.success('已保存草稿');
    },
  });

  const publish = useMutation({
    mutationFn: () =>
      request(`/api/forms/definitions/${id}/publish`, { method: 'POST' }),
    onSuccess: () => message.success('已发布'),
  });

  const designerVars = {
    '--form-designer-bg': token.colorBgLayout,
    '--form-designer-surface': token.colorBgContainer,
    '--form-designer-border': token.colorBorderSecondary,
    '--form-designer-border-strong': token.colorBorder,
    '--form-designer-text': token.colorText,
    '--form-designer-text-secondary': token.colorTextSecondary,
    '--form-designer-primary': token.colorPrimary,
    '--form-designer-primary-bg': token.colorPrimaryBg,
    '--form-designer-danger': token.colorError,
    '--form-designer-shadow': token.boxShadowSecondary,
    height: embedded ? 'calc(100vh - 260px)' : '100vh',
    minHeight: embedded ? 560 : undefined,
  } as React.CSSProperties;

  return (
    <DndContext
      onDragStart={(e: DragStartEvent) => {
        const data = e.active.data.current as
          | { source?: string; entry?: PaletteEntry; node?: any }
          | undefined;
        if (data?.source === 'palette' && data.entry) {
          setCanvasDragSnapshot(null);
          setActiveDrag({ source: 'palette', entry: data.entry });
          return;
        }
        if (data?.source === 'canvas' && data.node) {
          const ft = formRegistry[data.node.type];
          if (!ft) return;
          setCanvasDragSnapshot(captureCanvasDragSnapshot(data.node.id));
          setActiveDrag({
            source: 'canvas',
            nodeId: data.node.id,
            node: data.node,
            entry: {
              type: data.node.type,
              label: data.node.label ?? ft.label,
              icon: ft.icon,
              defaultProps: ft.defaultProps,
            },
          });
        }
      }}
      onDragCancel={() => {
        setActiveDrag(null);
        setCanvasDragSnapshot(null);
        setDropIndicator(null);
      }}
      onDragEnd={(e: DragEndEvent) => {
        const currentDrag = activeDrag;
        const currentDrop = dropIndicator;
        const isCanvasDrop = !!currentDrop || e.over?.id === 'canvas';
        setActiveDrag(null);
        setCanvasDragSnapshot(null);
        setDropIndicator(null);
        if (isCanvasDrop && currentDrag) {
          if (currentDrag.source === 'canvas') {
            if (currentDrop) {
              moveNode(currentDrag.nodeId, currentDrop.index);
            }
            return;
          }
          const t = currentDrag.entry.type;
          const newId = currentDrop
            ? insertNode(null, t, formRegistry[t].defaultProps, currentDrop.index)
            : addNode(null, t, formRegistry[t].defaultProps);
          setRecentlyDroppedId(newId);
        }
      }}
    >
      <div className="form-designer-shell" style={designerVars}>
        <div className="form-designer">
          <aside className="form-designer__palette">
            <h4>字段</h4>
            {paletteEntries
              .filter((e) => e.type !== 'description')
              .map((e) => (
                <PaletteCard
                  key={e.type}
                  entry={e}
                  onAdd={() => addNode(null, e.type, e.defaultProps)}
                />
              ))}
          </aside>
          <main className="form-designer__workspace">
            <Space className="form-designer__toolbar">
              <Button onClick={undo}>撤销</Button>
              <Button onClick={redo}>重做</Button>
              <Button
                type="primary"
                onClick={() => save.mutate()}
                loading={save.isPending}
              >
                保存草稿
              </Button>
              {!embedded && (
                <Button
                  onClick={() => publish.mutate()}
                  disabled={id === 'new'}
                >
                  发布
                </Button>
              )}
            </Space>
            <CanvasDrop
              activeCanvasNodeId={activeCanvasNodeId}
              canvasDragSnapshot={canvasDragSnapshot}
              dropIndicator={dropIndicator}
              isDragActive={!!activeDrag}
              onDropIndicatorChange={setDropIndicator}
              recentlyDroppedId={recentlyDroppedId}
              onDropAnimationEnd={(nodeId) => {
                if (nodeId === recentlyDroppedId) {
                  setRecentlyDroppedId(null);
                }
              }}
            />
          </main>
          <aside className="form-designer__inspector">
            <Inspector />
          </aside>
        </div>
        <DragOverlay dropAnimation={{ duration: 220, easing: 'ease' }}>
          {activeDrag ? (
            <div className="form-designer__drag-preview">
              <DragPreview drag={activeDrag} />
            </div>
          ) : null}
        </DragOverlay>
      </div>
    </DndContext>
  );
}

export default function FormDesigner() {
  return <FormDesignerSurface />;
}
