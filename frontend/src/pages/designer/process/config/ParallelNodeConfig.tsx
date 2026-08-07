import { PlusOutlined } from '@ant-design/icons';
import { Alert, Button, Form, Input, Space, Typography } from 'antd';
import type { TreeNode } from '../types';
import { useProcessDesignerStore } from '../useProcessDesignerStore';

/** 并行网关配置：管理分支名称与数量；分支内节点在画布上编辑。 */
export function ParallelNodeConfig({ node }: { node: TreeNode }) {
  const addBranch = useProcessDesignerStore((s) => s.addBranch);
  const updateName = useProcessDesignerStore((s) => s.updateName);
  return (
    <Space
      vertical
      className="pt-config-form"
      style={{ width: '100%' }}
      size={16}
    >
      <Form layout="vertical" component={false}>
        <Form.Item label="节点名称" style={{ marginBottom: 0 }}>
          <Input
            value={node.name ?? ''}
            onChange={(event) => updateName(node.id, event.target.value)}
          />
        </Form.Item>
      </Form>
      <Alert
        type="info"
        showIcon
        title="并行审批：所有分支的审批都完成后，流程才汇聚到下一步。任一分支被驳回时，整个并行网关驳回。"
      />
      <div>
        <Typography.Text strong>分支</Typography.Text>
        {node.branchs?.map((b) => (
          <div key={b.id} style={{ display: 'flex', gap: 8, marginTop: 8 }}>
            <Input
              value={b.name}
              onChange={(e) => updateName(b.id, e.target.value)}
              placeholder="分支名称"
            />
          </div>
        ))}
        <Button
          icon={<PlusOutlined />}
          style={{ marginTop: 8 }}
          disabled={(node.branchs?.length ?? 0) >= 8}
          onClick={() => addBranch(node.id)}
        >
          添加分支
        </Button>
      </div>
    </Space>
  );
}
