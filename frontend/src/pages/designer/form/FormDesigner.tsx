import {
  DndContext,
  DragOverlay,
  PointerSensor,
  useSensor,
  useSensors,
  type DragOverEvent,
  type DragStartEvent,
  useDraggable,
  useDroppable,
} from '@dnd-kit/core';
import { arrayMove, SortableContext, verticalListSortingStrategy } from '@dnd-kit/sortable';
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
  PictureOutlined,
  TableOutlined,
  UploadOutlined,
  UserOutlined,
  VideoCameraOutlined,
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
import { paletteGroups, formRegistry, type PaletteEntry } from '../../../registry/formRegistry';
import type { SchemaNode } from '../../../registry/types';
import { useFormDesignerStore } from './useFormDesignerStore';
import { Inspector } from './Inspector';
import './form-designer.less';

type ActiveDrag =
  | { source: 'palette'; entry: PaletteEntry }
  | {
      source: 'canvas';
      nodeId: string;
      entry: PaletteEntry;
      node: SchemaNode;
    };



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
  image_upload: <PictureOutlined />,
  video_upload: <VideoCameraOutlined />,
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
    id: `preview:${entry.type}`,
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
    <DesignerFieldPreview node={node}>
      <ft.Component node={renderNode} mode="designer-preview" value={undefined} />
    </DesignerFieldPreview>
  );
}



/**
 * Keeps the drag overlay inside the designer canvas, anchored at the canvas
 * center when the drag starts, and scales with pointer movement but clamped
 * to the canvas bounds.
 */
function CanvasDrop({
  schema,
  sortableIds,
  placeholderId,
  isDragActive,
  onDesignerNodeChange,
}: {
  schema: SchemaNode[];
  sortableIds: string[];
  placeholderId: string | null;
  isDragActive: boolean;
  onDesignerNodeChange(node: SchemaNode): void;
}) {
  const { isOver, setNodeRef } = useDroppable({ id: 'canvas' });
  const canvasRef = useRef<HTMLDivElement | null>(null);
  const selectedId = useFormDesignerStore((s) => s.selectedId);
  const select = useFormDesignerStore((s) => s.select);
  const setCanvasRef = (node: HTMLDivElement | null) => {
    canvasRef.current = node;
    setNodeRef(node);
  };

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
      onClick={(e) => {
        const id = (e.target as HTMLElement)
          .closest('[data-designer-field-id]')
          ?.getAttribute('data-designer-field-id');
        if (id) select(id);
      }}
    >
      <SortableContext items={sortableIds} strategy={verticalListSortingStrategy}>
        <FormRenderer
          schema={schema}
          mode="designer-preview"
          value={{}}
          sortableIds={sortableIds}
          placeholderId={placeholderId}
          onDesignerNodeChange={onDesignerNodeChange}
        />
      </SortableContext>
      {schema.length === 0 && !placeholderId && (
        <div className="form-designer__empty">拖入第一个字段</div>
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
  const { schema, loadSchema, resetSchema, addNode, insertNode, updateNode, undo } =
    useFormDesignerStore();
  const [definition, setDefinition] = useState<FormDefinition | null>(null);
  const [activeDrag, setActiveDrag] = useState<ActiveDrag | null>(null);
  const [visualIds, setVisualIds] = useState<string[]>(() =>
    schema.map((node) => node.id),
  );
  const visualIdsRef = useRef(visualIds);
  const [palettePreviewId, setPalettePreviewId] = useState<string | null>(null);
  const placeholderId =
    activeDrag?.source === 'palette' ? palettePreviewId : null;

  useEffect(() => {
    visualIdsRef.current = visualIds;
  }, [visualIds]);

  useEffect(() => {
    setVisualIds(schema.map((node) => node.id));
  }, [schema]);

  const resetVisualIds = useCallback(() => {
    setVisualIds(schema.map((node) => node.id));
  }, [schema]);

  const addPaletteEntry = useCallback(
    (entry: PaletteEntry) => {
      addNode(null, entry.type, entry.defaultProps);
    },
    [addNode],
  );

  const handleDragOver = useCallback(
    (event: DragOverEvent) => {
      const { over } = event;
      if (!over || !activeDrag) return;
      if (activeDrag.source === 'canvas') {
        const activeId = activeDrag.nodeId;
        const overId = String(over.id);
        if (overId === activeId) return;
        setVisualIds((ids) => {
          const oldIndex = ids.indexOf(activeId);
          const newIndex = ids.indexOf(overId);
          if (oldIndex < 0 || newIndex < 0) return ids;
          return arrayMove(ids, oldIndex, newIndex);
        });
        return;
      }
      if (!palettePreviewId) return;
      const previewId = palettePreviewId;
      const overId = String(over.id);
      setVisualIds((ids) => {
        const overIndex = ids.indexOf(overId);
        const existingIndex = ids.indexOf(previewId);
        if (existingIndex >= 0) {
          if (overIndex < 0) return ids;
          return arrayMove(ids, existingIndex, overIndex);
        }
        const next = [...ids];
        next.splice(overIndex < 0 ? next.length : overIndex, 0, previewId);
        return next;
      });
    },
    [activeDrag, palettePreviewId],
  );

  const handleDragEnd = useCallback(
    () => {
      const currentDrag = activeDrag;
      setActiveDrag(null);
      setPalettePreviewId(null);
      const finalIds = visualIdsRef.current;
      if (currentDrag?.source === 'canvas') {
        const ordered = finalIds
          .map((id) => schema.find((node) => node.id === id))
          .filter((node): node is SchemaNode => Boolean(node));
        if (
          ordered.length === schema.length &&
          ordered.some((node, index) => node.id !== schema[index]?.id)
        ) {
          resetSchema(ordered);
        }
        return;
      }
      if (currentDrag?.source === 'palette') {
        const index = palettePreviewId == null ? -1 : finalIds.indexOf(palettePreviewId);
        const type = currentDrag.entry.type;
        insertNode(
          null,
          type,
          formRegistry[type].defaultProps,
          index < 0 ? schema.length : index,
        );
      }
    },
    [activeDrag, insertNode, palettePreviewId, resetSchema, schema],
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
    height: embedded ? '100%' : 'calc(100vh - 120px)',
    minHeight: embedded ? 480 : undefined,
  } as React.CSSProperties;

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
  );

  return (
    <DndContext
      sensors={sensors}
      onDragStart={(e: DragStartEvent) => {
        const data = e.active.data.current as
          | { source?: string; entry?: PaletteEntry; node?: any }
          | undefined;
        if (data?.source === 'palette' && data.entry) {
          setPalettePreviewId(`preview:${data.entry.type}:${Date.now()}`);
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
      onDragOver={handleDragOver}
      onDragCancel={() => {
        setActiveDrag(null);
        setPalettePreviewId(null);
        resetVisualIds();
      }}
      onDragEnd={handleDragEnd}
    >
      <div className="form-designer-shell" style={designerVars}>
        <div className="form-designer">
          <aside className="form-designer__palette">
            {paletteGroups.map((group) => (
              <section key={group.key} className="form-designer__palette-group">
                <h4>{group.title}</h4>
                <div className="form-designer__palette-grid">
                  {group.entries
                    .filter((e) => e.type !== 'description')
                    .map((e) => (
                      <PaletteCard
                        key={e.type}
                        entry={e}
                        onAdd={() => addPaletteEntry(e)}
                      />
                    ))}
                </div>
              </section>
            ))}
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
              schema={schema}
              sortableIds={visualIds}
              placeholderId={placeholderId}
              isDragActive={!!activeDrag}
              onDesignerNodeChange={(node) => updateNode(node.id, node)}
            />
          </main>
          <aside className="form-designer__inspector">
            <Inspector />
          </aside>
        </div>
        <DragOverlay dropAnimation={null}>
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