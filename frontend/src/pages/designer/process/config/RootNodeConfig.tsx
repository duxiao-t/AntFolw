import { Alert, Form, Input } from 'antd';
import type { TreeNode } from '../types';
import { useProcessDesignerStore } from '../useProcessDesignerStore';
import { AssigneePicker } from '../../../../components/AssigneePicker';

export function RootNodeConfig({ node }: { node: TreeNode }) {
  const updateProps = useProcessDesignerStore((s) => s.updateProps);
  const updateName = useProcessDesignerStore((s) => s.updateName);
  const p: Record<string, any> = node.props ?? {};
  const set = (patch: Record<string, any>): void => {
    updateProps(node.id, { ...p, ...patch });
  };

  return (
    <Form layout="vertical" style={{ padding: 16 }}>
      <Form.Item label="节点名称">
        <Input
          value={node.name ?? ''}
          onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
            updateName(node.id, e.target.value)
          }
        />
      </Form.Item>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="可发起该流程的人（留空=全员）"
      />
      <Form.Item label="允许发起的人员">
        <AssigneePicker
          mode="user"
          value={p.assignedUser ?? []}
          onChange={(ids) => set({ assignedUser: ids })}
        />
      </Form.Item>
    </Form>
  );
}