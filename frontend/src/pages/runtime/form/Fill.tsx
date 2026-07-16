import { Button, Card, message, Modal, Space, Typography } from 'antd';
import { useState } from 'react';
import { useParams, history } from '@umijs/max';
import { useQuery, useMutation } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { FormRenderer } from '../../../components/FormRenderer/FormRenderer';
import { AssigneePicker } from '../../../components/AssigneePicker';

type TreeNode = {
  id: string;
  type: string;
  name?: string;
  props?: Record<string, any>;
  children?: TreeNode | null;
  branchs?: TreeNode[];
};

type SelfSelectNode = {
  id: string;
  name: string;
  multiple: boolean;
};

function collectSelfSelectNodes(
  node: TreeNode | null | undefined,
  acc: SelfSelectNode[],
): void {
  if (!node) return;
  if (
    node.type === 'APPROVAL' &&
    node.props?.assignedType === 'SELF_SELECT'
  ) {
    acc.push({
      id: node.id,
      name: node.name ?? node.id,
      multiple: !!node.props?.selfSelect?.multiple,
    });
  }
  if (node.children) {
    collectSelfSelectNodes(node.children, acc);
  }
  if (Array.isArray(node.branchs)) {
    node.branchs.forEach((b) => {
      collectSelfSelectNodes(b, acc);
    });
  }
}

export default function Fill() {
  const params = useParams();
  const code = params.code as string;
  const [val, setVal] = useState<any>({});
  const [pickerOpen, setPickerOpen] = useState(false);
  const [selfSelected, setSelfSelected] = useState<Record<string, number[]>>(
    {},
  );
  const [pendingSelfSelect, setPendingSelfSelect] = useState<SelfSelectNode[]>(
    [],
  );

  const { data: fd, isFetching } = useQuery({
    queryKey: ['form-def', code],
    queryFn: () => request(`/api/forms/definitions/by-code/${code}`),
  });

  const startInstance = useMutation({
    mutationFn: (payload: { selfSelected: Record<string, number[]> }) =>
      request('/api/instances/start', {
        method: 'POST',
        data: { formCode: code, data: val, selfSelected: payload.selfSelected },
      }),
    onSuccess: () => {
      message.success('提交成功');
      history.push('/proc');
    },
  });

  const doStart = (sel: Record<string, number[]>) => {
    startInstance.mutate({ selfSelected: sel });
  };

  const handleSubmit = async () => {
    const formDefId = fd?.id;
    if (!formDefId) {
      message.error('表单定义未就绪');
      return;
    }
    try {
      const procRes: any = await request(
        `/api/processes/definitions/by-form/${formDefId}`,
      );
      const tree: TreeNode | undefined = procRes?.process;
      const nodes: SelfSelectNode[] = [];
      if (tree) collectSelfSelectNodes(tree, nodes);
      if (nodes.length === 0) {
        doStart({});
        return;
      }
      setPendingSelfSelect(nodes);
      setSelfSelected({});
      setPickerOpen(true);
    } catch (e) {
      message.error('获取流程定义失败');
    }
  };

  if (isFetching) return <Card loading />;
  if (!fd) return <Card>表单未找到</Card>;
  return (
    <Card title={fd.name}>
      <FormRenderer
        schema={fd.schema ?? []}
        mode="runtime-fill"
        value={val}
        onChange={setVal}
      />
      <Button
        type="primary"
        style={{ marginTop: 16 }}
        onClick={handleSubmit}
        loading={startInstance.isPending}
      >
        提交并发起审批
      </Button>

      <Modal
        title="为自选审批节点选择审批人"
        open={pickerOpen}
        onCancel={() => setPickerOpen(false)}
        onOk={() => {
          setPickerOpen(false);
          doStart(selfSelected);
        }}
        width={560}
        okText="确定并发起"
        cancelText="取消"
      >
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          <Typography.Text type="secondary">
            请为以下自选审批节点选择审批人
          </Typography.Text>
          {pendingSelfSelect.map((n) => (
            <div key={n.id}>
              <div style={{ marginBottom: 4 }}>
                <strong>{n.name}</strong>
                <span style={{ marginLeft: 8, color: '#999' }}>
                  ({n.multiple ? '可多选' : '单选'})
                </span>
              </div>
              <AssigneePicker
                mode="user"
                value={selfSelected[n.id]}
                onChange={(v: any) => {
                  const arr: number[] = Array.isArray(v)
                    ? v
                    : v != null
                      ? [v]
                      : [];
                  const finalArr = n.multiple ? arr : arr.slice(0, 1);
                  setSelfSelected((prev) => ({ ...prev, [n.id]: finalArr }));
                }}
              />
            </div>
          ))}
        </Space>
      </Modal>
    </Card>
  );
}