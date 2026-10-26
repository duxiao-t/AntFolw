import { useMutation, useQueryClient } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { message } from 'antd';
import type { FormInstance } from 'antd';
import type { SchemaNode } from '../../registry/types';
import type { FormGrant, FormMaintainers } from './wizardTypes';
import { parseJsonValue } from './wizardValidation';

type Params = {
  form: FormInstance;
  formId: number | null;
  isNew: boolean;
  isAdmin: boolean;
  canManageGrants: boolean;
  allCompanyRoleId?: number;
  definition?: AntFlowAPI.FormDefinition;
  schema: SchemaNode[];
  goStep: (key: string, nextId?: number) => void;
};

/** 写操作：保存表单属性（新建时连带落授权/维护人员）与保存草稿。 */
export function useWizardSaveActions({
  form,
  formId,
  isNew,
  isAdmin,
  canManageGrants,
  allCompanyRoleId,
  definition,
  schema,
  goStep,
}: Params) {
  const qc = useQueryClient();

  const saveBasic = useMutation({
    mutationFn: async () => {
      const values = await form.validateFields();
      const settings = {
        ...parseJsonValue<Record<string, any>>(definition?.settings, {}),
        workflowEnabled: !!values.workflowEnabled,
        businessNumber: values.businessNumber,
      };
      const body: AntFlowAPI.SaveBody = {
        id: formId ?? undefined,
        code: values.code,
        name: values.name,
        schema,
        settings,
      };
      const saved = await request<AntFlowAPI.FormDefinition>(
        '/api/forms/definitions',
        { method: 'POST', data: body },
      );
      if (!isNew || !canManageGrants) return saved;

      try {
        const latestGrant = await request<FormGrant>(`/api/forms/${saved.id}/grants`);
        const latestMaintainers = await request<FormMaintainers>(
          `/api/forms/${saved.id}/maintainers`,
        );
        if (!latestGrant || !latestMaintainers
            || latestGrant.version !== latestMaintainers.version) {
          throw new Error('权限设置已被修改，请刷新后重试');
        }
        const userIds = Array.isArray(values.userIds)
          ? values.userIds
          : latestGrant.userIds;
        let roleIds = Array.isArray(values.roleIds)
          ? values.roleIds
          : latestGrant.roleIds;
        if (isAdmin && allCompanyRoleId) {
          roleIds = values.allCompany
            ? [...new Set([...roleIds, allCompanyRoleId])]
            : roleIds.filter((id: number) => id !== allCompanyRoleId);
        }
        const departmentIds = Array.isArray(values.departmentIds)
          ? values.departmentIds
          : latestGrant.departmentIds;
        const maintainerIds = Array.isArray(values.maintainerIds)
          ? values.maintainerIds
          : latestMaintainers.userIds;
        const updatedGrant = await request<FormGrant>(
          `/api/forms/${saved.id}/grants`, {
            method: 'PUT',
            data: {
              userIds,
              roleIds,
              departmentIds,
              version: latestGrant.version,
            },
          },
        );
        await request<FormMaintainers>(`/api/forms/${saved.id}/maintainers`, {
          method: 'PUT',
          data: { userIds: maintainerIds, version: updatedGrant.version },
        });
      } catch (error: any) {
        const grantError =
          error instanceof Error
            ? error
            : new Error(error?.message ?? '表单权限设置保存失败');
        (grantError as any).formId = saved.id;
        throw grantError;
      }
      return saved;
    },
    onSuccess: (res) => {
      message.success(
        definition?.status === 'PUBLISHED'
          ? '已转为草稿，请完成配置后重新发布'
          : '表单属性已保存',
      );
      qc.invalidateQueries({ queryKey: ['form-management-definition'] });
      qc.invalidateQueries({ queryKey: ['form-management-grant'] });
      qc.invalidateQueries({ queryKey: ['form-management-maintainers'] });
      goStep('designer', res.id);
    },
    onError: (error: any) => {
      if (error?.formId) {
        qc.invalidateQueries({
          queryKey: ['form-management-definition', error.formId],
        });
        qc.invalidateQueries({
          queryKey: ['form-management-grant', error.formId],
        });
        qc.invalidateQueries({
          queryKey: ['form-management-maintainers', error.formId],
        });
        goStep('basic', error.formId);
      }
      message.error(error?.message ?? '保存失败');
    },
  });

  const saveDraft = useMutation({
    mutationFn: () => {
      if (!definition) throw new Error('请先保存表单属性');
      const body: AntFlowAPI.SaveBody = {
        id: definition.id,
        code: definition.code,
        name: definition.name,
        description: definition.description ?? '',
        schema,
        settings: parseJsonValue(definition.settings, {}),
      };
      return request<AntFlowAPI.FormDefinition>('/api/forms/definitions', {
        method: 'POST',
        data: body,
      });
    },
    onSuccess: () => {
      message.success('草稿已保存');
      qc.invalidateQueries({ queryKey: ['form-management-definition'] });
    },
    onError: (error: any) => message.error(error?.message ?? '保存失败'),
  });

  return { saveBasic, saveDraft };
}

export type WizardSaveActions = ReturnType<typeof useWizardSaveActions>;
