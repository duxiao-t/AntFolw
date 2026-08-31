import { Alert, Divider, Form, Input, InputNumber, Radio, Select } from 'antd';
import { AssigneePicker } from '../../../../components/AssigneePicker';
import type { FieldPerm, FormFieldOption, TreeNode } from '../types';
import { useProcessDesignerStore } from '../useProcessDesignerStore';
import { FieldPermissionEditor } from './FieldPermissionEditor';

export function ApprovalNodeConfig({
  node,
  formFields,
  rejectTargets,
}: {
  node: TreeNode;
  formFields: FormFieldOption[];
  rejectTargets?: Array<{ id: string; label: string }>;
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
      <Form.Item label="审批对象">
        <Radio.Group
          value={p.assignedType as string}
          onChange={(e) => set({ assignedType: e.target.value })}
          options={[
            { value: 'ASSIGN_USER', label: '指定成员' },
            { value: 'ROLE', label: '角色' },
            { value: 'LEADER', label: '部门主管' },
            { value: 'DIRECT_MANAGER', label: '制单人直属上级' },
            { value: 'SELF', label: '发起人自己' },
            { value: 'SELF_SELECT', label: '发起人自选' },
            { value: 'FIELD_USER', label: '表单中的人员' },
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
        <Form.Item label="第几级部门主管（1=当前部门）">
          <InputNumber
            min={1}
            max={10}
            value={(p.leader as { level?: number })?.level ?? 1}
            onChange={(v) => set({ leader: { level: v ?? 1 } })}
          />
        </Form.Item>
      )}
      {p.assignedType === 'DIRECT_MANAGER' && (
        <Form.Item label="第几级直属上级">
          <InputNumber
            min={1}
            max={10}
            value={(p.manager as { level?: number })?.level ?? 1}
            onChange={(v) => set({ manager: { level: v ?? 1 } })}
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
      {p.assignedType === 'FIELD_USER' && (
        <Form.Item label="人员字段">
          <Select
            value={(p.fieldUser as { fieldId?: string })?.fieldId}
            placeholder="选择人员选择字段"
            options={formFields
              .filter((field) => field.type === 'user_picker')
              .map((field) => ({ value: field.id, label: field.label }))}
            onChange={(fieldId) => set({ fieldUser: { fieldId } })}
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
            { value: 'RATIO', label: '比例签' },
            { value: 'SEQUENTIAL', label: '顺签' },
          ]}
        />
      </Form.Item>
      {p.mode === 'RATIO' && (
        <Form.Item label="通过比例（%）">
          <InputNumber
            min={1}
            max={100}
            value={(p.ratio as number | undefined) ?? 60}
            onChange={(ratio) => set({ ratio: ratio ?? 60 })}
          />
        </Form.Item>
      )}
      <Form.Item label="审批人为空时的兜底人员">
        <AssigneePicker
          mode="user"
          value={(p.fallbackAssignee as { ids?: number[] })?.ids ?? []}
          onChange={(ids) => set({ fallbackAssignee: { type: 'USER', ids } })}
        />
      </Form.Item>
      {p.assignedType !== 'DIRECT_MANAGER' && (
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
      )}
      <Divider />
      <Form.Item label="超时后">
        <Select
          allowClear
          placeholder="不设置超时"
          value={(p.timeoutPolicy as { action?: string })?.action}
          options={[
            { value: 'REMIND', label: '提醒当前审批人' },
            { value: 'ESCALATE', label: '升级给直属上级' },
            { value: 'AUTO_APPROVE', label: '低风险流程自动通过' },
          ]}
          onClear={() => set({ timeoutPolicy: undefined })}
          onChange={(action) =>
            set({
              timeoutPolicy: {
                afterMinutes:
                  (p.timeoutPolicy as { afterMinutes?: number })?.afterMinutes ?? 1440,
                action,
                ...(action === 'AUTO_APPROVE' ? { riskLevel: 'LOW' } : {}),
              },
            })
          }
        />
      </Form.Item>
      {(p.timeoutPolicy as { action?: string } | undefined)?.action && (
        <Form.Item label="等待时长（分钟）">
          <InputNumber
            min={1}
            max={525600}
            value={(p.timeoutPolicy as { afterMinutes?: number }).afterMinutes ?? 1440}
            onChange={(afterMinutes) =>
              set({ timeoutPolicy: { ...p.timeoutPolicy, afterMinutes: afterMinutes ?? 1 } })
            }
          />
        </Form.Item>
      )}
      {p.timeoutPolicy?.action === 'AUTO_APPROVE' && (
        <Alert
          type="warning"
          showIcon
          title="自动通过只适用于已确认的低风险流程。"
          style={{ marginBottom: 16 }}
        />
      )}
      {rejectTargets && rejectTargets.length > 0 && (
        <Form.Item label="允许驳回到">
          <Select
            mode="multiple"
            value={(p.rejectTargets as string[] | undefined) ?? []}
            options={rejectTargets.map((target) => ({
              value: target.id,
              label: target.label,
            }))}
            placeholder="未选择时仅允许退回直接上一级"
            onChange={(values) =>
              set({ rejectTargets: values.length > 0 ? values : undefined })
            }
          />
        </Form.Item>
      )}
      <Divider />
      <Form.Item label="字段权限">
        <FieldPermissionEditor
          formFields={formFields}
          value={p.formPerms as FieldPerm[] | undefined}
          onChange={(formPerms) => set({ formPerms })}
        />
      </Form.Item>
    </Form>
  );
}
