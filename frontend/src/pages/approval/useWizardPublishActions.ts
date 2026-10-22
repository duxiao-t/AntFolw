import { useMutation, useQueryClient } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { message, Modal } from 'antd';
import type { FormInstance } from 'antd';
import type { SchemaNode } from '../../registry/types';
import type { ProcessDefinition } from './wizardTypes';
import { hasApprovalNode, parseJsonValue } from './wizardValidation';

type Params = {
  form: FormInstance;
  formId: number | null;
  definition?: AntFlowAPI.FormDefinition;
  schema: SchemaNode[];
  workflowEnabled: boolean;
  processDefinition?: ProcessDefinition | null;
  canPublish: boolean;
  firstPublishError?: string;
};

/** 发布与流程开关：发布前先把画布内容落库，关闭审批流程要先删掉服务端草稿。 */
export function useWizardPublishActions({
  form,
  formId,
  definition,
  schema,
  workflowEnabled,
  processDefinition,
  canPublish,
  firstPublishError,
}: Params) {
  const qc = useQueryClient();

  const publishAll = useMutation({
    mutationFn: async () => {
      if (!formId) throw new Error('请先保存表单属性');
      // 先落库再发布：不然画布上没保存的改动会连同发布一起被丢掉，而且"已发布的表单再点发布"
      // 在后端是空操作——用户会以为发布成功了，其实什么都没发生。
      const body: AntFlowAPI.SaveBody = {
        id: formId,
        code: definition?.code,
        name: definition?.name,
        description: definition?.description ?? '',
        schema,
        settings: parseJsonValue<Record<string, any>>(definition?.settings, {}),
      };
      const saved = await request<AntFlowAPI.FormDefinition>(
        '/api/forms/definitions',
        { method: 'POST', data: body },
      );
      // 内容没变时后端会原样返回 PUBLISHED。但**流程**可能还有未发布的草稿改动，这时不能
      // 直接返回——否则"只改了流程"的发布会永远停在草稿，界面却提示发布成功。
      if (saved?.status === 'PUBLISHED'
          && !(workflowEnabled && processDefinition?.status !== 'PUBLISHED')) return;
      if (workflowEnabled) {
        if (!processDefinition?.id) throw new Error('请先保存流程设计');
        const process = parseJsonValue<any>(processDefinition.process, null);
        if (!hasApprovalNode(process)) {
          throw new Error('启用审批流程后，至少需要配置一个审批节点');
        }
      }
      if (workflowEnabled && processDefinition?.id) {
        await request(`/api/forms/definitions/${formId}/publish-with-process`, {
          method: 'POST',
          data: { processDefinitionId: processDefinition.id },
        });
      } else {
        await request(`/api/forms/definitions/${formId}/publish`, {
          method: 'POST',
        });
      }
    },
    onSuccess: () => {
      message.success(workflowEnabled ? '表单和流程已发布' : '表单已发布');
      qc.invalidateQueries({ queryKey: ['form-management-definition'] });
      qc.invalidateQueries({ queryKey: ['form-management-process'] });
    },
    onError: (error: any) => {
      // HTTP errors are already rendered once by the global request handler.
      if (!error?.response) message.error(error?.message ?? '发布失败');
    },
  });

  const confirmPublish = () => {
    if (!canPublish) {
      message.error(firstPublishError ?? '发布检查未通过');
      return;
    }
    Modal.confirm({
      title: '确认发布',
      content: workflowEnabled
        ? '发布后用户提交将进入审批流程，确认发布？'
        : '发布后用户提交将直接完成，确认发布？',
      okText: '确认发布',
      cancelText: '取消',
      onOk: async () => {
        try {
          await publishAll.mutateAsync();
        } catch {
          // React Query onError already shows the backend business message.
        }
      },
    });
  };

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

  return { publishAll, confirmPublish, handleWorkflowEnabledChange };
}

export type WizardPublishActions = ReturnType<typeof useWizardPublishActions>;
