import { Form, Input, Radio, Select } from 'antd';
import { AssigneePicker } from '../../../../components/AssigneePicker';
import type { FormFieldOption, TreeNode } from '../types';
import { useProcessDesignerStore } from '../useProcessDesignerStore';

export function CcNodeConfig({
  node,
  formFields,
}: {
  node: TreeNode;
  formFields: FormFieldOption[];
}) {
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
      <Form.Item label="抄送对象">
        <Radio.Group
          value={(p.assignedType as string | undefined) ?? 'ASSIGN_USER'}
          onChange={(event) => set({ assignedType: event.target.value })}
          options={[
            { value: 'ASSIGN_USER', label: '指定成员' },
            { value: 'ROLE', label: '角色' },
            { value: 'FIELD_USER', label: '表单中的人员' },
          ]}
        />
      </Form.Item>
      {((p.assignedType as string | undefined) ?? 'ASSIGN_USER') === 'ASSIGN_USER' && (
        <Form.Item label="抄送成员">
        <AssigneePicker
          mode="user"
          value={p.assignedUser ?? []}
          onChange={(ids) => set({ assignedUser: ids })}
        />
        </Form.Item>
      )}
      {p.assignedType === 'ROLE' && (
        <Form.Item label="抄送角色">
          <AssigneePicker
            mode="role"
            value={p.role ?? []}
            onChange={(ids) => set({ role: ids })}
          />
        </Form.Item>
      )}
      {p.assignedType === 'FIELD_USER' && (
        <Form.Item
          label="人员字段"
          extra="字段为空或所选成员不可用时，抄送会静默跳过。"
        >
          <Select
            value={(p.fieldUser as { fieldId?: string })?.fieldId}
            placeholder="选择人员选择字段"
            options={formFields
              .filter((field) => field.type === 'user_picker' && !field.inTable)
              .map((field) => ({ value: field.id, label: field.label }))}
            onChange={(fieldId) => set({ fieldUser: { fieldId } })}
          />
        </Form.Item>
      )}
    </Form>
  );
}
