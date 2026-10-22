import { PageContainer } from '@ant-design/pro-components';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { history, request, useLocation, useModel, useParams } from '@umijs/max';
import { Card, Form, Result, Steps } from 'antd';
import { useEffect, useMemo, useRef } from 'react';
import { CAPABILITY, hasCapability } from '../../authz';
import type { SchemaNode } from '../../registry/types';
import { FormDesignerSurface } from '../designer/form/FormDesigner';
import { useFormDesignerStore } from '../designer/form/useFormDesignerStore';
import { ProcessDesignerSurface } from '../designer/process/ProcessDesigner';
import type { TreeNode } from '../designer/process/types';
import {
  flattenFormFields,
  validateProcessTree,
} from '../designer/process/validation';
import FormBasicStep from './FormBasicStep';
import FormPublishStep from './FormPublishStep';
import { useWizardPermissions } from './useWizardPermissions';
import { useWizardPublishActions } from './useWizardPublishActions';
import { useWizardSaveActions } from './useWizardSaveActions';
import type { ProcessDefinition } from './wizardTypes';
import {
  buildPublishChecks,
  collectOptionErrors,
  collectUploadWarnings,
  enrichSchemaLabels,
  getSteps,
  getWorkflowEnabled,
  hasApprovalNode,
  parseJsonValue,
  stepFromSearch,
} from './wizardValidation';

export default function FormManagementWizard() {
  const params = useParams();
  const location = useLocation();
  const { initialState } = useModel('@@initialState');
  const qc = useQueryClient();
  const [form] = Form.useForm();
  const id = params.id;
  const isNew = !id || id === 'new';
  const formId = isNew ? null : Number(id);
  const currentUser = initialState?.currentUser as any;
  const isAdmin = (currentUser?.roles ?? []).includes('admin');
  const canManageGrants = hasCapability(currentUser, CAPABILITY.formAuthorizationManage);
  // 新建表单需要 form:definition:manage，但 /approval/forms/:id/wizard 这条路由只要求
  // canDesigner（表单管理员**或**流程管理员）。于是只有 workflow:definition:manage 的人
  // 能打开 /designer/form/new，一路填完，最后 POST /api/forms/definitions 才 403——"存不了"。
  const canCreateForm = isAdmin || hasCapability(currentUser, CAPABILITY.formDefinitionManage);

  // 回填哨兵放在壳里：它们必须跨步骤切换存活。步骤组件卸载会重建这些 ref，
  // 于是回到某一步时又按服务端旧值回填一次，把用户没保存的编辑冲掉。
  const syncedGrantKey = useRef<string | null>(null);
  const syncedDefinitionId = useRef<number | null>(null);
  const syncedMaintainerFormId = useRef<number | null>(null);
  // 保存授权用的乐观锁版本。干净状态下跟随服务端；有未保存编辑就冻结——否则窗口焦点触发的
  // 刷新会把 version 换成新值，而我们发上去的还是旧字段值，等于**静默覆盖**别人的改动。
  const grantVersionRef = useRef<number | null>(null);

  const { data: definition } = useQuery<AntFlowAPI.FormDefinition>({
    queryKey: ['form-management-definition', formId],
    queryFn: () =>
      request<AntFlowAPI.FormDefinition>(`/api/forms/definitions/${formId}`),
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
        return await request<ProcessDefinition>(
          `/api/processes/definitions/draft/by-form/${formId}`,
        );
      } catch {
        return null;
      }
    },
    enabled: !!formId && !!workflowEnabled,
  });

  // 组件被复用到另一个 formId（例如 N → 后退 → new）时必须清干净：Form 里还留着上一张表单的
  // 人员/角色/部门，三个 synced ref 也非空 → grantDirty 恒为真 → 新表单的授权不会回填，
  // 保存时反而把上一张表单的授权写过去。
  const previousFormId = useRef<string | number | null>(formId);
  useEffect(() => {
    if (previousFormId.current === formId) return;
    previousFormId.current = formId;
    syncedGrantKey.current = null;
    syncedDefinitionId.current = null;
    syncedMaintainerFormId.current = null;
    grantVersionRef.current = null;
    form.resetFields();
  }, [formId, form]);

  const permissions = useWizardPermissions({
    form,
    formId,
    isNew,
    isAdmin,
    canManageGrants,
    currentUser,
    syncedGrantKey,
    syncedMaintainerFormId,
    grantVersionRef,
  });

  // 表单内容的唯一真相是设计器的 store（用户正在编辑的那份）。definition.schema 是"上次保存的
  // 状态"——拿它当真相会让「改完内容保存/发布」把编辑悄悄丢掉，而且后端看什么都没变，
  // 于是不降级为草稿、版本号也不动。
  const designerSchema = useFormDesignerStore((s) => s.schema);
  const designerFormId = useFormDesignerStore((s) => s.loadedFormId);
  const loadDesignerSchema = useFormDesignerStore((s) => s.loadSchema);
  const storeHoldsThisForm = designerFormId !== null && designerFormId === String(formId ?? 'new');
  const schema = useMemo(
    () => (storeHoldsThisForm
      ? designerSchema
      : parseJsonValue<SchemaNode[]>(definition?.schema, [])),
    [definition?.schema, designerSchema, storeHoldsThisForm],
  );
  // 不打开设计器也要有 store：进到这一页就把服务端那份灌进去，之后的保存都从 store 取。
  useEffect(() => {
    if (!definition) return;
    if (designerFormId === String(definition.id)) return;
    loadDesignerSchema(parseJsonValue<SchemaNode[]>(definition.schema, []), String(definition.id));
  }, [definition, designerFormId, loadDesignerSchema]);

  // 表单属性回填只做一次（键是表单 id）：与权限回填同一个道理，数据刷新不该覆盖用户的编辑。
  useEffect(() => {
    if (definition && syncedDefinitionId.current !== definition.id) {
      syncedDefinitionId.current = definition.id ?? null;
      form.setFieldsValue({
        code: definition.code,
        name: definition.name,
        workflowEnabled: getWorkflowEnabled(definition.settings),
        businessNumber: parseJsonValue<Record<string, any>>(definition.settings, {}).businessNumber,
      });
    }
  }, [definition, form]);

  const previewSchema = useMemo(() => enrichSchemaLabels(schema), [schema]);
  const processTree = useMemo(
    () => parseJsonValue<any>(processDefinition?.process, null),
    [processDefinition?.process],
  );
  const approvalNodeReady = hasApprovalNode(processTree);
  const processFormFields = useMemo(() => flattenFormFields(schema), [schema]);
  const processIssues = useMemo(
    () =>
      processTree
        ? validateProcessTree(processTree as TreeNode, processFormFields, true)
        : [],
    [processFormFields, processTree],
  );
  const optionErrors = useMemo(() => collectOptionErrors(schema), [schema]);
  const uploadWarnings = useMemo(() => collectUploadWarnings(schema), [schema]);
  const publishChecks = useMemo(
    () => buildPublishChecks({
      definition,
      schemaLength: schema.length,
      optionErrors,
      uploadWarnings,
      workflowEnabled: !!workflowEnabled,
      processDefinitionId: processDefinition?.id,
      approvalNodeReady,
      processIssues,
    }),
    [
      approvalNodeReady,
      definition,
      optionErrors,
      processDefinition?.id,
      processIssues,
      schema.length,
      uploadWarnings,
      workflowEnabled,
    ],
  );
  const publishErrors = publishChecks.filter((item) => item.status === 'error');

  const goStep = (key: string, nextId: number | null | undefined = formId) => {
    if (!nextId) return;
    history.push(`/approval/forms/${nextId}/wizard?step=${key}`);
  };

  const { saveBasic, saveDraft } = useWizardSaveActions({
    form,
    formId,
    isNew,
    isAdmin,
    canManageGrants,
    allCompanyRoleId: permissions.allCompanyRoleId,
    definition,
    schema,
    goStep,
  });

  const { publishAll, confirmPublish, handleWorkflowEnabledChange } = useWizardPublishActions({
    form,
    formId,
    definition,
    schema,
    workflowEnabled: !!workflowEnabled,
    processDefinition,
    canPublish: publishErrors.length === 0,
    firstPublishError: publishErrors[0]?.description,
  });

  if (isNew && !canCreateForm) {
    return (
      <PageContainer title={false}>
        <Result
          status="403"
          title="没有新建表单的权限"
          subTitle="当前账号可以设计已有表单的流程，但不能新建表单。"
        />
      </PageContainer>
    );
  }

  return (
    <PageContainer title={false}>
      {/* ===================================================================
          ⚠️ 用户指定永久保留区域：面包屑（PageContainer 默认生成）+ 分步导航
          「表单属性 → 表单设计 → 流程设计 → 预览发布」。
          禁止删除/隐藏此处导航，除非用户明确给出指令。
          =================================================================== */}
      <Card style={{ marginBottom: 16 }}>
        <Steps
          current={current}
          items={steps}
          onChange={(index) => {
            if (formId) goStep(steps[index].key);
          }}
        />
      </Card>
      {currentKey === 'basic' && (
        <FormBasicStep
          form={form}
          formId={formId}
          definition={definition}
          workflowEnabled={!!workflowEnabled}
          isAdmin={isAdmin}
          canManageGrants={canManageGrants}
          permissions={permissions}
          processFormFields={processFormFields}
          saving={saveBasic.isPending}
          onSave={() => saveBasic.mutate()}
          onBack={() => history.push('/approval/forms')}
          onWorkflowEnabledChange={handleWorkflowEnabledChange}
        />
      )}
      {currentKey === 'designer' && formId && (
        <FormDesignerSurface
          formId={formId}
          embedded
          onSaved={() =>
            qc.invalidateQueries({ queryKey: ['form-management-definition'] })
          }
        />
      )}
      {currentKey === 'process' && workflowEnabled && formId && (
        <ProcessDesignerSurface
          formDefId={formId}
          embedded
          onSaved={() =>
            qc.invalidateQueries({ queryKey: ['form-management-process'] })
          }
        />
      )}
      {currentKey === 'publish' && (
        <FormPublishStep
          formId={formId}
          definition={definition}
          workflowEnabled={!!workflowEnabled}
          processStatus={processDefinition?.status}
          previewSchema={previewSchema}
          publishChecks={publishChecks}
          publishErrors={publishErrors}
          visibilitySummary={permissions.visibilitySummary}
          maintainerCount={permissions.maintainerCount}
          savingDraft={saveDraft.isPending}
          publishing={publishAll.isPending}
          onBack={() => goStep(workflowEnabled ? 'process' : 'designer')}
          onSaveDraft={() => saveDraft.mutate()}
          onPublish={confirmPublish}
        />
      )}
    </PageContainer>
  );
}
