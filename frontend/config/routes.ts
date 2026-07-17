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
    component: './Welcome',
  },

  // ===== 组织架构 =====
  {
    path: '/org',
    name: 'org',
    icon: 'team',
    access: 'canAdmin',
    routes: [
      { path: '/org', redirect: '/org/departments' },
      { name: 'departments', icon: 'apartment', path: '/org/departments', component: './admin/Department' },
      { name: 'employees', icon: 'user', path: '/org/employees', component: './admin/User' },
    ],
  },

  // ===== 权限与安全 =====
  {
    path: '/security',
    name: 'security',
    icon: 'safetyCertificate',
    access: 'canAdmin',
    routes: [
      { path: '/security', redirect: '/security/roles' },
      { name: 'roles', icon: 'idcard', path: '/security/roles', component: './security/Role' },
      { name: 'user-permissions', icon: 'key', path: '/security/user-permissions', component: './security/UserPermission' },
      { name: 'audit-log', icon: 'fileSearch', path: '/security/audit-log', component: './security/AuditLog' },
      { name: 'policy', icon: 'safety', path: '/security/policy', component: './security/Policy' },
    ],
  },

  // ===== 审批与流程 =====
  {
    path: '/approval',
    name: 'approval',
    icon: 'audit',
    routes: [
      { path: '/approval', redirect: '/approval/templates' },
      { name: 'templates', icon: 'fileText', path: '/approval/templates', component: './approval/TemplateList' },
      { name: 'designer', icon: 'partition', path: '/approval/designer', component: './approval/DesignerEntry' },
      { name: 'records', icon: 'search', path: '/approval/records', component: './approval/RecordList' },
    ],
  },

  // ===== 数据与报表 =====
  {
    path: '/report',
    name: 'report',
    icon: 'barChart',
    access: 'canAdmin',
    routes: [
      { path: '/report', redirect: '/report/center' },
      { name: 'center', icon: 'fund', path: '/report/center', component: './report/Center' },
      { name: 'export', icon: 'export', path: '/report/export', component: './report/Export' },
      { name: 'view', icon: 'dashboard', path: '/report/view', component: './report/Dashboard' },
    ],
  },

  // ===== 系统设置 =====
  {
    path: '/settings',
    name: 'settings',
    icon: 'setting',
    access: 'canAdmin',
    routes: [
      { path: '/settings', redirect: '/settings/company' },
      { name: 'company', icon: 'bank', path: '/settings/company', component: './settings/Company' },
      { name: 's3', icon: 'cloud', path: '/settings/s3', component: './settings/S3Storage' },
      { name: 'wecom', icon: 'wechat', path: '/settings/wecom', component: './settings/Wecom' },
      { name: 'billing', icon: 'dollar', path: '/settings/billing', component: './settings/Billing' },
    ],
  },

  // ===== 设计器（隐藏）=====
  { path: '/designer/form/:id',        component: './designer/form/FormDesigner',    hideInMenu: true, access: 'canAdmin' },
  { path: '/designer/process/:formDefId', component: './designer/process/ProcessDesigner', hideInMenu: true, access: 'canAdmin' },

  // ===== 运行时 / 任务（隐藏）=====
  { path: '/runtime/form/:code', component: './runtime/form/Fill', hideInMenu: true },
  { path: '/runtime/list',       component: './runtime/form/List', hideInMenu: true },
  { path: '/tasks/inbox',        component: './tasks/Inbox',       hideInMenu: true },
  { path: '/tasks/done',         component: './tasks/Done',        hideInMenu: true },
  { path: '/proc',               component: './proc/Sent',         hideInMenu: true },
  { path: '/proc/:id',           component: './proc/Detail',       hideInMenu: true },
  { path: '/account/settings',      component: './account/settings',   hideInMenu: true },
  { path: '/account/center',        component: './account/center',     hideInMenu: true },

  // ===== 默认 =====
  { path: '/', redirect: '/workplace' },
  { component: './exception/404', path: '/*' },
];
