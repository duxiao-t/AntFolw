import { App, Button, Card, Modal, Space, Typography } from 'antd';
import { useState } from 'react';
import { useParams, history } from '@umijs/max';
import { useQuery, useMutation } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { FormRenderer } from '../../../components/FormRenderer/FormRenderer';
import { AssigneePicker } from '../../../components/AssigneePicker';
import {
  collectVisibleValues,
  firstVisibleValidationError,
} from '../../../registry/displayConditions';
import type { SchemaNode } from '../../../registry/types';

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

function parseJsonValue<T>(value: T | string | undefined, fallback: T): T {
  if (typeof value !== 'string') return value ?? fallback;
  try {
    return JSON.parse(value);
  } catch {
    return fallback;
  }
}

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
  const { message } = App.useApp();
  const [val, setVal] = useState<any>({});
  const [pickerOpen, setPickerOpen] = useState(false);
  const [selfSelected, setSelfSelected] = useState<Record<string, number[]>>(
    {},
  );
  const [pendingSelfSelect, setPendingSelfSelect] = useState<SelfSelectNode[]>(
    [],
  );
  const [pendingSubmissionData, setPendingSubmissionData] = useState<Record<string, any>>({});

  const { data: fd, isFetching } = useQuery<any>({
    queryKey: ['form-def', code],
    queryFn: () => request(`/api/forms/definitions/by-code/${code}`),
  });

  const startInstance = useMutation({
    mutationFn: (payload: { selfSelected: Record<string, number[]>; data: Record<string, any> }) =>
      request('/api/mobile/instances', {
        method: 'POST',
        headers: { 'Idempotency-Key': createIdempotencyKey() },
        data: {
          formCode: code,
          data: payload.data,
          selfSelected: payload.selfSelected,
          draftId: null,
          files: collectFileRefs(payload.data),
        },
      }),
    onSuccess: () => {
      message.success('提交成功');
      history.push('/proc');
    },
  });

  const submitFormData = useMutation({
    mutationFn: (data: Record<string, any>) =>
      request('/api/forms/data', {
        method: 'POST',
        data: { formCode: code, status: 'SUBMITTED', data, files: collectFileRefs(data) },
      }),
    onSuccess: () => {
      message.success('提交成功');
      history.push('/runtime/list');
    },
  });

  const doStart = (sel: Record<string, number[]>, data = pendingSubmissionData) => {
    startInstance.mutate({ selfSelected: sel, data });
  };

  const handleSubmit = async () => {
    const schema = parseJsonValue<SchemaNode[]>(fd?.schema, []);
    const error = firstVisibleValidationError(schema, val);
    if (error) {
      message.error(error);
      return;
    }
    const submissionData = collectVisibleValues(schema, val);
    const workflowEnabled = !!parseJsonValue<Record<string, any>>(
      fd?.settings,
      {},
    ).workflowEnabled;
    if (!workflowEnabled) {
      submitFormData.mutate(submissionData);
      return;
    }
    const formDefId = fd?.id;
    if (!formDefId) {
      message.error('表单定义未就绪');
      return;
    }
    try {
      const procRes: any = await request(
        `/api/processes/definitions/by-form/${formDefId}`,
      );
      const tree: TreeNode | undefined = parseJsonValue(procRes?.process, undefined);
      const nodes: SelfSelectNode[] = [];
      if (tree) collectSelfSelectNodes(tree, nodes);
      if (nodes.length === 0) {
        doStart({}, submissionData);
        return;
      }
      setPendingSubmissionData(submissionData);
      setPendingSelfSelect(nodes);
      setSelfSelected({});
      setPickerOpen(true);
    } catch (_error) {
      message.error('获取流程定义失败');
    }
  };

  if (isFetching) return <Card loading />;
  if (!fd) return <Card>表单未找到</Card>;
  return (
    <Card title={fd.name}>
      <FormRenderer
        schema={parseJsonValue(fd.schema, [])}
        mode="runtime-fill"
        value={val}
        onChange={setVal}
      />
      <Button
        type="primary"
        style={{ marginTop: 16 }}
        onClick={handleSubmit}
        loading={startInstance.isPending || submitFormData.isPending}
      >
        {parseJsonValue<Record<string, any>>(fd.settings, {}).workflowEnabled
          ? '提交并发起审批'
          : '提交'}
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

function collectFileRefs(values: Record<string, unknown>) {
  const refs: Array<{ fileId: string; fieldId: string; sortOrder: number }> = [];
  for (const [fieldId, value] of Object.entries(values)) collectValueFiles(value, fieldId, refs);
  return refs;
}

function createIdempotencyKey() {
  return typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `desktop-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function collectValueFiles(value: unknown, fieldId: string, refs: Array<{ fileId: string; fieldId: string; sortOrder: number }>) {
  if (!Array.isArray(value)) return;
  if (value.every((item) => typeof item === 'object' && item != null
    && typeof (item as { id?: unknown }).id === 'string'
    && typeof (item as { contentType?: unknown }).contentType === 'string')) {
    value.forEach((file, sortOrder) => {
      refs.push({ fileId: (file as { id: string }).id, fieldId, sortOrder });
    });
    return;
  }
  value.forEach((row) => {
    if (typeof row !== 'object' || row == null || Array.isArray(row)) return;
    for (const [nestedFieldId, nested] of Object.entries(row)) collectValueFiles(nested, nestedFieldId, refs);
  });
}
