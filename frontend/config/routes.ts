/**
 * AntFlow 路由配置 — 钉钉式菜单结构
 */
export default [
  // ===== 登录（无布局）=====
  {
    path: '/user',
    layout: false,
    routes: [
      { path: '/user/login', name: 'login', component: './user/login' },
      { path: '/user', redirect: '/user/login' },
      { component: './exception/404', path: '/user/*' },
    ],
  },

  // ===== 工作台 =====
  {
    path: '/workplace',
    name: 'workplace',
    icon: 'home',
    component: './dashboard/workplace',
    access: 'canAccessWorkplace',
  },

  // ===== 组织架构 =====
  {
    path: '/org',
    name: 'org',
    icon: 'team',
    access: 'canManageOrg',
    routes: [
      { path: '/org', redirect: '/org/contacts' },
      { name: 'contacts', icon: 'contacts', path: '/org/contacts', component: './org/Contacts', access: 'canManageOrg' },
    ],
  },

  // ===== 权限与安全 =====
  {
    path: '/security',
    name: 'security',
    icon: 'safetyCertificate',
    access: 'canManageSecurity',
    routes: [
      { path: '/security', redirect: '/security/roles' },
      { name: 'roles', icon: 'idcard', path: '/security/roles', component: './security/Role', access: 'canManageRoles' },
      { name: 'user-permissions', icon: 'key', path: '/security/user-permissions', component: './security/UserPermission', access: 'canAssignRoles' },
      { name: 'audit-log', icon: 'fileSearch', path: '/security/audit-log', component: './security/AuditLog', access: 'canReadAudit' },
    ],
  },

  // ===== 审批与流程 =====
  {
    path: '/approval',
    name: 'approval',
    icon: 'audit',
    routes: [
      { path: '/approval', redirect: '/approval/forms' },
      { name: 'forms', icon: 'form', path: '/approval/forms', component: './approval/FormManagementList', access: 'canReadForms' },
      { name: 'templates', icon: 'fileText', path: '/approval/templates', component: './approval/TemplateList', hideInMenu: true },
      { name: 'designer', icon: 'partition', path: '/approval/designer', component: './approval/DesignerEntry', hideInMenu: true },
      { name: 'records', icon: 'search', path: '/approval/records', component: './approval/RecordList', access: 'canReadInstances' },
      { name: 'monitor', icon: 'dashboard', path: '/approval/monitor', component: './approval/WorkflowMonitor', access: 'canOverrideWorkflow' },
    ],
  },

  // ===== 数据与报表 =====
  {
    path: '/report',
    name: 'report',
    icon: 'barChart',
    access: 'canReadReports',
    routes: [
      { path: '/report', redirect: '/report/center' },
      { name: 'center', icon: 'fund', path: '/report/center', component: './report/Center', access: 'canReadReportCenter' },
      { name: 'export', icon: 'export', path: '/report/export', component: './report/Export', access: 'canReadReportExport' },
      { name: 'view', icon: 'dashboard', path: '/report/view', component: './report/Dashboard', access: 'canReadReportDashboard' },
    ],
  },

  // ===== 系统设置 =====
  {
    path: '/settings',
    name: 'settings',
    icon: 'setting',
    access: 'canManageSettings',
    routes: [
      { path: '/settings', redirect: '/settings/company' },
      { name: 'company', icon: 'bank', path: '/settings/company', component: './settings/Company', access: 'canManageCompany' },
      { name: 's3', icon: 'cloud', path: '/settings/s3', component: './settings/S3Storage', access: 'canManageS3' },
      { name: 'wecom', icon: 'wechat', path: '/settings/wecom', component: './settings/Wecom', access: 'canManageWecom' },
      { name: 'identityProviders', icon: 'safetyCertificate', path: '/settings/identity-providers', component: './settings/IdentityProviders', access: 'canManageIdentityProviders' },
      { name: 'billing', icon: 'dollar', path: '/settings/billing', component: './settings/Billing', access: 'canManageBilling' },
    ],
  },

  // ===== 设计器（隐藏）=====
  { path: '/approval/forms/new', component: './approval/FormManagementWizard', hideInMenu: true, access: 'canCreateForm' },
  { path: '/approval/forms/:id/wizard', component: './approval/FormManagementWizard', hideInMenu: true, access: 'canDesigner' },
  { path: '/designer/form/:id', component: './designer/form/FormDesigner', hideInMenu: true, access: 'canDesigner' },
  { path: '/designer/process/:formDefId', component: './designer/process/ProcessDesigner', hideInMenu: true, access: 'canDesigner' },

  // ===== 表单后台兼容入口（隐藏）=====
  { path: '/admin/forms', component: './admin/FormList', hideInMenu: true, access: 'canAdmin' },
  { path: '/admin/form-data', component: './admin/FormData', hideInMenu: true, access: 'canAdmin' },
  { path: '/approval/form-data', component: './admin/FormData', hideInMenu: true, access: 'canAdmin' },

  // ===== 运行时 / 任务（隐藏）=====
  { path: '/runtime/form/:code', component: './runtime/form/Fill', hideInMenu: true, access: 'canUseRuntime' },
  { path: '/runtime/list', component: './runtime/form/List', hideInMenu: true, access: 'canUseRuntime' },
  { path: '/tasks/inbox', component: './tasks/Inbox', hideInMenu: true, access: 'canUseTasks' },
  { path: '/tasks/done', component: './tasks/Done', hideInMenu: true, access: 'canUseTasks' },
  { path: '/proc', component: './proc/Sent', hideInMenu: true, access: 'canUseProcesses' },
  { path: '/proc/:id', component: './proc/Detail', hideInMenu: true, access: 'canUseProcessDetail' },
  { path: '/account/settings', component: './account/settings', hideInMenu: true },
  { path: '/account/center', component: './account/center', hideInMenu: true },

  // ===== 默认 =====
  { path: '/', component: './AuthorizedHome' },
  { component: './exception/404', path: '/*' },
];
