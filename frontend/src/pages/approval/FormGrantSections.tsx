import { Button, Form, Select, Switch, TreeSelect } from 'antd';
import FormGrantUserPicker from './FormGrantUserPicker';
import { useWizardStyles } from './wizardStyles';
import { grantDepartmentTree } from './wizardValidation';
import type { WizardPermissions } from './useWizardPermissions';

type SectionProps = {
  formId: number | null;
  permissions: WizardPermissions;
};

export function FormMaintainerSection({ formId, permissions }: SectionProps) {
  const { styles } = useWizardStyles();
  return (
    <section className={`${styles.propertiesSection} ${styles.dividedSection}`}>
      <div className={styles.sectionHeader}>
        <span className={styles.sectionMarker} aria-hidden="true" />
        <div>
          <h2 className={styles.sectionTitle}>指定维护人员</h2>
          <span className={styles.sectionDescription}>只有名单内且持有对应表单或流程能力的人员才能设计、发布或删除模板；至少保留一人。</span>
        </div>
      </div>
      <Form.Item
        label="维护人员"
        name="maintainerIds"
        rules={[{
          validator: (_rule, value?: number[]) =>
            value?.length ? Promise.resolve() : Promise.reject(new Error('至少选择一名维护人员')),
        }]}
      >
        <FormGrantUserPicker
          title="选择维护人员"
          users={permissions.initialMaintainerUsers}
          departments={permissions.grantCandidates?.departments ?? []}
          endpoint={
            formId
              ? `/api/forms/${formId}/grants/user-candidates`
              : '/api/forms/grant-user-candidates'
          }
        />
      </Form.Item>
      <Button
        disabled={!formId}
        loading={permissions.saveMaintainers.isPending}
        onClick={() => permissions.saveMaintainers.mutate()}
      >
        保存维护人员
      </Button>
    </section>
  );
}

export function FormUsageScopeSection({ formId, isAdmin, permissions }: SectionProps & { isAdmin: boolean }) {
  const { styles } = useWizardStyles();
  const {
    allCompanyRoleId,
    allCompany,
    formGrant,
    grantCandidates,
    grantDirty,
    initialGrantUsers,
    saveUsageScope,
  } = permissions;
  return (
    <section className={`${styles.propertiesSection} ${styles.dividedSection}`}>
      <div className={styles.sectionHeader}>
        <span className={styles.sectionMarker} aria-hidden="true" />
        <div>
          <h2 className={styles.sectionTitle}>表单使用范围</h2>
          <span className={styles.sectionDescription}>按人员、角色或部门授权使用；不授予模板维护或业务数据查看权限。</span>
        </div>
      </div>
      {isAdmin && (
        <div className={styles.settingRow} style={{ marginTop: 0 }}>
          <div className={styles.settingCopy}>
            <span className={styles.settingLabel}>全部员工</span>
            <span className={styles.settingHint}>授予已授权公司内的员工角色，不会向其他公司开放。</span>
          </div>
          <div className={styles.settingControl}>
            <span>{!allCompanyRoleId ? '不可用' : allCompany ? '已启用' : '未启用'}</span>
            <Form.Item name="allCompany" valuePropName="checked" noStyle>
              <Switch aria-label="已授权公司内全部员工" disabled={!allCompanyRoleId} />
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
            title="选择可使用表单的人员"
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
              options={(grantCandidates?.roles ?? []).filter((role) => role.id !== allCompanyRoleId).map((role) => ({
                value: role.id, label: `${role.name} · ${role.code}`,
              }))} />
          </Form.Item>
        )}
        <Form.Item label="部门及下级部门" name="departmentIds">
          <TreeSelect treeCheckable treeCheckStrictly={false} showCheckedStrategy={TreeSelect.SHOW_PARENT}
            maxTagCount="responsive" allowClear
            treeData={grantDepartmentTree(grantCandidates?.departments ?? [])}
            placeholder="选择部门后自动包含其下级部门" />
        </Form.Item>
      </div>
      <div className={styles.saveRow}>
        <Button
          disabled={!formId}
          loading={saveUsageScope.isPending}
          onClick={() => saveUsageScope.mutate()}
        >
          保存使用范围
        </Button>
        {formId && grantDirty && (
          <span className={styles.unsavedHint}>有未保存的修改</span>
        )}
      </div>
    </section>
  );
}
