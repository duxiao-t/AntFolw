import {
  DndContext,
  DragOverlay,
  type DragEndEvent,
  type DragStartEvent,
  useDraggable,
  useDroppable,
} from '@dnd-kit/core';
import {
  AlignLeftOutlined,
  ApartmentOutlined,
  CalendarOutlined,
  CheckSquareOutlined,
  ColumnWidthOutlined,
  DollarOutlined,
  DownSquareOutlined,
  FieldNumberOutlined,
  FileTextOutlined,
  FontSizeOutlined,
  TableOutlined,
  UploadOutlined,
  UserOutlined,
} from '@ant-design/icons';
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
};


const FALLBACK_FIELD_GAP = 16;
const AUTO_SCROLL_EDGE = 72;
const AUTO_SCROLL_MAX_SPEED = 18;

const paletteIcons: Record<string, React.ReactNode> = {
  text: <FontSizeOutlined />,
  textarea: <AlignLeftOutlined />,
  number: <FieldNumberOutlined />,
  money: <DollarOutlined />,
  date: <CalendarOutlined />,
  date_range: <CalendarOutlined />,
  select: <DownSquareOutlined />,
  multi_select: <CheckSquareOutlined />,
  user_picker: <UserOutlined />,
  dept_picker: <ApartmentOutlined />,
  file_upload: <UploadOutlined />,
  span_layout: <ColumnWidthOutlined />,
  table_list: <TableOutlined />,
};

function FieldTypeIcon({ entry }: { entry: PaletteEntry }) {
  return (
    <span className="form-designer__field-icon" aria-hidden="true">
      {paletteIcons[entry.type] ?? <FileTextOutlined />}
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
      <span className="form-designer__palette-card-label" title={entry.label}>
        {entry.label}
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


function CanvasDrop({
  activeCanvasNodeId,
  dropIndicator,
  isDragActive,
  onDropIndicatorChange,
  recentlyDroppedId,
  onDropAnimationEnd,
}: {
  activeCanvasNodeId: string | null;
  dropIndicator: DropIndicatorState | null;
  isDragActive: boolean;
  onDropIndicatorChange(next: DropIndicatorState | null): void;
  recentlyDroppedId: string | null;
  onDropAnimationEnd(id: string): void;
}) {
  const { isOver, setNodeRef } = useDroppable({ id: 'canvas' });
  const canvasRef = useRef<HTMLDivElement | null>(null);
  const lastPointerRef = useRef<{ clientX: number; clientY: number } | null>(null);
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
        Math.abs((current?.top ?? -1) - (next?.top ?? -1)) < 0.5;
      if (isSame) return;
      dropIndicatorRef.current = next;
      onDropIndicatorChange(next);
    },
    [onDropIndicatorChange],
  );

  const recomputeIndicator = useCallback(() => {
    const canvas = canvasRef.current;
    const pointer = lastPointerRef.current;
    if (!canvas || !isDragActive || !pointer) {
      return;
    }
    const canvasRect = canvas.getBoundingClientRect();
    const isInsideCanvas =
      pointer.clientX >= canvasRect.left &&
      pointer.clientX <= canvasRect.right &&
      pointer.clientY >= canvasRect.top &&
      pointer.clientY <= canvasRect.bottom;
    if (!isInsideCanvas) {
      setNextDropIndicator(null);
      return;
    }

    const pointerY = pointer.clientY - canvasRect.top + canvas.scrollTop;
    const fieldEls = Array.from(
      canvas.querySelectorAll<HTMLElement>('[data-designer-field-id]'),
    ).filter((el) => el.dataset.designerFieldId !== activeCanvasNodeId);

    if (fieldEls.length === 0) {
      setNextDropIndicator({ index: 0, top: canvas.scrollTop + 8 });
      return;
    }

    const layoutTops = fieldEls.map((el) => el.offsetTop);
    const layoutBottoms = fieldEls.map((el) => el.offsetTop + el.offsetHeight);
    let index = fieldEls.length;
    for (let i = 0; i < fieldEls.length; i += 1) {
      if (pointerY < layoutTops[i] + (layoutBottoms[i] - layoutTops[i]) / 2) {
        index = i;
        break;
      }
    }

    let top: number;
    if (index === 0) {
      top = layoutTops[0] - FALLBACK_FIELD_GAP / 2;
    } else if (index >= fieldEls.length) {
      top = layoutBottoms[layoutBottoms.length - 1] + FALLBACK_FIELD_GAP / 2;
    } else {
      top = (layoutBottoms[index - 1] + layoutTops[index]) / 2;
    }
    setNextDropIndicator({ index, top: top - 2 });
  }, [activeCanvasNodeId, isDragActive, setNextDropIndicator]);

  useEffect(() => {
    if (!isDragActive) return undefined;
    const handlePointerMove = (event: PointerEvent) => {
      lastPointerRef.current = { clientX: event.clientX, clientY: event.clientY };
    };
    const handlePointerCancel = () => {
      lastPointerRef.current = null;
      setNextDropIndicator(null);
    };
    window.addEventListener('pointermove', handlePointerMove, { passive: true });
    window.addEventListener('pointercancel', handlePointerCancel, { passive: true });
    return () => {
      window.removeEventListener('pointermove', handlePointerMove);
      window.removeEventListener('pointercancel', handlePointerCancel);
      lastPointerRef.current = null;
      setNextDropIndicator(null);
    };
  }, [isDragActive, setNextDropIndicator]);

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
          } else if (bottomDistance >= 0 && bottomDistance < AUTO_SCROLL_EDGE) {
            scrollDelta =
              AUTO_SCROLL_MAX_SPEED *
              ((AUTO_SCROLL_EDGE - bottomDistance) / AUTO_SCROLL_EDGE);
          }
        }
        if (Math.abs(scrollDelta) > 0.2) {
          const previousScrollTop = canvas.scrollTop;
          canvas.scrollTop += scrollDelta;
          if (canvas.scrollTop !== previousScrollTop) {
            recomputeIndicator();
          }
        } else {
          recomputeIndicator();
        }
      }
      frameId = requestAnimationFrame(tick);
    };
    frameId = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frameId);
  }, [isDragActive, recomputeIndicator]);

  useEffect(() => {
    if (!isDragActive) return undefined;
    const recalculate = () => {
      if (lastPointerRef.current) recomputeIndicator();
    };
    const observer = new ResizeObserver(recalculate);
    const canvas = canvasRef.current;
    if (canvas) observer.observe(canvas);
    window.addEventListener('resize', recalculate);
    return () => {
      observer.disconnect();
      window.removeEventListener('resize', recalculate);
    };
  }, [isDragActive, recomputeIndicator]);

  useEffect(() => {
    document.querySelectorAll('[data-designer-field-id]').forEach((el) => {
      (el as HTMLElement).style.outline =
        (el as HTMLElement).getAttribute('data-designer-field-id') === selectedId
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
        lastPointerRef.current = { clientX: e.clientX, clientY: e.clientY };
      }}
      onClick={(e) => {
        const id = (e.target as HTMLElement)
          .closest('[data-designer-field-id]')
          ?.getAttribute('data-designer-field-id');
        if (id) select(id);
      }}
    >
      <FormRenderer
        schema={schema}
        mode="designer-preview"
        value={{}}
        recentlyDroppedId={recentlyDroppedId}
        onDropAnimationEnd={onDropAnimationEnd}
        onDesignerNodeChange={(node) => updateNode(node.id, node)}
      />
      {(schema.length === 0 || (activeCanvasNodeId && schema.length === 1)) && (
        <div className="form-designer__empty">拖入第一个字段</div>
      )}
      {dropIndicator && (
        <div
          className="form-designer__insert-line"
          style={{ top: dropIndicator.top }}
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
  const { schema, loadSchema, resetSchema, addNode, insertNode, moveNode, undo } =
    useFormDesignerStore();
  const [definition, setDefinition] = useState<FormDefinition | null>(null);
  const [activeDrag, setActiveDrag] = useState<ActiveDrag | null>(null);
  const [dropIndicator, setDropIndicator] =
    useState<DropIndicatorState | null>(null);
  const [recentlyDroppedId, setRecentlyDroppedId] = useState<string | null>(null);
  const activeCanvasNodeId =
    activeDrag?.source === 'canvas' ? activeDrag.nodeId : null;
  const addPaletteEntry = useCallback(
    (entry: PaletteEntry) => {
      addNode(null, entry.type, entry.defaultProps);
    },
    [addNode],
  );

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
          setActiveDrag({ source: 'palette', entry: data.entry });
          return;
        }
        if (data?.source === 'canvas' && data.node) {
          const ft = formRegistry[data.node.type];
          if (!ft) return;
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
        setDropIndicator(null);
      }}
      onDragEnd={(e: DragEndEvent) => {
        const currentDrag = activeDrag;
        const currentDrop = dropIndicator;
        const isCanvasDrop = !!currentDrop || e.over?.id === 'canvas';
        setActiveDrag(null);
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
            <h4>基础组件</h4>
            <div className="form-designer__palette-grid">
              {paletteEntries
                .filter((e) => e.type !== 'description')
                .map((e) => (
                  <PaletteCard
                    key={e.type}
                    entry={e}
                    onAdd={() => addPaletteEntry(e)}
                  />
                ))}
            </div>
          </aside>
          <main className="form-designer__workspace">
            <Space className="form-designer__toolbar">
              <Button onClick={undo}>撤销</Button>
              <Button disabled={schema.length === 0} onClick={() => resetSchema([])}>
                清空
              </Button>
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