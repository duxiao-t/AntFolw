export const CAPABILITY = {
  formDefinitionRead: 'form:definition:read',
  formDefinitionManage: 'form:definition:manage',
  formDefinitionPublish: 'form:definition:publish',
  formDefinitionDelete: 'form:definition:delete',
  formAuthorizationManage: 'form:authorization:manage',
  formOptionSourceManage: 'form:option_source:manage',
  formDataRead: 'form:data:read',
  workflowInstanceRead: 'workflow:instance:read',
  workflowInstanceOverride: 'workflow:instance:override',
  workflowInstanceWithdraw: 'workflow:instance:withdraw',
  workflowMonitorRead: 'workflow:monitor:read',
  workflowTaskRead: 'workflow:task:read',
  workflowTaskApprove: 'workflow:task:approve',
  workflowTaskReject: 'workflow:task:reject',
  workflowAutomationRetry: 'workflow:automation:retry',
  orgUserRead: 'org:user:read',
  securityRoleRead: 'security:role:read',
  securityRoleManage: 'security:role:manage',
  auditEventExport: 'audit:event:export',
  auditArchiveDownload: 'audit:archive:download',
} as const;

export function hasCapability(
  user: { roles?: readonly string[]; permissions?: readonly string[] } | null | undefined,
  capability: string,
): boolean {
  return Boolean(user?.roles?.includes('admin') || user?.permissions?.includes(capability));
}
