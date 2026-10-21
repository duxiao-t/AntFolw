import { App, Button, Card, Modal, Select, Space, Typography } from 'antd';
import { useMemo, useRef, useState } from 'react';
import { useModel, useParams, history } from '@umijs/max';
import { useQuery, useMutation } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { FormRenderer } from '../../../components/FormRenderer/FormRenderer';
import { AssigneePicker } from '../../../components/AssigneePicker';
import {
  collectVisibleValues,
  firstVisibleValidationError,
} from '../../../registry/displayConditions';
import type { FieldMode, SchemaNode } from '../../../registry/types';

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
  const [companyId, setCompanyId] = useState<number | null>(null);

  // P4a：归属在创建时快照。有部门的账号由服务端按身份推导，这里不打扰他；
  // **没有部门的全局管理员**（如种子 admin）必须显式选一个公司，否则服务端会拒绝创建。
  const currentUser = useModel('@@initialState')?.initialState?.currentUser as
    | { departmentId?: unknown }
    | undefined;
  const departmentId = Number(currentUser?.departmentId);
  const needsCompany =
    currentUser != null && !(Number.isInteger(departmentId) && departmentId > 0);

  const { data: fd, isFetching } = useQuery<any>({
    queryKey: ['mobile-form', code],
    queryFn: () => request(`/api/mobile/forms/${encodeURIComponent(code)}`),
  });
  const { data: companies, isError: companiesFailed } = useQuery<any[]>({
    queryKey: ['companies'],
    queryFn: () => request('/api/companies').then((r: any) => r ?? []),
    enabled: needsCompany,
    retry: 0,
  });
  const companyBlocked = () => {
    if (!needsCompany || companyId != null) return false;
    message.error(
      companiesFailed
        ? '无法加载公司列表，暂时不能提交，请稍后重试'
        : '请先选择本次提交归属的公司',
    );
    return true;
  };
  const fieldModes = useMemo<Record<string, FieldMode>>(
    () =>
      Object.fromEntries(
        Object.entries(fd?.starterFieldModes ?? {}).map(([fieldId, mode]) => [
          fieldId,
          mode === 'HIDDEN'
            ? 'hidden'
            : mode === 'READONLY'
              ? 'readonly'
              : 'runtime-fill',
        ]),
      ),
    [fd?.starterFieldModes],
  );

  /**
   * 幂等键要按「一次提交」固化，不能每次调用现生成：
   * 超时/断网后用户重按提交时，服务端必须能认出这是同一次操作，否则会落两份实例。
   * 成功后换新键（下一次是新操作）；失败保持原键（重试继续复用）。
   */
  const idempotencyKeyRef = useRef(createIdempotencyKey());
  const nextIdempotencyKey = () => {
    idempotencyKeyRef.current = createIdempotencyKey();
  };

  const startInstance = useMutation({
    mutationFn: (payload: { selfSelected: Record<string, number[]>; data: Record<string, any> }) =>
      request('/api/mobile/instances', {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKeyRef.current },
        data: {
          formCode: code,
          data: payload.data,
          selfSelected: payload.selfSelected,
          draftId: null,
          companyId,
          files: collectFileRefs(payload.data),
        },
      }),
    onSuccess: () => {
      nextIdempotencyKey();
      message.success('提交成功');
      history.push('/proc');
    },
  });

  const submitFormData = useMutation({
    mutationFn: (data: Record<string, any>) =>
      request('/api/forms/data', {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKeyRef.current },
        data: {
          formCode: code,
          status: 'SUBMITTED',
          data,
          companyId,
          files: collectFileRefs(data),
        },
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
    if (companyBlocked()) return;
    const workflowEnabled = !!parseJsonValue<Record<string, any>>(
      fd?.settings,
      {},
    ).workflowEnabled;
    if (!workflowEnabled) {
      submitFormData.mutate(submissionData);
      return;
    }
    const tree: TreeNode | undefined = parseJsonValue(fd?.process, undefined);
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
  };

  if (isFetching) return <Card loading />;
  if (!fd) return <Card>表单未找到</Card>;
  return (
    <Card title={fd.name}>
      <FormRenderer
        schema={parseJsonValue(fd.schema, [])}
        mode="runtime-fill"
        fieldModes={fieldModes}
        optionContext={{ formCode: code, formVersion: fd.version }}
        value={val}
        onChange={setVal}
      />
      {needsCompany ? (
        <div style={{ marginTop: 16 }}>
          <Typography.Text type="secondary">
            当前账号没有部门，请选择本次提交归属的公司：
          </Typography.Text>
          <Select
            style={{ display: 'block', marginTop: 8, maxWidth: 320 }}
            placeholder={companiesFailed ? '公司列表加载失败' : '请选择公司'}
            value={companyId ?? undefined}
            disabled={companiesFailed}
            loading={!companiesFailed && companies == null}
            onChange={(value: number) => setCompanyId(value ?? null)}
            options={(companies ?? []).map((company: any) => ({
              label: company.name,
              value: company.id,
            }))}
          />
          {companiesFailed ? (
            <Typography.Text type="danger">
              无法加载公司列表，暂时不能提交，请稍后重试。
            </Typography.Text>
          ) : null}
        </div>
      ) : null}
      <Button
        type="primary"
        style={{ marginTop: 16 }}
        onClick={handleSubmit}
        disabled={needsCompany && companyId == null}
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
