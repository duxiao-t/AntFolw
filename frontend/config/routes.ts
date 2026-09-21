/**
 * AntFlow 路由配置。
 *
 * 菜单可展示页面全部来自 src/pages/registry.ts（唯一真源），这里只做拼装；
 * 隐藏页 / 设计器 / 运行时入口单独声明，不参与菜单编排。
 */
import { PAGES, accessKey } from '../src/pages/registry';

const registryRoutes = PAGES.map((page) => ({
  name: page.key,
  icon: page.icon,
  path: page.path,
  component: page.component,
  access: accessKey(page.key),
}));

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

  // ===== 菜单页面（注册表派生）=====
  ...registryRoutes,

  // ===== 隐藏页 / 设计器 / 运行时 =====
  { path: '/approval/templates', component: './approval/TemplateList', hideInMenu: true, access: 'canReadForms' },
  { path: '/approval/designer', component: './approval/DesignerEntry', hideInMenu: true, access: 'canDesigner' },
  { path: '/approval/forms/new', component: './approval/FormManagementWizard', hideInMenu: true, access: 'canCreateForm' },
  { path: '/approval/forms/:id/wizard', component: './approval/FormManagementWizard', hideInMenu: true, access: 'canDesigner' },
  { path: '/designer/form/:id', component: './designer/form/FormDesigner', hideInMenu: true, access: 'canDesigner' },
  { path: '/designer/process/:formDefId', component: './designer/process/ProcessDesigner', hideInMenu: true, access: 'canDesigner' },
  { path: '/admin/forms', component: './admin/FormList', hideInMenu: true, access: 'canAdmin' },
  { path: '/admin/form-data', component: './admin/FormData', hideInMenu: true, access: 'canAdmin' },
  { path: '/approval/form-data', component: './admin/FormData', hideInMenu: true, access: 'canAdmin' },
  { path: '/runtime/form/:code', component: './runtime/form/Fill', hideInMenu: true, access: 'canUseRuntime' },
  { path: '/runtime/list', component: './runtime/form/List', hideInMenu: true, access: 'canUseRuntime' },
  { path: '/tasks/inbox', component: './tasks/Inbox', hideInMenu: true, access: 'canUseTasks' },
  { path: '/tasks/done', component: './tasks/Done', hideInMenu: true, access: 'canUseTasks' },
  { path: '/proc', component: './proc/Sent', hideInMenu: true, access: 'canUseProcesses' },
  { path: '/proc/:id', component: './proc/Detail', hideInMenu: true, access: 'canUseProcessDetail' },
  { path: '/account/settings', component: './account/settings', hideInMenu: true },
  { path: '/account/center', redirect: '/account/settings' },

  // ===== 默认 =====
  { path: '/', component: './AuthorizedHome' },
  { component: './exception/404', path: '/*' },
];
