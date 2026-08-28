/**
 * @see https://umijs.org/docs/max/access#access
 * */
type AuthorizedCurrentUser = API.CurrentUser & {
  permissions?: string[];
};

export default function access(
  initialState: { currentUser?: AuthorizedCurrentUser } | undefined,
) {
  const { currentUser } = initialState ?? {};
  const roles = currentUser?.roles ?? [];
  const permissions = currentUser?.permissions ?? [];
  const admin = roles.includes('admin');
  const can = (permission: string) => admin || permissions.includes(permission);
  const page = (permission: string) => admin || permissions.includes(permission);
  return {
    canAdmin: admin,
    canAccessWorkplace: page('page.workplace'),
    canDesigner:
      page('page.approval.forms') && can('form.definition.design'),
    canReadForms:
      page('page.approval.forms') && can('form.definition.read'),
    canCreateForm:
      page('page.approval.forms') && can('form.definition.create'),
    canReadInstances:
      page('page.approval.records') && can('workflow.instance.read'),
    canManageOrg:
      page('page.org.contacts') &&
      can('org.department.read') &&
      can('org.user.read'),
    canWriteDepartments: can('org.department.write'),
    canWriteUsers: can('org.user.write'),
    canManageSecurity:
      (page('page.security.roles') &&
          can('security.role.read') &&
          can('security.permission.read')) ||
      admin ||
      (page('page.security.audit_log') && can('security.audit.read')),
    canManageRoles:
      page('page.security.roles') &&
      can('security.role.read') &&
      can('security.permission.read'),
    canAssignRoles: admin,
    canReadAudit:
      page('page.security.audit_log') && can('security.audit.read'),
    canReadReportCenter:
      page('page.report.center') && can('form.data.read'),
    canReadReportExport:
      page('page.report.export') && can('form.data.export'),
    canReadReportDashboard:
      page('page.report.dashboard') && can('form.data.read'),
    canReadReports:
      (page('page.report.center') || page('page.report.export') ||
        page('page.report.dashboard')) && can('form.data.read'),
    canManageSettings:
      page('page.settings.company') || page('page.settings.s3') ||
      page('page.settings.wecom') || page('page.settings.billing'),
    canManageCompany:
      page('page.settings.company') && can('org.company.manage'),
    canManageS3: page('page.settings.s3'),
    canManageWecom: page('page.settings.wecom'),
    canManageBilling: page('page.settings.billing'),
    canUseRuntime:
      page('page.workplace') && can('form.runtime.read'),
    canUseTasks:
      page('page.workplace') && can('workflow.task.read'),
    canUseProcesses:
      page('page.workplace') && can('workflow.instance.read'),
    canUseProcessDetail:
      page('page.workplace') &&
      (can('workflow.task.read') || can('workflow.instance.read')),
    canApproveTask: can('workflow.task.approve'),
    canRejectTask: can('workflow.task.reject'),
    canWithdrawInstance: can('workflow.instance.withdraw'),
    canOverrideWorkflow: can('workflow.instance.override'),
    canRetryAutomation: can('workflow.automation.retry'),
  };
}
