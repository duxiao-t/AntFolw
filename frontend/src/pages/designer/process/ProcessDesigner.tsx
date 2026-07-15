import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  useNodesState,
  useEdgesState,
  addEdge,
  type Node,
  type Edge,
  type Connection,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { Button, Space, message } from 'antd';
import { useEffect, useState } from 'react';
import { useParams } from '@umijs/max';
import { request } from '@umijs/max';
import {
  ApprovalNode,
  StartNode,
  EndNode,
} from './ApprovalNodeComponent';
import { ApprovalNodeConfig } from './ApprovalNodeConfig';

const processNodeTypes = {
  start: StartNode,
  approval: ApprovalNode,
  end: EndNode,
};

export default function ProcessDesigner() {
  const { formDefId } = useParams();
  const [nodes, setNodes, onNodesChange] = useNodesState<Node>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const [selected, setSelected] = useState<Node | null>(null);
  const [pdId, setPdId] = useState<number | null>(null);

  useEffect(() => {
    (async () => {
      try {
        const pd = await request(`/api/processes/definitions/by-form/${formDefId}`);
        if (pd) {
          setPdId(pd.id);
          setNodes(
            (pd.nodes ?? []).map((n: any, idx: number) => ({
              id: n.id,
              type: n.type,
              position: { x: n.x ?? idx * 200, y: n.y ?? 80 },
              data: {
                label: n.type === 'start' ? '开始' : n.type === 'end' ? '结束' : '审批',
                assignee: n.assignee ?? { type: 'user', ids: [] },
              },
            })),
          );
          setEdges(
            (pd.edges ?? []).map((e: any) => ({
              id: `${e.from}->${e.to}`,
              source: e.from,
              target: e.to,
            })),
          );
          return;
        }
      } catch { /* 404 — fall through to new draft */ }
      setNodes([
        { id: 'start', type: 'start', position: { x: 80, y: 80 }, data: { label: '开始' } },
        { id: 'end',   type: 'end',   position: { x: 480, y: 80 }, data: { label: '结束' } },
      ]);
    })();
  }, [formDefId]);

  const save = async () => {
    const payload = {
      id: pdId ?? null,
      formDefId: Number(formDefId),
      nodes: nodes.map((n) => ({
        id: n.id,
        type: n.type,
        x: n.position.x,
        y: n.position.y,
        assignee: (n.data as any).assignee ?? { type: 'user', ids: [] },
        props: {},
      })),
      edges: edges.map((e) => ({ from: e.source, to: e.target })),
    };
    const res = await request('/api/processes/definitions', {
      method: 'POST',
      data: payload,
    });
    setPdId(res.id);
    message.success('已保存草稿');
  };

  const publish = async () => {
    if (!pdId) {
      await save();
    }
    await request(`/api/processes/definitions/${pdId}/publish`, {
      method: 'POST',
    });
    message.success('已发布');
  };

  return (
    <div style={{ display: 'flex', height: '100vh' }}>
      <aside style={{ width: 200, padding: 8 }}>
        <h4>节点</h4>
        <Button
          block
          style={{ marginBottom: 8 }}
          onClick={() =>
            setNodes((ns) => [
              ...ns,
              {
                id: 'n_' + Date.now(),
                type: 'approval',
                position: { x: 240, y: 80 },
                data: {
                  label: '审批节点',
                  assignee: { type: 'user', ids: [] },
                },
              },
            ])
          }
        >
          + 审批
        </Button>
      </aside>
      <main style={{ flex: 1 }}>
        <Space style={{ padding: 8 }}>
          <Button type="primary" onClick={save}>
            保存草稿
          </Button>
          <Button onClick={publish}>发布</Button>
        </Space>
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={processNodeTypes as any}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={(c: Connection) =>
            setEdges((es) =>
              addEdge(
                { ...c, id: `${c.source}->${c.target}` },
                es,
              ),
            )
          }
          onNodeClick={(_, n) => setSelected(n)}
        >
          <Background />
          <Controls />
          <MiniMap />
        </ReactFlow>
      </main>
      <aside style={{ width: 320, borderLeft: '1px solid #eee', overflowY: 'auto' }}>
        {selected && selected.type === 'approval' ? (
          <ApprovalNodeConfig
            node={selected as any}
            onChange={(n) => {
              setNodes((ns) => ns.map((x) => (x.id === n.id ? n : x)));
              setSelected(n);
            }}
          />
        ) : (
          <div style={{ padding: 16, color: '#888' }}>
            在画布中选中一个审批节点
          </div>
        )}
      </aside>
    </div>
  );
}
