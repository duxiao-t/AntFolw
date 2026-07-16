import { Button, Space, message, Drawer } from 'antd';
import { useEffect, useState } from 'react';
import { useParams, request } from '@umijs/max';
import { useQuery } from '@tanstack/react-query';
import { ProcessTree } from './ProcessTree';
import { useProcessDesignerStore } from './useProcessDesignerStore';
import { ApprovalNodeConfig } from './config/ApprovalNodeConfig';
import { CcNodeConfig } from './config/CcNodeConfig';
import { ConditionNodeConfig } from './config/ConditionNodeConfig';
import { RootNodeConfig } from './config/RootNodeConfig';
import type { TreeNode } from './types';

function find(node: TreeNode | null | undefined, id: string): TreeNode | null {
  if (!node) return null;
  if (node.id === id) return node;
  for (const b of node.branchs ?? []) {
    const h = find(b, id);
    if (h) return h;
  }
  return find(node.children, id);
}

type FormDefinition = {
  id: number;
  code: string;
  name: string;
  schema?: any[];
};

export default function ProcessDesigner() {
  const { formDefId } = useParams();
  const process = useProcessDesignerStore(
    (s: ReturnType<typeof useProcessDesignerStore.getState>) => s.process,
  );
  const selectedId = useProcessDesignerStore(
    (s: ReturnType<typeof useProcessDesignerStore.getState>) => s.selectedId,
  );
  const load = useProcessDesignerStore(
    (s: ReturnType<typeof useProcessDesignerStore.getState>) => s.load,
  );
  const select = useProcessDesignerStore(
    (s: ReturnType<typeof useProcessDesignerStore.getState>) => s.select,
  );
  const [pdId, setPdId] = useState<number | null>(null);

  // Load existing process definition for this form; fall back to fresh root on 404.
  useEffect(() => {
    (async () => {
      try {
        const pd = await request<any>(
          `/api/processes/definitions/by-form/${formDefId}`,
        );
        if (pd?.process) {
          setPdId(pd.id);
          load(pd.process);
          return;
        }
      } catch {
        /* no saved process yet — use fresh root */
      }
      load(null);
    })();
  }, [formDefId, load]);

  // Pull the form schema so the condition node can offer field choices.
  const { data: formDef } = useQuery<FormDefinition>({
    queryKey: ['form-def-for-flow', formDefId],
    queryFn: () => request<FormDefinition>(`/api/forms/definitions/${formDefId}`),
    enabled: !!formDefId,
  });
  const formFields = ((formDef?.schema ?? []) as any[]).map((n) => ({
    id: n.id,
    label: n.props?.label ?? n.type,
    type: n.type,
  }));

  const save = async (): Promise<void> => {
    const res = await request<any>('/api/processes/definitions', {
      method: 'POST',
      data: { id: pdId, formDefId: Number(formDefId), process },
    });
    setPdId(res.id);
    message.success('已保存草稿');
  };

  const publish = async (): Promise<void> => {
    let id = pdId;
    if (!id) {
      const res = await request<any>('/api/processes/definitions', {
        method: 'POST',
        data: { id: null, formDefId: Number(formDefId), process },
      });
      id = res.id;
      setPdId(id);
    }
    await request(`/api/processes/definitions/${id}/publish`, { method: 'POST' });
    message.success('已发布');
  };

  const selected = selectedId ? find(process, selectedId) : null;

  return (
    <div style={{ height: '100vh', display: 'flex', flexDirection: 'column' }}>
      <Space style={{ padding: 8 }}>
        <Button type="primary" onClick={save}>
          保存草稿
        </Button>
        <Button onClick={publish}>发布</Button>
      </Space>
      <div
        style={{ flex: 1, overflow: 'auto', background: '#f5f6f6', padding: 24 }}
      >
        <ProcessTree />
      </div>
      <Drawer
        open={!!selected}
        width={400}
        onClose={() => select(null)}
        title={selected?.name}
        destroyOnClose
      >
        {selected?.type === 'ROOT' && <RootNodeConfig node={selected} />}
        {selected?.type === 'APPROVAL' && <ApprovalNodeConfig node={selected} />}
        {selected?.type === 'CC' && <CcNodeConfig node={selected} />}
        {selected?.type === 'CONDITION' && (
          <ConditionNodeConfig node={selected} formFields={formFields} />
        )}
      </Drawer>
    </div>
  );
}