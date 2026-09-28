import type { RunTimeLayoutConfig } from '@@/plugin-layout/types.d';
import type { RequestConfig } from '@@/plugin-request/request';
import { LinkOutlined } from '@ant-design/icons';
import type { MenuDataItem, Settings as LayoutSettings } from '@ant-design/pro-components';
import { SettingDrawer } from '@ant-design/pro-components';
import { history, Link, useModel } from '@umijs/max';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import React from 'react';
import Exception403 from '@/pages/exception/403';

// Initialize dayjs plugins globally
dayjs.extend(relativeTime);

import { request as umiRequest } from '@umijs/max';
import {
  AvatarDropdown,
  DocLink,
  ErrorBoundary,
  Footer,
  LangDropdown,
  OfflineBanner,
  VersionDropdown,
} from '@/components';
import { WorkflowEventsSubscriber } from '@/components/WorkflowEventsSubscriber';
import { CAPABILITY, hasCapability } from './authz';
import { navToMenuData, PAGE_BY_KEY, type NavNode } from '@/pages/registry';
import { menuIcon } from '@/pages/menuIcons';
import defaultSettings from '../config/defaultSettings';
import { errorConfig } from './requestErrorConfig';

const isDev = process.env.NODE_ENV === 'development' || process.env.UMI_ENV === 'dev';
const loginPath = '/user/login';
const TOKEN_KEY = 'antflow-token';
const CSRF_COOKIE_NAME = process.env.ANTFLOW_AUTH_CSRF_COOKIE_NAME || 'antflow-csrf';

async function restoreCookieSession(): Promise<boolean> {
  const csrf = document.cookie.split('; ')
    .find((entry) => entry.startsWith(`${CSRF_COOKIE_NAME}=`))?.split('=').slice(1).join('=');
  if (!csrf) return false;
  try {
    const response = await fetch('/api/auth/refresh', {
      method: 'POST', credentials: 'include', headers: { 'X-CSRF-Token': decodeURIComponent(csrf) },
    });
    if (!response.ok) return false;
    const payload = await response.json() as { accessToken?: string };
    if (!payload.accessToken) return false;
    localStorage.setItem(TOKEN_KEY, payload.accessToken);
    return true;
  } catch {
    return false;
  }
}

function AuthzRefresh() {
  const { initialState, setInitialState } = useModel('@@initialState');
  const refreshing = React.useRef<Promise<void> | null>(null);
  const refresh = React.useCallback(() => {
    if (!initialState?.currentUser || !initialState.fetchUserInfo) return;
    if (!refreshing.current) {
      refreshing.current = Promise.all([
        initialState.fetchUserInfo(),
        fetchNavigation(),
      ]).then(([currentUser, navigation]) => {
        // 菜单编排在服务端：能力变更或管理员改菜单后需要一起刷新。
        setInitialState((state: any) => ({
          ...state,
          currentUser: currentUser ?? state.currentUser,
          navigation: navigation ?? state.navigation,
        }));
      }).finally(() => { refreshing.current = null; });
    }
  }, [initialState, setInitialState]);
  React.useEffect(() => {
    window.addEventListener('focus', refresh);
    window.addEventListener('antflow:refresh-authz', refresh);
    return () => {
      window.removeEventListener('focus', refresh);
      window.removeEventListener('antflow:refresh-authz', refresh);
    };
  }, [refresh]);
  return null;
}

/** 当前用户可见菜单（服务端按能力过滤；未注册 pageKey 由 navToMenuData 跳过）。 */
async function fetchNavigation(): Promise<NavNode[] | undefined> {
  try {
    if (!localStorage.getItem(TOKEN_KEY)) return undefined;
    return await umiRequest<NavNode[]>('/api/navigation', { skipErrorHandler: true });
  } catch {
    return undefined;
  }
}

/**
 * @see https://umijs.org/docs/api/runtime-config#getinitialstate
 * */
export async function getInitialState(): Promise<{
  settings?: Partial<LayoutSettings>;
  currentUser?: API.CurrentUser;
  navigation?: NavNode[];
  loading?: boolean;
  fetchUserInfo?: () => Promise<API.CurrentUser | undefined>;
  settingDrawerOpen?: boolean;
}> {
  const fetchUserInfo = async () => {
    try {
      if (!localStorage.getItem(TOKEN_KEY)) await restoreCookieSession();
      // AntFlow: hit our backend instead of the upstream mock.
      // skipErrorHandler=true keeps GlobalExceptionHandler from showing toast on 401.
      const me = await umiRequest<API.CurrentUser>('/api/auth/me', {
        skipErrorHandler: true,
      });
      // Mark admin status for access.ts gating.
      return {
        ...me,
        access: (me as any).roles?.includes('admin') ? 'admin' : 'user',
        name: (me as any).displayName ?? (me as any).username,
      } as API.CurrentUser;
    } catch (_error) {
      const { pathname, search, hash } = window.location;
      history.replace(
        `${loginPath}?redirect=${encodeURIComponent(pathname + search + hash)}`,
      );
    }
    return undefined;
  };
  // 如果不是登录页面，执行
  const { location } = window;
  if (
    ![loginPath, '/user/register', '/user/register-result'].includes(
      location.pathname,
    )
  ) {
    const currentUser = await fetchUserInfo();
    const navigation = currentUser ? await fetchNavigation() : undefined;
    return {
      fetchUserInfo,
      currentUser,
      navigation,
      settings: defaultSettings as Partial<LayoutSettings>,
      settingDrawerOpen: false,
    };
  }
  return {
    fetchUserInfo,
    settings: defaultSettings as Partial<LayoutSettings>,
    settingDrawerOpen: false,
  };
}

/**
 * 隐藏页（设计器、向导等）不在后端菜单里，ProLayout 无从推导，只能显式补标题与父级。
 * 父级路径可点，是为了让子页有明确的返回入口。
 */
const HIDDEN_BREADCRUMBS: Array<{ test: RegExp; parent?: [string, string]; title: string }> = [
  { test: /^\/approval\/forms\/new(\/|$)/, parent: ['/approval/forms', '表单管理'], title: '表单向导' },
  { test: /^\/approval\/forms\/[^/]+\/wizard(\/|$)/, parent: ['/approval/forms', '表单管理'], title: '表单向导' },
  { test: /^\/approval\/option-sources(\/|$)/, parent: ['/approval/forms', '表单管理'], title: '选项数据源' },
  { test: /^\/(approval|admin)\/form-data(\/|$)/, parent: ['/approval/forms', '表单管理'], title: '提交数据' },
  { test: /^\/approval\/templates(\/|$)/, parent: ['/approval/forms', '表单管理'], title: '模板库' },
  { test: /^\/designer\/form\/[^/]+(\/|$)/, parent: ['/approval/forms', '表单管理'], title: '表单设计器' },
  { test: /^\/designer\/process\/[^/]+(\/|$)/, parent: ['/approval/forms', '表单管理'], title: '流程设计器' },
  { test: /^\/approval\/designer(\/|$)/, parent: ['/approval/forms', '表单管理'], title: '流程设计器' },
  { test: /^\/proc\/[^/]+(\/|$)/, parent: ['/approval/records', '审批记录'], title: '申请详情' },
  { test: /^\/runtime\/form\//, title: '填写表单' },
  { test: /^\/runtime\/list(\/|$)/, title: '已提交表单' },
  { test: /^\/tasks\/(inbox|done)(\/|$)/, title: '我的任务' },
  { test: /^\/account\/(settings|center)(\/|$)/, title: '个人设置' },
];

/** 菜单页的中文名只有一个真源：后端菜单。按路径回查，避免在前端再抄一份。 */
function findMenuTitle(navigation: NavNode[], pathname: string): string | undefined {
  for (const node of navigation) {
    const page = node.pageKey ? PAGE_BY_KEY[node.pageKey] : undefined;
    if (page && (pathname === page.path || pathname.startsWith(`${page.path}/`))) {
      return node.name ?? undefined;
    }
    const nested = findMenuTitle(node.children ?? [], pathname);
    if (nested) return nested;
  }
  return undefined;
}

/**
 * 菜单项里的 icon 是键字符串（registry 与后端都只存键），ProLayout 会把不认识的字符串
 * 当文本渲染出来。这里统一换成真图标；未知键置空，绝不漏出英文。
 */
function withMenuIcons(items: MenuDataItem[]): MenuDataItem[] {
  return items.map((item) => ({
    ...item,
    icon: menuIcon(item.icon as string | undefined) ?? undefined,
    children: item.children ? withMenuIcons(item.children) : undefined,
  }));
}

/** 第一项永远是可点的工作台，这样子页都有返回入口（ProLayout 默认不给）。 */
function breadcrumbItems(pathname: string, navigation: NavNode[]) {
  const home = { title: <Link to="/workplace">工作台</Link> };
  const hidden = HIDDEN_BREADCRUMBS.find((entry) => entry.test.test(pathname));
  if (hidden) {
    return [
      home,
      ...(hidden.parent
        ? [{ title: <Link to={hidden.parent[0]}>{hidden.parent[1]}</Link> }]
        : []),
      { title: hidden.title },
    ];
  }
  const title = findMenuTitle(navigation, pathname);
  return title && pathname !== '/workplace' ? [home, { title }] : [home];
}

// ProLayout 支持的api https://procomponents.ant.design/components/layout
export const layout: RunTimeLayoutConfig = ({
  initialState,
  setInitialState,
}) => {
  const workflowEventsEnabled = hasCapability(
    initialState?.currentUser,
    CAPABILITY.workflowTaskRead,
  );
  return {
    // 菜单来自服务端编排（t_menu + 能力过滤），未注册 pageKey 会被跳过。
    menuDataRender: () => withMenuIcons(navToMenuData(initialState?.navigation ?? [])),
    // 面包屑自己拼：ProLayout 默认因 minLength=2 且 /approval 无父节点而整条不显示，
    // 隐藏页也永远不在菜单里。这里保证每页至少有一条可点的工作台。
    breadcrumbProps: { minLength: 1 },
    breadcrumbRender: () =>
      breadcrumbItems(window.location.pathname, initialState?.navigation ?? []),
    menuItemRender: (item, dom) => {
      if (item.path) {
        return (
          <Link to={item.path} prefetch>
            {dom}
          </Link>
        );
      }
      return dom;
    },
    actionsRender: () => {
      // `locale: false` opts out of the language switcher. ProLayout's own
      // `locale` prop is a locale string, so narrow to the boolean toggle here.
      const localeEnabled =
        (initialState?.settings as { locale?: boolean })?.locale !== false;
      return [
        <DocLink key="doc" />,
        <VersionDropdown key="version" />,
        localeEnabled && <LangDropdown key="lang" />,
      ].filter(Boolean);
    },
    avatarProps: {
      src: initialState?.currentUser?.avatar,
      title: initialState?.currentUser?.name
        ?? initialState?.currentUser?.displayName
        ?? initialState?.currentUser?.username
        ?? '当前用户',
      render: (_, avatarChildren) => (
        <AvatarDropdown>{avatarChildren}</AvatarDropdown>
      ),
    },
    // waterMarkProps: {
    //   content: initialState?.currentUser?.name,
    // },
    footerRender: () => <Footer />,
    onPageChange: () => {
      const { location } = window;
      // 如果没有登录，重定向到 login
      if (!initialState?.currentUser && location.pathname !== loginPath) {
        history.replace(
          `${loginPath}?redirect=${encodeURIComponent(location.pathname + location.search + location.hash)}`,
        );
      }
    },
    links: isDev
      ? [
          <Link key="openapi" to="/umi/plugin/openapi" target="_blank">
            <LinkOutlined />
            <span>OpenAPI 文档</span>
          </Link>,
        ]
      : [],
    // Replace ProLayout's default ErrorBoundary with our offline-aware version,
    // so chunk load errors show friendly messages instead of "Something went wrong."
    ErrorBoundary,
    menuHeaderRender: undefined,
    // 自定义 403 页面
    unAccessible: <Exception403 />,
    // 增加一个 loading 的状态
    childrenRender: (children) => {
      // if (initialState?.loading) return <PageLoading />;
      return (
        <>
          <AuthzRefresh />
          <WorkflowEventsSubscriber enabled={workflowEventsEnabled} />
          {children}
          <SettingDrawer
            disableUrlParams
            enableDarkTheme
            collapse={initialState?.settingDrawerOpen}
            onCollapseChange={(open) => {
              setInitialState((s) => ({
                ...s,
                settingDrawerOpen: open,
              }));
            }}
            settings={initialState?.settings}
            onSettingChange={(settings) => {
              setInitialState((s) => ({
                ...s,
                settings,
              }));
            }}
          />
        </>
      );
    },
    ...initialState?.settings,
  };
};

/**
 * @name request 配置，可以配置错误处理
 * 它基于 axios 提供了一套统一的网络请求和错误处理方案。
 * @doc https://umijs.org/docs/max/request#配置
 */
export const request: RequestConfig = {
  // AntFlow endpoints are called with explicit /api paths. Keep baseURL empty
  // so Umi does not turn /api/auth/login into /api/api/auth/login.
  baseURL: '',
  ...errorConfig,
};

export function rootContainer(container: React.ReactNode) {
  return (
    <>
      <OfflineBanner />
      <ErrorBoundary>{container}</ErrorBoundary>
    </>
  );
}
