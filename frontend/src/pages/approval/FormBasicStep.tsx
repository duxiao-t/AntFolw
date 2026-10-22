import { Button, Card, Form, Input, Switch } from 'antd';
import type { FormInstance } from 'antd';
import type { ComponentProps } from 'react';
import BusinessNumberEditor from './BusinessNumberEditor';
import { FormMaintainerSection, FormUsageScopeSection } from './FormGrantSections';
import { useWizardStyles } from './wizardStyles';
import { getWorkflowEnabled } from './wizardValidation';
import type { WizardPermissions } from './useWizardPermissions';

type Props = {
  form: FormInstance;
  formId: number | null;
  definition?: AntFlowAPI.FormDefinition;
  workflowEnabled: boolean;
  isAdmin: boolean;
  canManageGrants: boolean;
  permissions: WizardPermissions;
  /** 业务单号的可用字段来自设计器，类型从编辑器自身推导，避免多引一条跨页面依赖。 */
  processFormFields: ComponentProps<typeof BusinessNumberEditor>['fields'];
  saving: boolean;
  onSave: () => void;
  onBack: () => void;
  onWorkflowEnabledChange: (checked: boolean) => void;
};

export default function FormBasicStep({
  form,
  formId,
  definition,
  workflowEnabled,
  isAdmin,
  canManageGrants,
  permissions,
  processFormFields,
  saving,
  onSave,
  onBack,
  onWorkflowEnabledChange,
}: Props) {
  const { styles } = useWizardStyles();
  return (
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
                <Switch aria-label="是否启用审批流程" onChange={onWorkflowEnabledChange} />
              </Form.Item>
            </div>
          </div>
        </section>
        {canManageGrants && <FormMaintainerSection formId={formId} permissions={permissions} />}
        {canManageGrants && (
          <FormUsageScopeSection formId={formId} isAdmin={isAdmin} permissions={permissions} />
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
        <Button type="primary" loading={saving} onClick={onSave}>
          保存表单属性
        </Button>
        <Button onClick={onBack}>
          返回列表
        </Button>
      </div>
    </Card>
  );
}
