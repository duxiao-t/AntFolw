import { Form, Input, Radio } from 'antd';
import type { TreeNode } from '../types';
import { useProcessDesignerStore } from '../useProcessDesignerStore';
import {
  type ConditionProps,
  ConditionRulesEditor,
  type FieldDef,
} from './ConditionRulesEditor';

export function BranchNodeConfig({
  node,
  formFields,
}: {
  node: TreeNode;
  formFields: FieldDef[];
}) {
  const updateProps = useProcessDesignerStore((state) => state.updateProps);
  const updateName = useProcessDesignerStore((state) => state.updateName);
  const props = (node.props ?? {}) as ConditionProps;
  const conditionMode = props.conditionMode ?? 'ALWAYS';

  return (
    <Form layout="vertical" className="pt-config-form">
      <Form.Item label="分支名称">
        <Input
          value={node.name ?? ''}
          onChange={(event) => updateName(node.id, event.target.value)}
        />
      </Form.Item>
      <Form.Item label="执行方式">
        <Radio.Group
          block
          optionType="button"
          buttonStyle="solid"
          value={conditionMode}
          onChange={(event) =>
            updateProps(node.id, {
              ...props,
              conditionMode: event.target.value as 'ALWAYS' | 'WHEN_MATCHED',
            })
          }
          options={[
            { value: 'ALWAYS', label: '始终执行' },
            { value: 'WHEN_MATCHED', label: '满足条件时执行' },
          ]}
        />
      </Form.Item>
      {conditionMode === 'WHEN_MATCHED' && (
        <ConditionRulesEditor
          props={props}
          formFields={formFields}
          onChange={(next) => updateProps(node.id, next)}
        />
      )}
    </Form>
  );
}
