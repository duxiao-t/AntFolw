import { DndContext, useDraggable, useDroppable } from '@dnd-kit/core';
import { Button, Space, message } from 'antd';
import { useEffect, useState } from 'react';
import { useParams, useNavigate } from '@umijs/max';
import { useMutation } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { FormRenderer } from '../../../components/FormRenderer/FormRenderer';
import {
  paletteEntries,
  formRegistry,
} from '../../../registry/formRegistry';
import { useFormDesignerStore } from './useFormDesignerStore';
import { Inspector } from './Inspector';

function PaletteCard({ entry }: any) {
  const { attributes, listeners, setNodeRef } = useDraggable({
    id: entry.type,
    data: { source: 'palette' },
  });
  return (
    <div
      ref={setNodeRef}
      {...attributes}
      {...listeners}
      style={{
        padding: 12,
        border: '1px solid #ddd',
        margin: 4,
        cursor: 'grab',
      }}
    >
      {entry.label}
    </div>
  );
}

function CanvasDrop() {
  const { setNodeRef } = useDroppable({ id: 'canvas' });
  const schema = useFormDesignerStore((s) => s.schema);
  const selectedId = useFormDesignerStore((s) => s.selectedId);
  const select = useFormDesignerStore((s) => s.select);
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
      ref={setNodeRef}
      style={{ flex: 1, padding: 16, background: '#fafafa' }}
      onClick={(e) => {
        const id = (e.target as HTMLElement)
          .closest('[data-field-id]')
          ?.getAttribute('data-field-id');
        if (id) select(id);
      }}
    >
      <FormRenderer schema={schema} mode="designer-preview" value={{}} />
    </div>
  );
}

type FormDefinition = {
  id: number;
  code: string;
  name: string;
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
  const { schema, loadSchema, addNode, undo, redo } = useFormDesignerStore();
  const [definition, setDefinition] = useState<FormDefinition | null>(null);

  // Load existing definition when id is provided (not 'new').
  useEffect(() => {
    if (!id || id === 'new') return;
    (async () => {
      try {
        const fd = await request<any>(`/api/forms/definitions/${id}`);
        setDefinition(fd);
        loadSchema(parseJsonValue(fd.schema, []));
      } catch (e) {
        message.error('加载表单失败');
      }
    })();
  }, [id, loadSchema]);

  const save = useMutation({
    mutationFn: () =>
      request('/api/forms/definitions', {
        method: 'POST',
        data: {
          id: id === 'new' ? null : Number(id),
          code: definition?.code ?? `form_${Date.now()}`,
          name: definition?.name ?? '未命名表单',
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

  return (
    <DndContext
      onDragEnd={(e) => {
        if (e.over?.id === 'canvas') {
          const t = String(e.active.id);
          addNode(null, t, formRegistry[t].defaultProps);
        }
      }}
    >
      <div style={{ display: 'flex', height: embedded ? 'calc(100vh - 260px)' : '100vh', minHeight: embedded ? 560 : undefined }}>
        <aside
          style={{
            width: 200,
            padding: 8,
            borderRight: '1px solid #eee',
            overflowY: 'auto',
          }}
        >
          <h4>字段</h4>
          {paletteEntries
            .filter((e) => e.type !== 'description')
            .map((e) => <PaletteCard key={e.type} entry={e} />)}
        </aside>
        <main
          style={{
            flex: 1,
            display: 'flex',
            flexDirection: 'column',
            overflow: 'hidden',
          }}
        >
          <Space style={{ padding: 8 }}>
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
          <CanvasDrop />
        </main>
        <aside
          style={{
            width: 320,
            borderLeft: '1px solid #eee',
            overflowY: 'auto',
          }}
        >
          <Inspector />
        </aside>
      </div>
    </DndContext>
  );
}

export default function FormDesigner() {
  return <FormDesignerSurface />;
}
