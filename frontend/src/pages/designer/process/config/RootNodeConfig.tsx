import { Alert, Divider, Form, Input, Radio, Switch } from 'antd';
import { AssigneePicker } from '../../../../components/AssigneePicker';
import type { TreeNode } from '../types';
import { useProcessDesignerStore } from '../useProcessDesignerStore';

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
        title="可发起该流程的人（留空=全员）"
      />
      <Form.Item label="允许发起的人员">
        <AssigneePicker
          mode="user"
          value={p.assignedUser ?? []}
          onChange={(ids) => set({ assignedUser: ids })}
        />
      </Form.Item>
      <Divider />
      <Form.Item label="驳回修改后重新提交">
        <Radio.Group
          value={(p.resubmitStrategy as string | undefined) ?? 'FULL'}
          onChange={(event) => set({ resubmitStrategy: event.target.value })}
          options={[
            { value: 'FULL', label: '全部重新审批' },
            { value: 'DIFF_CONTINUE', label: '未变字段续跑' },
          ]}
        />
      </Form.Item>
      <Form.Item label="发起人是审批人时自动通过">
        <Switch
          checked={Boolean(p.skipStarterAsApprover)}
          onChange={(skipStarterAsApprover) => set({ skipStarterAsApprover })}
        />
      </Form.Item>
      <Form.Item label="连续节点审批人相同时自动通过">
        <Switch
          checked={Boolean(p.skipConsecutiveSameApprover)}
          onChange={(skipConsecutiveSameApprover) =>
            set({ skipConsecutiveSameApprover })
          }
        />
      </Form.Item>
      <Form.Item label="流程兜底审批人">
        <AssigneePicker
          mode="user"
          value={(p.fallbackAssignee as { ids?: number[] })?.ids ?? []}
          onChange={(ids) => set({ fallbackAssignee: { type: 'USER', ids } })}
        />
      </Form.Item>
    </Form>
  );
}
