import { PageContainer } from '@ant-design/pro-components';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  List,
  Modal,
  Space,
  Steps,
  Switch,
  Tag,
  Typography,
  message,
  theme,
} from 'antd';
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  ExclamationCircleOutlined,
} from '@ant-design/icons';
import { history, request, useLocation, useParams } from '@umijs/max';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { FormRenderer } from '../../components/FormRenderer/FormRenderer';
import { formRegistry } from '../../registry/formRegistry';
import type { SchemaNode } from '../../registry/types';
import { FormDesignerSurface } from '../designer/form/FormDesigner';
import { ProcessDesignerSurface } from '../designer/process/ProcessDesigner';

type FormDefinition = {
  id: number;
  code: string;
  name: string;
  status: string;
  version: number;
  description?: string;
  schema?: any[] | string;
  settings?: Record<string, any> | string;
};

type ProcessDefinition = {
  id: number;
  formDefId: number;
  status: string;
  version: number;
  process?: any;
};

type PublishCheckStatus = 'success' | 'warning' | 'error';

type PublishCheckItem = {
  key: string;
  status: PublishCheckStatus;
  title: string;
  description: string;
};

const allSteps = [
  { key: 'basic', title: '表单属性' },
  { key: 'designer', title: '表单制作' },
  { key: 'process', title: '流程设计' },
  { key: 'publish', title: '预览发布' },
];

function parseJsonValue<T>(value: T | string | undefined, fallback: T): T {
  if (typeof value !== 'string') return value ?? fallback;
  try {
    return JSON.parse(value);
  } catch {
    return fallback;
  }
}

function getWorkflowEnabled(settings: Record<string, any> | string | undefined) {
  return !!parseJsonValue<Record<string, any>>(settings, {}).workflowEnabled;
}

function getNodeLabel(node: SchemaNode) {
  return node.label || formRegistry[node.type]?.label || node.type;
}

function enrichSchemaLabels(nodes: SchemaNode[]): SchemaNode[] {
  return nodes.map((node) => ({
    ...node,
    label: getNodeLabel(node),
    children: node.children ? enrichSchemaLabels(node.children) : undefined,
  }));
}

function isEmptyValue(value: any) {
  return (
    value == null ||
    value === '' ||
    (Array.isArray(value) && value.length === 0)
  );
}

function matchesDisplayCondition(
  condition: Record<string, any> | undefined,
  value: Record<string, any>,
) {
  if (!condition?.fieldId) return true;
  const sourceValue = value[condition.fieldId];
  const targetValue = condition.value;

  switch (condition.operator ?? 'eq') {
    case 'ne':
      return String(sourceValue ?? '') !== String(targetValue ?? '');
    case 'contains':
      return Array.isArray(sourceValue)
        ? sourceValue.map(String).includes(String(targetValue ?? ''))
        : String(sourceValue ?? '').includes(String(targetValue ?? ''));
    case 'empty':
      return isEmptyValue(sourceValue);
    case 'notEmpty':
      return !isEmptyValue(sourceValue);
    default:
      return String(sourceValue ?? '') === String(targetValue ?? '');
  }
}

function isVisibleNode(node: SchemaNode, value: Record<string, any>) {
  return !node.props?.hidden && matchesDisplayCondition(node.props?.displayCondition, value);
}

function collectValidationErrors(nodes: SchemaNode[], value: Record<string, any>) {
  const errors: string[] = [];
  nodes.forEach((node) => {
    if (!isVisibleNode(node, value)) return;
    const currentValue = value[node.id] ?? node.props?.defaultValue;
    const label = getNodeLabel(node);
    if (node.props?.required && isEmptyValue(currentValue)) {
      errors.push(node.props?.validationMessage || `请填写：${label}`);
    }
    if (
      !isEmptyValue(currentValue) &&
      node.props?.minLength &&
      String(currentValue).length < node.props.minLength
    ) {
      errors.push(`${label} 不能少于 ${node.props.minLength} 个字符`);
    }
    if (!isEmptyValue(currentValue) && node.props?.pattern) {
      try {
        const regex = new RegExp(node.props.pattern);
        if (!regex.test(String(currentValue))) {
          errors.push(node.props?.validationMessage || `${label} 格式不正确`);
        }
      } catch {
        errors.push(`${label} 的正则表达式无效`);
      }
    }
    if (node.children) {
      errors.push(...collectValidationErrors(node.children, value));
    }
  });
  return errors;
}

function collectOptionErrors(nodes: SchemaNode[]) {
  const optionTypes = new Set(['select', 'multi_select']);
  const errors: string[] = [];
  nodes.forEach((node) => {
    if (
      optionTypes.has(node.type) &&
      (!Array.isArray(node.props?.options) || node.props.options.length === 0)
    ) {
      errors.push(getNodeLabel(node));
    }
    if (node.children) {
      errors.push(...collectOptionErrors(node.children));
    }
  });
  return errors;
}

function collectUploadWarnings(nodes: SchemaNode[]) {
  const warnings: string[] = [];
  nodes.forEach((node) => {
    if (node.type === 'file_upload' && !node.props?.accept) {
      warnings.push(getNodeLabel(node));
    }
    if (node.children) {
      warnings.push(...collectUploadWarnings(node.children));
    }
  });
  return warnings;
}

function hasApprovalNode(node: any): boolean {
  if (!node) return false;
  if (node.type === 'APPROVAL') return true;
  if (hasApprovalNode(node.children)) return true;
  return Array.isArray(node.branchs) && node.branchs.some(hasApprovalNode);
}

function getSteps(workflowEnabled: boolean) {
  return workflowEnabled
    ? allSteps
    : allSteps.filter((item) => item.key !== 'process');
}

function stepFromSearch(search: string, steps: typeof allSteps) {
  const key = new URLSearchParams(search).get('step') ?? 'basic';
  const index = steps.findIndex((item) => item.key === key);
  return index >= 0 ? index : 0;
}

export default function FormManagementWizard() {
  const params = useParams();
  const location = useLocation();
  const qc = useQueryClient();
  const [form] = Form.useForm();
  const { token } = theme.useToken();
  const [previewValue, setPreviewValue] = useState<Record<string, any>>({});
  const id = params.id;
  const isNew = !id || id === 'new';
  const formId = isNew ? null : Number(id);

  const { data: definition } = useQuery<FormDefinition>({
    queryKey: ['form-management-definition', formId],
    queryFn: () => request<FormDefinition>(`/api/forms/definitions/${formId}`),
    enabled: !!formId,
  });

  const watchedWorkflowEnabled = Form.useWatch('workflowEnabled', form);
  const workflowEnabled =
    watchedWorkflowEnabled ?? getWorkflowEnabled(definition?.settings);
  const steps = getSteps(!!workflowEnabled);
  const current = stepFromSearch(location.search, steps);
  const currentKey = steps[current].key;

  const { data: processDefinition } = useQuery<ProcessDefinition | null>({
    queryKey: ['form-management-process', formId],
    queryFn: async () => {
      try {
        return await request<ProcessDefinition>(`/api/processes/definitions/draft/by-form/${formId}`);
      } catch {
        return null;
      }
    },
    enabled: !!formId && !!workflowEnabled,
  });

  const schema = useMemo(
    () => parseJsonValue<SchemaNode[]>(definition?.schema, []),
    [definition?.schema],
  );
  const previewSchema = useMemo(() => enrichSchemaLabels(schema), [schema]);
  const processTree = useMemo(
    () => parseJsonValue<any>(processDefinition?.process, null),
    [processDefinition?.process],
  );
  const approvalNodeReady = hasApprovalNode(processTree);
  const optionErrors = useMemo(() => collectOptionErrors(schema), [schema]);
  const uploadWarnings = useMemo(() => collectUploadWarnings(schema), [schema]);
  const publishChecks = useMemo<PublishCheckItem[]>(() => {
    const checks: PublishCheckItem[] = [
      {
        key: 'definition',
        status: definition ? 'success' : 'error',
        title: '表单定义已保存',
        description: definition ? '已读取当前草稿配置' : '请先保存表单属性',
      },
      {
        key: 'name',
        status: definition?.name?.trim() ? 'success' : 'error',
        title: '表单名称已填写',
        description: definition?.name?.trim() || '表单名称不能为空',
      },
      {
        key: 'schema',
        status: schema.length > 0 ? 'success' : 'error',
        title: '至少包含 1 个组件',
        description:
          schema.length > 0
            ? `当前共 ${schema.length} 个组件`
            : '请返回表单制作添加组件',
      },
      {
        key: 'options',
        status: optionErrors.length === 0 ? 'success' : 'error',
        title: '选项类组件配置完整',
        description:
          optionErrors.length === 0
            ? '单选/多选组件均已配置选项'
            : `${optionErrors.join('、')} 缺少选项`,
      },
      {
        key: 'upload',
        status: uploadWarnings.length === 0 ? 'success' : 'warning',
        title: '上传组件限制',
        description:
          uploadWarnings.length === 0
            ? '上传组件未发现明显风险'
            : `${uploadWarnings.join('、')} 未限制文件类型，发布后仍可使用`,
      },
    ];

    if (workflowEnabled) {
      checks.push({
        key: 'workflow',
        status:
          processDefinition?.id && approvalNodeReady ? 'success' : 'error',
        title: '审批流程已配置',
        description:
          processDefinition?.id && approvalNodeReady
            ? '已配置至少一个审批节点'
            : '启用审批流程后，必须至少配置一个审批节点',
      });
    } else {
      checks.push({
        key: 'workflow',
        status: 'success',
        title: '提交后行为明确',
        description: '未启用审批流程，用户提交后直接完成',
      });
    }

    return checks;
  }, [
    approvalNodeReady,
    definition,
    optionErrors,
    processDefinition?.id,
    schema.length,
    uploadWarnings,
    workflowEnabled,
  ]);
  const publishErrors = publishChecks.filter((item) => item.status === 'error');
  const canPublish = publishErrors.length === 0;

  useEffect(() => {
    if (!definition) return;
    form.setFieldsValue({
      code: definition.code,
      name: definition.name,
      workflowEnabled: getWorkflowEnabled(definition.settings),
    });
  }, [definition, form]);

  const goStep = (key: string, nextId = formId) => {
    if (!nextId) return;
    history.push(`/approval/forms/${nextId}/wizard?step=${key}`);
  };

  const handlePreviewSubmit = () => {
    const validationErrors = collectValidationErrors(schema, previewValue);
    if (validationErrors.length > 0) {
      message.error(validationErrors[0]);
      return;
    }
    message.success('模拟提交校验通过');
  };

  const saveBasic = useMutation({
    mutationFn: async () => {
      const values = await form.validateFields();
      const settings = {
        ...parseJsonValue<Record<string, any>>(definition?.settings, {}),
        workflowEnabled: !!values.workflowEnabled,
      };
      return request<FormDefinition>('/api/forms/definitions', {
        method: 'POST',
        data: {
          id: formId,
          code: values.code,
          name: values.name,
          schema: parseJsonValue(definition?.schema, []),
          settings,
        },
      });
    },
    onSuccess: (res) => {
      message.success(
        definition?.status === 'PUBLISHED'
          ? '已转为草稿，请完成配置后重新发布'
          : '表单属性已保存',
      );
      qc.invalidateQueries({ queryKey: ['form-management-definition'] });
      goStep('designer', res.id);
    },
  });

  const saveDraft = useMutation({
    mutationFn: () => {
      if (!definition) throw new Error('请先保存表单属性');
      return request<FormDefinition>('/api/forms/definitions', {
        method: 'POST',
        data: {
          id: definition.id,
          code: definition.code,
          name: definition.name,
          description: definition.description ?? '',
          schema,
          settings: parseJsonValue(definition.settings, {}),
        },
      });
    },
    onSuccess: () => {
      message.success('草稿已保存');
      qc.invalidateQueries({ queryKey: ['form-management-definition'] });
    },
    onError: (error: any) => message.error(error?.message ?? '保存失败'),
  });

  const handleWorkflowEnabledChange = (checked: boolean) => {
    if (checked) {
      form.setFieldValue('workflowEnabled', true);
      return;
    }
    if (!formId || !processDefinition?.id) {
      form.setFieldValue('workflowEnabled', false);
      return;
    }
    Modal.confirm({
      title: '关闭审批流程',
      content: '关闭后已配置的流程将被清除，是否继续？',
      okText: '继续关闭',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: async () => {
        await request(`/api/processes/definitions/by-form/${formId}`, {
          method: 'DELETE',
        });
        form.setFieldValue('workflowEnabled', false);
        qc.invalidateQueries({ queryKey: ['form-management-process'] });
        message.success('已关闭并清除流程配置');
      },
      onCancel: () => {
        form.setFieldValue('workflowEnabled', true);
      },
    });
  };

  const publishAll = useMutation({
    mutationFn: async () => {
      if (!formId) throw new Error('请先保存表单属性');
      if (workflowEnabled) {
        if (!processDefinition?.id) throw new Error('请先保存流程设计');
        const process = parseJsonValue<any>(processDefinition.process, null);
        if (!hasApprovalNode(process)) {
          throw new Error('启用审批流程后，至少需要配置一个审批节点');
        }
      }
      await request(`/api/forms/definitions/${formId}/publish`, { method: 'POST' });
      if (workflowEnabled && processDefinition?.id) {
        await request(`/api/processes/definitions/${processDefinition.id}/publish`, { method: 'POST' });
      }
    },
    onSuccess: () => {
      message.success(workflowEnabled ? '表单和流程已发布' : '表单已发布');
      qc.invalidateQueries({ queryKey: ['form-management-definition'] });
      qc.invalidateQueries({ queryKey: ['form-management-process'] });
    },
    onError: (error: any) => message.error(error?.message ?? '发布失败'),
  });

  const confirmPublish = () => {
    if (!canPublish) {
      message.error(publishErrors[0]?.description ?? '发布检查未通过');
      return;
    }
    Modal.confirm({
      title: '确认发布',
      content: workflowEnabled
        ? '发布后用户提交将进入审批流程，确认发布？'
        : '发布后用户提交将直接完成，确认发布？',
      okText: '确认发布',
      cancelText: '取消',
      onOk: () => publishAll.mutateAsync(),
    });
  };

  const renderBasic = () => (
    <Card>
      <Form
        form={form}
        layout="vertical"
        initialValues={{
          code: definition?.code ?? `form_${Date.now()}`,
          name: definition?.name ?? '未命名表单',
          workflowEnabled: getWorkflowEnabled(definition?.settings),
        }}
      >
        <Form.Item label="表单名称" name="name" rules={[{ required: true, message: '请输入表单名称' }]}>
          <Input placeholder="例如：请假申请" />
        </Form.Item>
        <Form.Item label="表单编码" name="code" rules={[{ required: true, message: '请输入表单编码' }]}>
          <Input disabled={!!formId} placeholder="例如：leave_request" />
        </Form.Item>
        <Form.Item
          label="是否启用审批流程"
          name="workflowEnabled"
          valuePropName="checked"
        >
          <Switch onChange={handleWorkflowEnabledChange} />
        </Form.Item>
      </Form>
      <Space>
        <Button type="primary" loading={saveBasic.isPending} onClick={() => saveBasic.mutate()}>
          保存并进入表单制作
        </Button>
        <Button onClick={() => history.push('/approval/forms')}>返回列表</Button>
      </Space>
    </Card>
  );

  const renderPublish = () => {
    const checkIcon = (status: PublishCheckStatus) => {
      if (status === 'success') {
        return <CheckCircleOutlined style={{ color: token.colorSuccess }} />;
      }
      if (status === 'warning') {
        return <ExclamationCircleOutlined style={{ color: token.colorWarning }} />;
      }
      return <CloseCircleOutlined style={{ color: token.colorError }} />;
    };

    return (
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'minmax(420px, 1fr) 360px',
          gap: 24,
          alignItems: 'start',
        }}
      >
        <Card title="手机端预览">
          <div
            style={{
              display: 'flex',
              justifyContent: 'center',
              padding: '8px 0 16px',
            }}
          >
            <div
              style={{
                width: 390,
                maxWidth: '100%',
                minHeight: 640,
                maxHeight: 'calc(100vh - 260px)',
                overflowY: 'auto',
                border: `1px solid ${token.colorBorder}`,
                borderRadius: 28,
                background: token.colorBgLayout,
                padding: 12,
                boxShadow: token.boxShadowSecondary,
              }}
            >
              <div
                style={{
                  minHeight: 616,
                  borderRadius: 20,
                  background: token.colorBgContainer,
                  padding: 16,
                }}
              >
                <Typography.Title level={4} style={{ marginTop: 0 }}>
                  {definition?.name ?? '未命名表单'}
                </Typography.Title>
                {definition?.description && (
                  <Typography.Paragraph type="secondary">
                    {definition.description}
                  </Typography.Paragraph>
                )}
                {previewSchema.length > 0 ? (
                  <FormRenderer
                    schema={previewSchema}
                    mode="runtime-fill"
                    value={previewValue}
                    onChange={setPreviewValue}
                  />
                ) : (
                  <Alert
                    type="warning"
                    showIcon
                    message="暂无组件"
                    description="请返回表单制作添加至少一个组件。"
                  />
                )}
                <Button
                  block
                  type="primary"
                  style={{ marginTop: 16 }}
                  disabled={previewSchema.length === 0}
                  onClick={handlePreviewSubmit}
                >
                  模拟提交
                </Button>
              </div>
            </div>
          </div>
        </Card>

        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Card title="发布检查">
            <List
              dataSource={publishChecks}
              renderItem={(item) => (
                <List.Item>
                  <List.Item.Meta
                    avatar={checkIcon(item.status)}
                    title={item.title}
                    description={item.description}
                  />
                </List.Item>
              )}
            />
          </Card>

          <Card title="发布设置">
            <Descriptions column={1} size="small">
              <Descriptions.Item label="表单状态">
                {definition?.status ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label="审批流程">
                {workflowEnabled ? (
                  <Tag color="blue">启用</Tag>
                ) : (
                  <Tag>未启用</Tag>
                )}
              </Descriptions.Item>
              <Descriptions.Item label="提交后行为">
                {workflowEnabled ? '提交后进入审批' : '提交成功后直接完成'}
              </Descriptions.Item>
              <Descriptions.Item label="可见范围">所有用户</Descriptions.Item>
              {workflowEnabled && (
                <Descriptions.Item label="流程状态">
                  {processDefinition?.status ?? '未保存'}
                </Descriptions.Item>
              )}
            </Descriptions>
          </Card>

          <Card>
            {publishErrors.length > 0 && (
              <Alert
                type="error"
                showIcon
                style={{ marginBottom: 12 }}
                message="发布检查未通过"
                description={publishErrors[0].description}
              />
            )}
            <Space wrap>
              <Button onClick={() => goStep(workflowEnabled ? 'process' : 'designer')}>
                上一步
              </Button>
              <Button loading={saveDraft.isPending} onClick={() => saveDraft.mutate()}>
                保存草稿
              </Button>
              <Button
                type="primary"
                disabled={!canPublish}
                loading={publishAll.isPending}
                onClick={confirmPublish}
              >
                发布
              </Button>
            </Space>
          </Card>
        </Space>
      </div>
    );
  };

  return (
    <PageContainer title={false}>
      <Card style={{ marginBottom: 16 }}>
        <Steps
          current={current}
          items={steps}
          onChange={(index) => {
            if (formId) goStep(steps[index].key);
          }}
        />
      </Card>
      {currentKey === 'basic' && renderBasic()}
      {currentKey === 'designer' && formId && <FormDesignerSurface formId={formId} embedded onSaved={() => qc.invalidateQueries({ queryKey: ['form-management-definition'] })} />}
      {currentKey === 'process' && workflowEnabled && formId && <ProcessDesignerSurface formDefId={formId} embedded onSaved={() => qc.invalidateQueries({ queryKey: ['form-management-process'] })} />}
      {currentKey === 'publish' && renderPublish()}
    </PageContainer>
  );
}
