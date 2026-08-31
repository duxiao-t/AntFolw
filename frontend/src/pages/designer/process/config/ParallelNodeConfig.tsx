import { PlusOutlined } from '@ant-design/icons';
import { Alert, Button, Form, Input, Radio, Space, Typography } from 'antd';
import type { TreeNode } from '../types';
import { useProcessDesignerStore } from '../useProcessDesignerStore';

/** 并行网关配置：管理分支名称与数量；分支内节点在画布上编辑。 */
export function ParallelNodeConfig({ node }: { node: TreeNode }) {
  const addBranch = useProcessDesignerStore((s) => s.addBranch);
  const updateName = useProcessDesignerStore((s) => s.updateName);
  const updateProps = useProcessDesignerStore((s) => s.updateProps);
  const props = node.props ?? {};
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
        <Form.Item label="分支汇聚方式" style={{ marginTop: 16, marginBottom: 0 }}>
          <Radio.Group
            value={(props.joinMode as string | undefined) ?? 'ALL'}
            onChange={(event) =>
              updateProps(node.id, { ...props, joinMode: event.target.value })
            }
            options={[
              { value: 'ALL', label: '全部通过；任一驳回则退回' },
              { value: 'ANY', label: '任一通过；单分支驳回不影响其他分支' },
            ]}
          />
        </Form.Item>
      </Form>
      <Alert
        type="info"
        showIcon
        title={
          props.joinMode === 'ANY'
            ? '任一分支通过后立即汇聚；只有所有分支都驳回时才退回。'
            : '所有分支都通过后才汇聚；任一分支驳回会作废其他分支。'
        }
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
