import { Form, Input } from 'antd';
import type { TreeNode } from '../types';
import { useProcessDesignerStore } from '../useProcessDesignerStore';
import type { FieldDef } from './ConditionRulesEditor';

export function BranchNodeConfig({
  node,
  formFields,
}: {
  node: TreeNode;
  formFields: FieldDef[];
}) {
  const updateName = useProcessDesignerStore((state) => state.updateName);

  return (
    <Form layout="vertical" className="pt-config-form">
      <Form.Item label="分支名称">
        <Input
          value={node.name ?? ''}
          onChange={(event) => updateName(node.id, event.target.value)}
        />
      </Form.Item>
      <Form.Item label="执行方式">
        <Input value="始终执行" readOnly />
      </Form.Item>
    </Form>
  );
}
