import {
  CheckCircleOutlined,
  CompressOutlined,
  MinusOutlined,
  PlusOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { request, useNavigate, useParams } from '@umijs/max';
import { App, Button, Drawer, Space, Tooltip } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import { ApprovalNodeConfig } from './config/ApprovalNodeConfig';
import { BranchNodeConfig } from './config/BranchNodeConfig';
import { CcNodeConfig } from './config/CcNodeConfig';
import { ConditionNodeConfig } from './config/ConditionNodeConfig';
import { DelayNodeConfig } from './config/DelayNodeConfig';
import { ParallelNodeConfig } from './config/ParallelNodeConfig';
import { RootNodeConfig } from './config/RootNodeConfig';
import { TriggerNodeConfig } from './config/TriggerNodeConfig';
import { ProcessTree } from './ProcessTree';
import type { FormFieldOption, TreeNode } from './types';
import { useProcessDesignerStore } from './useProcessDesignerStore';
import { validateProcessTree } from './validation';

function find(node: TreeNode | null | undefined, id: string): TreeNode | null {
  if (!node) return null;
  if (node.id === id) return node;
  for (const branch of node.branchs ?? []) {
    const match = find(branch, id);
    if (match) return match;
  }
  return find(node.children, id);
}

function upstreamApprovals(root: TreeNode, id: string): Array<{ id: string; label: string }> {
  const path: TreeNode[] = [];
  const visit = (node: TreeNode | null | undefined): boolean => {
    if (!node) return false;
    path.push(node);
    if (node.id === id) return true;
    for (const branch of node.branchs ?? []) {
      if (visit(branch)) return true;
    }
    if (visit(node.children)) return true;
    path.pop();
    return false;
  };
  if (!visit(root)) return [];
  return path
    .slice(0, -1)
    .filter((node) => node.type === 'APPROVAL')
    .map((node) => ({ id: node.id, label: node.name || '审批人' }));
}

type FormDefinition = {
  id: number;
  code: string;
  name: string;
  schema?: any[] | string;
};

function parseJsonValue<T>(value: T | string | undefined, fallback: T): T {
  if (typeof value !== 'string') return value ?? fallback;
  try {
    return JSON.parse(value);
  } catch {
    return fallback;
  }
}

const FIELD_CONTAINER_TYPES = new Set(['span_layout', 'table_list']);

function flattenFormFields(nodes: any[]): FormFieldOption[] {
  const result: FormFieldOption[] = [];
  const visit = (list: any[]) => {
    for (const node of list) {
      if (!node?.id) continue;
      if (FIELD_CONTAINER_TYPES.has(node.type)) {
        if (Array.isArray(node.children)) visit(node.children);
        continue;
      }
      if (node.type === 'description') continue;
      result.push({
        id: node.id,
        label: node.label ?? node.props?.label ?? node.id,
        type: node.type,
        options: Array.isArray(node.props?.options)
          ? node.props.options
              .filter(
                (option: any) =>
                  option &&
                  !option.isOther &&
                  (typeof option.value === 'string' ||
                    typeof option.value === 'number'),
              )
              .map((option: any) => ({
                label: String(option.label ?? option.value),
                value: option.value,
              }))
          : undefined,
      });
    }
  };
  visit(nodes);
  return result;
}

export function ProcessDesignerSurface({
  formDefId: formDefIdProp,
  embedded = false,
  onSaved,
}: {
  formDefId?: string | number;
  embedded?: boolean;
  onSaved?: (processDefinition: any) => void;
}) {
  const routeParams = useParams();
  const formDefId = String(formDefIdProp ?? routeParams.formDefId ?? '');
  const { message } = App.useApp();
  const viewportRef = useRef<HTMLDivElement>(null);
  const process = useProcessDesignerStore((state) => state.process);
  const selectedId = useProcessDesignerStore((state) => state.selectedId);
  const load = useProcessDesignerStore((state) => state.load);
  const select = useProcessDesignerStore((state) => state.select);
  const [pdId, setPdId] = useState<number | null>(null);
  const [zoom, setZoom] = useState(100);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    (async () => {
      try {
        const definition = await request<any>(
          `/api/processes/definitions/draft/by-form/${formDefId}`,
        );
        if (definition?.process) {
          setPdId(definition.id);
          load(parseJsonValue(definition.process, null));
          return;
        }
      } catch {
        // A new form has no process draft yet.
      }
      load(null);
    })();
  }, [formDefId, load]);

  const { data: formDef } = useQuery<FormDefinition>({
    queryKey: ['form-def-for-flow', formDefId],
    queryFn: () =>
      request<FormDefinition>(`/api/forms/definitions/${formDefId}`),
    enabled: !!formDefId,
  });
  const formFields = flattenFormFields(parseJsonValue<any[]>(formDef?.schema, []));
  const issues = useMemo(() => validateProcessTree(process), [process]);

  const save = async (): Promise<void> => {
    setSaving(true);
    try {
      const response = await request<any>('/api/processes/definitions', {
        method: 'POST',
        data: { id: pdId, formDefId: Number(formDefId), process },
      });
      setPdId(response.id);
      onSaved?.(response);
      message.success('流程草稿已保存');
    } catch (error: any) {
      message.error(error?.message ?? '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const validate = (): void => {
    const first = issues[0];
    if (!first) {
      message.success('流程校验通过');
      return;
    }
    select(first.nodeId);
    message.error(first.message);
    window.setTimeout(() => {
      const target = viewportRef.current?.querySelector<HTMLElement>(
        `[data-node-id="${first.nodeId}"]`,
      );
      target?.scrollIntoView({
        behavior: 'smooth',
        block: 'center',
        inline: 'center',
      });
    }, 50);
  };

  const selected = selectedId ? find(process, selectedId) : null;

  return (
    <div
      className="process-designer"
      style={{
        height: embedded ? 'calc(100vh - 260px)' : '100vh',
        minHeight: embedded ? 560 : undefined,
      }}
    >
      <div className="process-designer__toolbar">
        <Space size={8}>
          <Button
            type="primary"
            icon={<SaveOutlined />}
            loading={saving}
            onClick={save}
          >
            保存草稿
          </Button>
          <Button icon={<CheckCircleOutlined />} onClick={validate}>
            流程校验
          </Button>
          {issues.length > 0 && (
            <span className="process-designer__issue-count">
              {issues.length} 项待配置
            </span>
          )}
        </Space>
        <Space.Compact className="process-designer__zoom">
          <Tooltip title="缩小">
            <Button
              aria-label="缩小画布"
              icon={<MinusOutlined />}
              disabled={zoom <= 60}
              onClick={() => setZoom((value) => Math.max(60, value - 10))}
            />
          </Tooltip>
          <span className="process-designer__zoom-value">{zoom}%</span>
          <Tooltip title="放大">
            <Button
              aria-label="放大画布"
              icon={<PlusOutlined />}
              disabled={zoom >= 140}
              onClick={() => setZoom((value) => Math.min(140, value + 10))}
            />
          </Tooltip>
          <Tooltip title="恢复 100%">
            <Button
              aria-label="恢复 100%"
              icon={<CompressOutlined />}
              onClick={() => setZoom(100)}
            />
          </Tooltip>
        </Space.Compact>
      </div>
      <div ref={viewportRef} className="process-designer__viewport">
        <ProcessTree zoom={zoom} />
      </div>
      <Drawer
        open={!!selected}
        size={440}
        onClose={() => select(null)}
        title={selected?.name}
        destroyOnHidden
      >
        {selected?.type === 'ROOT' && <RootNodeConfig node={selected} />}
        {selected?.type === 'APPROVAL' && (
          <ApprovalNodeConfig
            node={selected}
            formFields={formFields}
            rejectTargets={upstreamApprovals(process, selected.id)}
          />
        )}
        {selected?.type === 'CC' && <CcNodeConfig node={selected} />}
        {selected?.type === 'CONDITION' && (
          <ConditionNodeConfig node={selected} formFields={formFields} />
        )}
        {selected?.type === 'PARALLEL' && (
          <ParallelNodeConfig node={selected} />
        )}
        {selected?.type === 'BRANCH' && (
          <BranchNodeConfig node={selected} formFields={formFields} />
        )}
        {selected?.type === 'DELAY' && <DelayNodeConfig node={selected} />}
        {selected?.type === 'TRIGGER' && (
          <TriggerNodeConfig node={selected} formFields={formFields} />
        )}
      </Drawer>
    </div>
  );
}

export default function ProcessDesigner() {
  const routeParams = useParams();
  const navigate = useNavigate();
  const formDefId = String(routeParams.formDefId ?? '');
  useEffect(() => {
    navigate(
      formDefId
        ? `/approval/forms/${formDefId}/wizard?step=process`
        : '/approval/forms',
      { replace: true },
    );
  }, [formDefId, navigate]);
  return null;
}
