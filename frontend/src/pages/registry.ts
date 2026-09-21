/**
 * 页面注册表：菜单可展示页面的唯一真源。
 *
 * - 每个页面声明 pageKey、路由、组件、图标，以及「只读能力集」（全部满足菜单才可见）。
 * - config/routes.ts 与 access.ts 都由这里派生；写能力只控制按钮与接口，不进可见性判断。
 * - 后端 t_menu 只存 pageKey 与所需能力，不存组件路径，因此本文件是页面存在性的唯一裁判。
 *
 * 必须是纯数据（不引入 React/JSX），Umi 配置文件也会 import 它。
 */
import type { MenuDataItem } from '@ant-design/pro-components';
import pageCapabilities from '../../config/page-capabilities.json';

export type PageDef = {
  /** 与后端 t_menu.page_key 对应的稳定标识 */
  key: string;
  path: string;
  component: string;
  icon?: string;
  /** 菜单该项需要的只读能力（数组，全部满足才可见） */
  readCapabilities: string[];
};

/** 只读能力集允许为空：工作台只要求进入管理端 */
export const CONSOLE_ENTRY = 'console:entry:access';
export const PAGE_CAPABILITIES = pageCapabilities as Record<string, string[]>;

export const PAGES: PageDef[] = [
  { key: 'workplace', path: '/workplace', component: './dashboard/workplace', icon: 'home',
    readCapabilities: PAGE_CAPABILITIES.workplace },
  { key: 'org.contacts', path: '/org/contacts', component: './org/Contacts', icon: 'contacts',
    readCapabilities: PAGE_CAPABILITIES['org.contacts'] },
  { key: 'approval.forms', path: '/approval/forms', component: './approval/FormManagementList',
    icon: 'form', readCapabilities: PAGE_CAPABILITIES['approval.forms'] },
  { key: 'approval.records', path: '/approval/records', component: './approval/RecordList',
    icon: 'search', readCapabilities: PAGE_CAPABILITIES['approval.records'] },
  { key: 'approval.monitor', path: '/approval/monitor', component: './approval/WorkflowMonitor',
    icon: 'dashboard', readCapabilities: PAGE_CAPABILITIES['approval.monitor'] },
  { key: 'report.center', path: '/report/center', component: './report/Center', icon: 'fund',
    readCapabilities: PAGE_CAPABILITIES['report.center'] },
  { key: 'report.view', path: '/report/view', component: './report/Dashboard', icon: 'dashboard',
    readCapabilities: PAGE_CAPABILITIES['report.view'] },
  { key: 'report.export', path: '/report/export', component: './report/Export', icon: 'export',
    readCapabilities: PAGE_CAPABILITIES['report.export'] },
  { key: 'security.roles', path: '/security/roles', component: './security/Role', icon: 'idcard',
    readCapabilities: PAGE_CAPABILITIES['security.roles'] },
  { key: 'security.user-permissions', path: '/security/user-permissions',
    component: './security/UserPermission', icon: 'key',
    readCapabilities: PAGE_CAPABILITIES['security.user-permissions'] },
  { key: 'security.audit-log', path: '/security/audit-log', component: './security/AuditLog',
    icon: 'fileSearch', readCapabilities: PAGE_CAPABILITIES['security.audit-log'] },
  { key: 'security.menu', path: '/security/menu', component: './security/Menu', icon: 'menu',
    readCapabilities: PAGE_CAPABILITIES['security.menu'] },
  { key: 'settings.company', path: '/settings/company', component: './settings/Company',
    icon: 'bank', readCapabilities: PAGE_CAPABILITIES['settings.company'] },
  { key: 'settings.s3', path: '/settings/s3', component: './settings/S3Storage', icon: 'cloud',
    readCapabilities: PAGE_CAPABILITIES['settings.s3'] },
  { key: 'settings.wecom', path: '/settings/wecom', component: './settings/Wecom',
    icon: 'wechat', readCapabilities: PAGE_CAPABILITIES['settings.wecom'] },
  { key: 'settings.identity-providers', path: '/settings/identity-providers',
    component: './settings/IdentityProviders', icon: 'safetyCertificate',
    readCapabilities: PAGE_CAPABILITIES['settings.identity-providers'] },
  { key: 'settings.backup', path: '/settings/backup', component: './settings/Backup',
    icon: 'database', readCapabilities: PAGE_CAPABILITIES['settings.backup'] },
];

export const PAGE_BY_KEY: Record<string, PageDef> = Object.fromEntries(
  PAGES.map((page) => [page.key, page]),
);

/** 隐藏页/设计器/运行时入口的能力映射（不进菜单，只做前端门禁） */
export const HIDDEN_CAPABILITIES: Record<string, string> = {
  canReadForms: 'form:definition:read',
  canCreateForm: 'form:definition:manage',
  canManageOptionSources: 'form:option_source:manage',
  canUseRuntime: 'form:runtime:read',
  canUseTasks: 'workflow:task:read',
  canUseProcesses: 'workflow:instance:read',
};

/** 「任一能力即可」的入口（流程详情：审批人或记录查看者都能打开） */
export const HIDDEN_ANY_OF_CAPABILITIES: Record<string, string[]> = {
  canUseProcessDetail: ['workflow:task:read', 'workflow:instance:read'],
  /** 表单设计器与流程设计器：表单管理员或流程管理员都能进入 */
  canDesigner: ['form:definition:manage', 'workflow:definition:manage'],
};

/** Umi access 的 key（routes.ts 与 access.ts 共用） */
export function accessKey(pageKey: string): string {
  return `can_${pageKey.replace(/[^a-zA-Z0-9]/g, '_')}`;
}

export function pageAllowed(page: PageDef, roles: string[], permissions: string[]): boolean {
  if (roles.includes('admin')) return true;
  return permissions.includes(CONSOLE_ENTRY)
    && page.readCapabilities.every((code) => permissions.includes(code));
}

/** 保持稳定顺序的第一个可访问页面，用于登录后落地。 */
export function firstAccessiblePath(roles: string[], permissions: string[]): string | undefined {
  return PAGES.find((page) => pageAllowed(page, roles, permissions))?.path;
}

export type NavNode = {
  pageKey: string | null;
  type: 'DIR' | 'PAGE';
  name: string | null;
  icon: string | null;
  requiredPermissions: string[] | null;
  sortOrder: number;
  children: NavNode[];
};

/** 后端导航 → ProLayout 菜单；未注册的 pageKey 直接跳过（不出现幽灵菜单）。 */
export function navToMenuData(nodes: NavNode[]): MenuDataItem[] {
  const items: MenuDataItem[] = [];
  nodes.forEach((node, index) => {
    if (node.type === 'DIR') {
      const children = navToMenuData(node.children ?? []);
      if (children.length === 0) return;
      items.push({
        key: `dir-${index}-${node.name ?? ''}`,
        name: node.name ?? '',
        icon: node.icon ?? undefined,
        children,
      });
      return;
    }
    const page = node.pageKey ? PAGE_BY_KEY[node.pageKey] : undefined;
    if (!page) {
      if (process.env.NODE_ENV === 'development') {
        console.warn(`[menu] 未注册的 pageKey 已跳过: ${node.pageKey}`);
      }
      return;
    }
    items.push({
      key: page.key,
      path: page.path,
      name: node.name ?? page.key,
      icon: node.icon ?? page.icon,
    });
  });
  return items;
}
