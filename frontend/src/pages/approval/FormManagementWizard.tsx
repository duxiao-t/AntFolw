import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  ExclamationCircleOutlined,
} from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { history, request, useLocation, useModel, useParams } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  List,
  Modal,
  message,
  Select,
  Space,
  Steps,
  Switch,
  Tag,
  theme,
  TreeSelect,
} from 'antd';
import { useEffect, useMemo } from 'react';
import { createStyles } from 'antd-style';
import { formRegistry } from '../../registry/formRegistry';
import type { SchemaNode } from '../../registry/types';
import { FormDesignerSurface } from '../designer/form/FormDesigner';
import { ProcessDesignerSurface } from '../designer/process/ProcessDesigner';
import type { TreeNode } from '../designer/process/types';
import {
  flattenFormFields,
  validateProcessTree,
} from '../designer/process/validation';
import FormGrantUserPicker, {
  type GrantDepartment,
  type GrantUser,
} from './FormGrantUserPicker';
import MobileFormPreview from './MobileFormPreview';
import BusinessNumberEditor from './BusinessNumberEditor';

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

type FormGrant = {
  version: number;
  userIds: number[];
  roleIds: number[];
  departmentIds: number[];
  users: GrantUser[];
  roles: { id: number; code: string; name: string }[];
  departments: GrantDepartment[];
};
type FormGrantCandidates = {
  roles: { id: number; code: string; name: string }[];
  departments: GrantDepartment[];
};

const allSteps = [
  { key: 'basic', title: '表单属性' },
  { key: 'designer', title: '表单制作' },
  { key: 'process', title: '流程设计' },
  { key: 'publish', title: '预览发布' },
];

const useWizardStyles = createStyles(({ token }) => ({
  propertiesCard: {
    overflow: 'hidden',
    borderColor: token.colorBorderSecondary,
    '& .ant-card-body': { padding: 0 },
  },
  propertiesForm: {
    width: '100%',
    maxWidth: 1120,
    margin: '0 auto',
  },
  propertiesSection: {
    padding: '26px 30px',
    '@media (max-width: 960px)': { padding: '22px 20px' },
  },
  dividedSection: { borderTop: `1px solid ${token.colorSplit}` },
  sectionHeader: {
    display: 'grid',
    gridTemplateColumns: '3px minmax(0, 1fr)',
    gap: 12,
    marginBottom: 20,
  },
  sectionMarker: {
    width: 3,
    minHeight: 38,
    borderRadius: 2,
    background: token.colorPrimary,
  },
  sectionTitle: {
    margin: 0,
    color: token.colorText,
    fontSize: 16,
    lineHeight: 1.35,
    fontWeight: 650,
  },
  sectionDescription: {
    display: 'block',
    marginTop: 4,
    color: token.colorTextSecondary,
    fontSize: 13,
    lineHeight: 1.5,
  },
  fieldGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
    gap: '18px 24px',
    '& .ant-form-item': { minWidth: 0, marginBottom: 0 },
    '@media (max-width: 960px)': { gridTemplateColumns: 'minmax(0, 1fr)' },
  },
  settingRow: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 24,
    marginTop: 20,
    padding: '15px 16px',
    border: `1px solid ${token.colorBorderSecondary}`,
    borderRadius: token.borderRadiusLG,
    background: token.colorFillAlter,
    '@media (max-width: 680px)': { alignItems: 'flex-start', flexDirection: 'column', gap: 12 },
  },
  settingCopy: { minWidth: 0 },
  settingLabel: { display: 'block', color: token.colorText, fontSize: 14, fontWeight: 600 },
  settingHint: { display: 'block', marginTop: 3, color: token.colorTextSecondary, fontSize: 12 },
  settingControl: {
    display: 'flex',
    flex: '0 0 auto',
    alignItems: 'center',
    gap: 10,
    color: token.colorTextSecondary,
    fontSize: 12,
  },
  grantGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
    gap: '18px 24px',
    marginTop: 20,
    '& .ant-form-item': { minWidth: 0, marginBottom: 0 },
    '@media (max-width: 960px)': { gridTemplateColumns: 'minmax(0, 1fr)' },
  },
  fullWidth: { gridColumn: '1 / -1' },
  actions: {
    display: 'flex',
    flexWrap: 'wrap',
    gap: 10,
    padding: '20px 30px 26px',
    borderTop: `1px solid ${token.colorSplit}`,
    '@media (max-width: 960px)': { padding: '18px 20px 22px' },
  },
}));

function parseJsonValue<T>(value: T | string | undefined, fallback: T): T {
  if (typeof value !== 'string') return value ?? fallback;
  try {
    return JSON.parse(value);
  } catch {
    return fallback;
  }
}

function getWorkflowEnabled(
  settings: Record<string, any> | string | undefined,
) {
  return !!parseJsonValue<Record<string, any>>(settings, {}).workflowEnabled;
}

function getNodeLabel(node: SchemaNode) {
  return node.label || formRegistry[node.type]?.label || node.type;
}

function grantDepartmentTree(departments: GrantDepartment[]) {
  const children = new Map<number | undefined, GrantDepartment[]>();
  departments.forEach((department) => {
    children.set(department.parentId,
      [...(children.get(department.parentId) ?? []), department]);
  });
  const build = (parentId?: number): any[] => (children.get(parentId) ?? []).map((department) => ({
    value: department.id,
    title: department.name,
    children: build(department.id),
  }));
  return build();
}

function enrichSchemaLabels(nodes: SchemaNode[]): SchemaNode[] {
  return nodes.map((node) => ({
    ...node,
    label: getNodeLabel(node),
    children: node.children ? enrichSchemaLabels(node.children) : undefined,
  }));
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
  return allSteps.filter((item) => workflowEnabled || item.key !== 'process');
}

function stepFromSearch(search: string, steps: typeof allSteps) {
  const key = new URLSearchParams(search).get('step') ?? 'basic';
  const index = steps.findIndex((item) => item.key === key);
  return index >= 0 ? index : 0;
}

export default function FormManagementWizard() {
  const { styles } = useWizardStyles();
  const params = useParams();
  const location = useLocation();
  const { initialState } = useModel('@@initialState');
  const qc = useQueryClient();
  const [form] = Form.useForm();
  const { token } = theme.useToken();
  const id = params.id;
  const isNew = !id || id === 'new';
  const formId = isNew ? null : Number(id);
  const currentUser = initialState?.currentUser as any;
  const isAdmin = (currentUser?.roles ?? []).includes('admin');
  const canManageGrants =
    isAdmin ||
    (currentUser?.permissions ?? []).includes('form.authorization.manage');

  const { data: definition } = useQuery<FormDefinition>({
    queryKey: ['form-management-definition', formId],
    queryFn: () => request<FormDefinition>(`/api/forms/definitions/${formId}`),
    enabled: !!formId,
  });

  const watchedWorkflowEnabled = Form.useWatch('workflowEnabled', form);
  const watchedAllCompany = Form.useWatch('allCompany', form);
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

  const { data: formGrant } = useQuery<FormGrant>({
    queryKey: ['form-management-grant', formId],
    queryFn: () => request<FormGrant>(`/api/forms/${formId}/grants`),
    enabled: !!formId && canManageGrants,
  });

  const { data: grantCandidates } = useQuery<FormGrantCandidates>({
    queryKey: ['form-management-grant-candidates', formId ?? 'new'],
    queryFn: () =>
      request<FormGrantCandidates>(
        formId
          ? `/api/forms/${formId}/grants/candidates`
          : '/api/forms/grant-candidates',
      ),
    enabled: canManageGrants,
  });
  const allCompanyRoleId = grantCandidates?.roles.find((role) => role.code === 'user')?.id;
  const visibilitySummary = allCompanyRoleId && formGrant?.roleIds.includes(allCompanyRoleId)
    ? '全公司'
    : [
        formGrant?.departmentIds.length ? `${formGrant.departmentIds.length} 个部门` : '',
        formGrant?.roleIds.length ? `${formGrant.roleIds.length} 个角色` : '',
        formGrant?.userIds.length ? `${formGrant.userIds.length} 名人员` : '',
      ].filter(Boolean).join('、') || '仅创建人';
  const initialGrantUsers: GrantUser[] =
    formGrant?.users ??
    (currentUser?.id
      ? [
          {
            id: currentUser.id,
            username: currentUser.username ?? '',
            displayName:
              currentUser.displayName ??
              currentUser.name ??
              currentUser.username,
            employeeNo: currentUser.employeeNo,
            departmentId: currentUser.departmentId,
          },
        ]
      : []);

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
  const processFormFields = useMemo(() => flattenFormFields(schema), [schema]);
  const processIssues = useMemo(
    () =>
      processTree
        ? validateProcessTree(processTree as TreeNode, processFormFields)
        : [],
    [processFormFields, processTree],
  );
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
          processDefinition?.id &&
          approvalNodeReady &&
          processIssues.length === 0
            ? 'success'
            : 'error',
        title: '审批流程已配置',
        description:
          processDefinition?.id &&
          approvalNodeReady &&
          processIssues.length === 0
            ? '已配置至少一个审批节点'
            : processIssues[0]?.message ??
              '启用审批流程后，必须至少配置一个审批节点',
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
    processIssues,
    schema.length,
    uploadWarnings,
    workflowEnabled,
  ]);
  const publishErrors = publishChecks.filter((item) => item.status === 'error');
  const canPublish = publishErrors.length === 0;

  useEffect(() => {
    if (definition) {
      form.setFieldsValue({
        code: definition.code,
        name: definition.name,
        workflowEnabled: getWorkflowEnabled(definition.settings),
        businessNumber: parseJsonValue<Record<string, any>>(definition.settings, {}).businessNumber,
      });
    }
    if (canManageGrants && formGrant) {
      form.setFieldsValue({
        userIds: formGrant.userIds,
        roleIds: isAdmin ? formGrant.roleIds.filter((id) => id !== allCompanyRoleId) : [],
        allCompany: !!allCompanyRoleId && formGrant.roleIds.includes(allCompanyRoleId),
        departmentIds: formGrant.departmentIds,
      });
    } else if (
      canManageGrants &&
      isNew &&
      form.getFieldValue('userIds') === undefined
    ) {
      form.setFieldsValue({
        userIds: currentUser?.id ? [currentUser.id] : [],
        roleIds: [],
        allCompany: false,
        departmentIds: [],
      });
    }
  }, [
    allCompanyRoleId,
    canManageGrants,
    currentUser?.id,
    definition,
    form,
    formGrant,
    isAdmin,
    isNew,
  ]);

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
        businessNumber: values.businessNumber,
      };
      const saved = await request<FormDefinition>('/api/forms/definitions', {
        method: 'POST',
        data: {
          id: formId,
          code: values.code,
          name: values.name,
          schema: parseJsonValue(definition?.schema, []),
          settings,
        },
      });
      if (!canManageGrants) return saved;

      try {
        const latestGrant = await request<FormGrant>(
          `/api/forms/${saved.id}/grants`,
        );
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
        await request<FormGrant>(`/api/forms/${saved.id}/grants`, {
          method: 'PUT',
          data: { userIds, roleIds, departmentIds, version: latestGrant.version },
        });
      } catch (error: any) {
        const grantError =
          error instanceof Error
            ? error
            : new Error(error?.message ?? '表单管理员保存失败');
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
        goStep('basic', error.formId);
      }
      message.error(error?.message ?? '保存失败');
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
      onOk: async () => {
        try {
          await publishAll.mutateAsync();
        } catch {
          // React Query onError already shows the backend business message.
        }
      },
    });
  };

  const renderBasic = () => (
    <Card className={styles.propertiesCard}>
      <Form
        className={styles.propertiesForm}
        form={form}
        layout="vertical"
        initialValues={{
          code: definition?.code ?? `form_${Date.now()}`,
          name: definition?.name ?? '未命名表单',
          workflowEnabled: getWorkflowEnabled(definition?.settings),
        }}
      >
        <section className={styles.propertiesSection}>
          <div className={styles.sectionHeader}>
            <span className={styles.sectionMarker} aria-hidden="true" />
            <div>
              <h2 className={styles.sectionTitle}>基础信息</h2>
              <span className={styles.sectionDescription}>维护表单名称、唯一编码和提交后的处理方式。</span>
            </div>
          </div>
          <div className={styles.fieldGrid}>
            <Form.Item
              label="表单名称"
              name="name"
              rules={[{ required: true, message: '请输入表单名称' }]}
            >
              <Input placeholder="例如：请假申请" />
            </Form.Item>
            <Form.Item
              label="表单编码"
              name="code"
              rules={[{ required: true, message: '请输入表单编码' }]}
            >
              <Input disabled={!!formId} placeholder="例如：leave_request" />
            </Form.Item>
          </div>
          <div className={styles.settingRow}>
            <div className={styles.settingCopy}>
              <span className={styles.settingLabel}>审批流程</span>
              <span className={styles.settingHint}>启用后，表单提交将进入已配置的审批流程。</span>
            </div>
            <div className={styles.settingControl}>
              <span>{workflowEnabled ? '已启用' : '未启用'}</span>
              <Form.Item name="workflowEnabled" valuePropName="checked" noStyle>
                <Switch aria-label="是否启用审批流程" onChange={handleWorkflowEnabledChange} />
              </Form.Item>
            </div>
          </div>
        </section>
        {canManageGrants && (
          <section className={`${styles.propertiesSection} ${styles.dividedSection}`}>
            <div className={styles.sectionHeader}>
              <span className={styles.sectionMarker} aria-hidden="true" />
              <div>
                <h2 className={styles.sectionTitle}>表单可见范围</h2>
                <span className={styles.sectionDescription}>全公司、部门、角色和指定人员取并集；部门授权包含所有下级部门。</span>
              </div>
            </div>
            {isAdmin && (
              <div className={styles.settingRow} style={{ marginTop: 0 }}>
                <div className={styles.settingCopy}>
                  <span className={styles.settingLabel}>全公司可见</span>
                  <span className={styles.settingHint}>允许所有有效成员查看并发起此表单。</span>
                </div>
                <div className={styles.settingControl}>
                  <span>{!allCompanyRoleId ? '不可用' : watchedAllCompany ? '已启用' : '未启用'}</span>
                  <Form.Item name="allCompany" valuePropName="checked" noStyle>
                    <Switch aria-label="全公司可见" disabled={!allCompanyRoleId} />
                  </Form.Item>
                </div>
              </div>
            )}
            <div className={styles.grantGrid}>
              <Form.Item
                className={styles.fullWidth}
                label="指定人员"
                name="userIds"
              >
                <FormGrantUserPicker
                  users={initialGrantUsers}
                  departments={grantCandidates?.departments ?? []}
                  endpoint={
                    formId
                      ? `/api/forms/${formId}/grants/user-candidates`
                      : '/api/forms/grant-user-candidates'
                  }
                />
              </Form.Item>
              {isAdmin && (
                <Form.Item label="指定角色" name="roleIds">
                  <Select mode="multiple" maxTagCount="responsive"
                    showSearch={{ optionFilterProp: 'label' }}
                    placeholder="选择可查看表单的角色"
                    options={(grantCandidates?.roles ?? []).filter((role) => role.code !== 'user').map((role) => ({
                      value: role.id, label: `${role.name} · ${role.code}`,
                    }))} />
                </Form.Item>
              )}
              <Form.Item label="部门及下级部门" name="departmentIds">
                <TreeSelect treeCheckable treeCheckStrictly={false} showCheckedStrategy={TreeSelect.SHOW_PARENT}
                  maxTagCount="responsive" allowClear treeDefaultExpandAll
                  treeData={grantDepartmentTree(grantCandidates?.departments ?? [])}
                  placeholder="选择部门后自动包含其下级部门" />
              </Form.Item>
            </div>
          </section>
        )}
        <section className={`${styles.propertiesSection} ${styles.dividedSection}`}>
          <div className={styles.sectionHeader}>
            <span className={styles.sectionMarker} aria-hidden="true" />
            <div>
              <h2 className={styles.sectionTitle}>业务单号</h2>
              <span className={styles.sectionDescription}>使用默认编号，或按业务需要组合专属流水号。</span>
            </div>
          </div>
          <BusinessNumberEditor fields={processFormFields} />
        </section>
      </Form>
      <div className={styles.actions}>
        <Button
          type="primary"
          loading={saveBasic.isPending}
          onClick={() => saveBasic.mutate()}
        >
          保存并进入表单制作
        </Button>
        <Button onClick={() => history.push('/approval/forms')}>
          返回列表
        </Button>
      </div>
    </Card>
  );

  const renderPublish = () => {
    const checkIcon = (status: PublishCheckStatus) => {
      if (status === 'success') {
        return <CheckCircleOutlined style={{ color: token.colorSuccess }} />;
      }
      if (status === 'warning') {
        return (
          <ExclamationCircleOutlined style={{ color: token.colorWarning }} />
        );
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
          <MobileFormPreview
            title={definition?.name ?? '未命名表单'}
            description={definition?.description}
            schema={previewSchema}
          />
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
              <Descriptions.Item label="可见范围">{visibilitySummary}</Descriptions.Item>
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
              <Button
                onClick={() => goStep(workflowEnabled ? 'process' : 'designer')}
              >
                上一步
              </Button>
              <Button
                loading={saveDraft.isPending}
                onClick={() => saveDraft.mutate()}
              >
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
      {currentKey === 'basic' && renderBasic()}
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
      {currentKey === 'publish' && renderPublish()}
    </PageContainer>
  );
}
