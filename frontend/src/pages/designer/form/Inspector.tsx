import { Button } from 'antd';
import { useFormDesignerStore } from './useFormDesignerStore';
import { formRegistry, findById } from '../../../registry/formRegistry';

export function Inspector() {
  const selectedId = useFormDesignerStore((s) => s.selectedId);
  const schema = useFormDesignerStore((s) => s.schema);
  const updateNode = useFormDesignerStore((s) => s.updateNode);
  const removeNode = useFormDesignerStore((s) => s.removeNode);

  if (!selectedId) {
    return (
      <div style={{ padding: 16, color: '#888' }}>
        在画布中选中一个字段以编辑
      </div>
    );
  }
  const node = findById(schema, selectedId);
  if (!node) return null;
  const ft = (formRegistry as any)[node.type];
  if (!ft) return null;

  return (
    <div>
      <h4 style={{ padding: 16, margin: 0, borderBottom: '1px solid #eee' }}>
        {ft.label} <small style={{ color: '#888' }}>({node.type})</small>
      </h4>
      <ft.ConfigPanel node={node} onChange={(n) => updateNode(node.id, n)} />
      <div style={{ padding: 16 }}>
        <Button danger onClick={() => removeNode(node.id)}>
          删除字段
        </Button>
      </div>
    </div>
  );
}
