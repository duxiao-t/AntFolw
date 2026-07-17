import { PageContainer } from '@ant-design/pro-components';
import { Button, Card, Descriptions, Form, Input, Result, Space, Steps, message } from 'antd';
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

const steps = [
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

function stepFromSearch(search: string) {
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
  const current = stepFromSearch(location.search);
  const currentKey = steps[current].key;
  const formId = isNew ? null : Number(id);

  const { data: definition } = useQuery<FormDefinition>({
    queryKey: ['form-management-definition', formId],
    queryFn: () => request<FormDefinition>(`/api/forms/definitions/${formId}`),
    enabled: !!formId,
  });

  const { data: processDefinition } = useQuery<ProcessDefinition | null>({
    queryKey: ['form-management-process', formId],
    queryFn: async () => {
      try {
        return await request<ProcessDefinition>(`/api/processes/definitions/draft/by-form/${formId}`);
      } catch {
        return null;
      }
    },
    enabled: !!formId,
  });

  useEffect(() => {
    if (!definition) return;
    form.setFieldsValue({ code: definition.code, name: definition.name });
  }, [definition, form]);

  const goStep = (key: string, nextId = formId) => {
    if (!nextId) return;
    history.push(`/approval/forms/${nextId}/wizard?step=${key}`);
  };

  const saveBasic = useMutation({
    mutationFn: async () => {
      const values = await form.validateFields();
      return request<FormDefinition>('/api/forms/definitions', {
        method: 'POST',
        data: {
          id: formId,
          code: values.code,
          name: values.name,
          schema: parseJsonValue(definition?.schema, []),
          settings: parseJsonValue(definition?.settings, {}),
        },
      });
    },
    onSuccess: (res) => {
      message.success('表单属性已保存');
      qc.invalidateQueries({ queryKey: ['form-management-definition'] });
      goStep('designer', res.id);
    },
  });

  const publishAll = useMutation({
    mutationFn: async () => {
      if (!formId) throw new Error('请先保存表单属性');
      if (!processDefinition?.id) throw new Error('请先保存流程设计');
      await request(`/api/forms/definitions/${formId}/publish`, { method: 'POST' });
      await request(`/api/processes/definitions/${processDefinition.id}/publish`, { method: 'POST' });
    },
    onSuccess: () => {
      message.success('表单和流程已发布');
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
        }}
      >
        <Form.Item label="表单名称" name="name" rules={[{ required: true, message: '请输入表单名称' }]}>
          <Input placeholder="例如：请假申请" />
        </Form.Item>
        <Form.Item label="表单编码" name="code" rules={[{ required: true, message: '请输入表单编码' }]}>
          <Input disabled={!!formId} placeholder="例如：leave_request" />
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
        <Descriptions.Item label="流程状态">{processDefinition?.status ?? '未保存'}</Descriptions.Item>
        <Descriptions.Item label="字段数量">{parseJsonValue<any[]>(definition?.schema, []).length}</Descriptions.Item>
      </Descriptions>
      <Result
        status={definition && processDefinition ? 'info' : 'warning'}
        title="发布前检查"
        subTitle={definition && processDefinition ? '确认无误后发布表单和流程' : '请先完成表单制作并保存流程设计'}
        extra={[
          <Button key="process" onClick={() => goStep('process')}>返回流程设计</Button>,
          <Button key="publish" type="primary" loading={publishAll.isPending} onClick={() => publishAll.mutate()}>
            发布
          </Button>,
        ]}
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
      {currentKey === 'process' && formId && <ProcessDesignerSurface formDefId={formId} embedded onSaved={() => qc.invalidateQueries({ queryKey: ['form-management-process'] })} />}
      {currentKey === 'publish' && renderPublish()}
    </PageContainer>
  );
}
