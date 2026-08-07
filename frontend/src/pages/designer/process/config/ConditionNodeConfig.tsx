import { Alert, Form, Input } from 'antd';
import type { TreeNode } from '../types';
import { useProcessDesignerStore } from '../useProcessDesignerStore';
import {
  type ConditionProps,
  ConditionRulesEditor,
  type FieldDef,
} from './ConditionRulesEditor';

export function ConditionNodeConfig({
  node,
  formFields,
}: {
  node: TreeNode;
  formFields: FieldDef[];
}) {
  const updateProps = useProcessDesignerStore((state) => state.updateProps);
  const updateName = useProcessDesignerStore((state) => state.updateName);
  const props = (node.props ?? {}) as ConditionProps;

  if (props.isDefault) {
    return (
      <Form layout="vertical" className="pt-config-form">
        <Form.Item label="分支名称">
          <Input
            value={node.name ?? ''}
            onChange={(event) => updateName(node.id, event.target.value)}
          />
        </Form.Item>
        <Alert type="info" showIcon title="其它条件都不满足时进入默认分支。" />
      </Form>
    );
  }

  return (
    <Form layout="vertical" className="pt-config-form">
      <Form.Item label="分支名称">
        <Input
          value={node.name ?? ''}
          onChange={(event) => updateName(node.id, event.target.value)}
        />
      </Form.Item>
      <ConditionRulesEditor
        props={props}
        formFields={formFields}
        onChange={(next) => updateProps(node.id, next)}
      />
    </Form>
  );
}
