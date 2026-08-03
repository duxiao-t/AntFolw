import {
  Divider,
  Form,
  Input,
  InputNumber,
  Radio,
} from 'antd';
import type { TreeNode } from '../types';
import { useProcessDesignerStore } from '../useProcessDesignerStore';
import { AssigneePicker } from '../../../../components/AssigneePicker';

export function ApprovalNodeConfig({ node }: { node: TreeNode }) {
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
      <Form.Item label="审批对象">
        <Radio.Group
          value={p.assignedType as string}
          onChange={(e) => set({ assignedType: e.target.value })}
          options={[
            { value: 'ASSIGN_USER', label: '指定成员' },
            { value: 'ROLE', label: '角色' },
            { value: 'LEADER', label: '主管' },
            { value: 'SELF', label: '发起人自己' },
            { value: 'SELF_SELECT', label: '发起人自选' },
          ]}
        />
      </Form.Item>

      {p.assignedType === 'ASSIGN_USER' && (
        <Form.Item label="选择成员">
          <AssigneePicker
            mode="user"
            value={p.assignedUser ?? []}
            onChange={(ids) => set({ assignedUser: ids })}
          />
        </Form.Item>
      )}
      {p.assignedType === 'ROLE' && (
        <Form.Item label="选择角色">
          <AssigneePicker
            mode="role"
            value={p.role ?? []}
            onChange={(ids) => set({ role: ids })}
          />
        </Form.Item>
      )}
      {p.assignedType === 'LEADER' && (
        <Form.Item label="第几级主管（1=直接主管）">
          <InputNumber
            min={1}
            max={10}
            value={(p.leader as { level?: number })?.level ?? 1}
            onChange={(v) => set({ leader: { level: v ?? 1 } })}
          />
        </Form.Item>
      )}
      {p.assignedType === 'SELF_SELECT' && (
        <Form.Item label="自选方式">
          <Radio.Group
            value={
              ((p.selfSelect as { multiple?: boolean })?.multiple ?? false) as
                | boolean
                | string
            }
            onChange={(e) => set({ selfSelect: { multiple: e.target.value } })}
            options={[
              { value: false, label: '自选一人' },
              { value: true, label: '自选多人' },
            ]}
          />
        </Form.Item>
      )}

      <Divider />
      <Form.Item label="多人审批方式">
        <Radio.Group
          value={p.mode as string}
          onChange={(e) => set({ mode: e.target.value })}
          options={[
            { value: 'OR', label: '或签（一人通过即可）' },
            { value: 'AND', label: '会签（须全部通过）' },
          ]}
        />
      </Form.Item>
      <Form.Item label="审批人为空时">
        <Radio.Group
          value={
            ((p.nobody as { handler?: string })?.handler ?? 'TO_PASS') as string
          }
          onChange={(e) => set({ nobody: { handler: e.target.value } })}
          options={[
            { value: 'TO_PASS', label: '自动通过' },
            { value: 'TO_REFUSE', label: '自动驳回' },
          ]}
        />
      </Form.Item>
    </Form>
  );
}