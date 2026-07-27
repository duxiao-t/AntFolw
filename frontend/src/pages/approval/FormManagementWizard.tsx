import { PageContainer } from '@ant-design/pro-components';
import {
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  Modal,
  Result,
  Space,
  Steps,
  Switch,
  message,
} from 'antd';
import { history, request, useLocation, useParams } from '@umijs/max';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { FormDesignerSurface } from '../designer/form/FormDesigner';
import { ProcessDesignerSurface } from '../designer/process/ProcessDesigner';

type FormDefinition = {
  id: number;
  code: string;
  name: string;
  status: string;
  version: number;
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

  const renderPublish = () => (
    <Card>
      <Descriptions bordered column={1}>
        <Descriptions.Item label="表单名称">{definition?.name ?? '-'}</Descriptions.Item>
        <Descriptions.Item label="表单编码">{definition?.code ?? '-'}</Descriptions.Item>
        <Descriptions.Item label="表单状态">{definition?.status ?? '-'}</Descriptions.Item>
        <Descriptions.Item label="审批流程">{workflowEnabled ? '启用' : '未启用'}</Descriptions.Item>
        {workflowEnabled && (
          <Descriptions.Item label="流程状态">{processDefinition?.status ?? '未保存'}</Descriptions.Item>
        )}
        <Descriptions.Item label="字段数量">{parseJsonValue<any[]>(definition?.schema, []).length}</Descriptions.Item>
      </Descriptions>
      <Result
        status={definition && (!workflowEnabled || processDefinition) ? 'info' : 'warning'}
        title="发布前检查"
        subTitle={
          workflowEnabled
            ? definition && processDefinition
              ? '确认无误后发布表单和流程'
              : '请先完成表单制作并保存流程设计'
            : '当前表单未启用审批流程，发布后用户提交将直接完成'
        }
        extra={[
          workflowEnabled && (
            <Button key="process" onClick={() => goStep('process')}>返回流程设计</Button>
          ),
          <Button key="publish" type="primary" loading={publishAll.isPending} onClick={() => publishAll.mutate()}>
            发布
          </Button>,
        ].filter(Boolean)}
      />
    </Card>
  );

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
