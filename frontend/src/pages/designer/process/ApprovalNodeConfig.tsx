import { Form, Input, Select } from 'antd';
import type { Node } from '@xyflow/react';
import { AssigneePicker } from '../../components/AssigneePicker';

export function ApprovalNodeConfig({ node, onChange }: {
  node: Node<any, 'approval'>;
  onChange: (n: Node<any, 'approval'>) => void;
}) {
  const data = node.data as any;
  return (
    <Form layout="vertical" style={{ padding: 16 }}>
      <Form.Item label="节点名称">
        <Input
          value={data.label}
          onChange={(e) =>
            onChange({ ...node, data: { ...data, label: e.target.value } })
          }
        />
      </Form.Item>
      <Form.Item label="审批人类型">
        <Select
          value={data.assignee?.type ?? 'user'}
          onChange={(v) =>
            onChange({
              ...node,
              data: { ...data, assignee: { type: v, ids: [] } },
            })
          }
          options={[
            { value: 'user', label: '用户' },
            { value: 'role', label: '角色' },
          ]}
        />
      </Form.Item>
      {data.assignee?.type && data.assignee.type !== 'dept_leader' && (
        <Form.Item label="审批人">
          <AssigneePicker
            mode={data.assignee.type}
            value={data.assignee.ids}
            onChange={(ids) =>
              onChange({
                ...node,
                data: {
                  ...data,
                  assignee: { ...data.assignee, ids },
                },
              })
            }
          />
        </Form.Item>
      )}
    </Form>
  );
}
