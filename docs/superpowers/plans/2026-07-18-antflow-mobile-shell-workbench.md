# AntFlow Mobile Shell And Workbench Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 创建独立、可部署的 Ant Design Mobile 应用，交付品牌加载、企业级会话、三栏导航、工作台、应用目录、常用应用和个人中心。

**Architecture:** `mobile/` 使用 feature-first 目录，React Router 管路由，TanStack Query 管服务端状态，BrandProvider 把公开品牌配置收敛为受控 CSS Variables，PlatformAdapter 隔离浏览器与未来企业微信环境。首屏通过 `/api/mobile/bootstrap` 聚合请求渲染，不直接依赖桌面前端代码。

**Tech Stack:** Node 22+, React 19, TypeScript strict, Vite, React Router, Ant Design Mobile, antd-mobile-icons, TanStack Query, Zustand, Vitest, Testing Library, Playwright, Biome.

---

## File Map

```text
mobile/
├── src/app/                 App、路由、Provider、Shell、守卫
├── src/features/auth/       登录、刷新、设备会话
├── src/features/branding/   公共品牌加载与主题
├── src/features/workbench/  工作台、应用目录、常用应用
├── src/features/profile/    我的、账号与安全
├── src/shared/api/          HTTP、DTO、query keys、错误
├── src/shared/platform/     BrowserAdapter 与接口
├── src/shared/storage/      恢复态和安全存储边界
├── src/shared/ui/           页面状态、状态标签、布局组件
├── src/styles/              Token、全局和安全区样式
├── e2e/                     Playwright
├── package.json
├── vite.config.ts
└── vitest.config.ts
```

## Task 1: Scaffold The Independent Mobile Project

**Files:**
- Create: `mobile/package.json`
- Create: `mobile/package-lock.json`
- Create: `mobile/tsconfig*.json`
- Create: `mobile/vite.config.ts`
- Create: `mobile/index.html`
- Create: `mobile/src/main.tsx`
- Create: `mobile/src/vite-env.d.ts`
- Modify: `.gitignore`

- [ ] **Step 1: Generate the Vite React TypeScript project**

From the repository root:

```powershell
npm create vite@latest mobile -- --template react-ts
Set-Location mobile
npm install
npm install antd-mobile antd-mobile-icons @tanstack/react-query react-router-dom zustand
npm install -D @biomejs/biome vitest @vitest/coverage-v8 happy-dom @testing-library/react @testing-library/jest-dom @testing-library/user-event @playwright/test vite-plugin-pwa
```

Do not add yarn/pnpm files.

- [ ] **Step 2: Replace package scripts**

Use:

```json
{
  "scripts": {
    "dev": "vite --host 0.0.0.0 --port 5173",
    "build": "tsc -b && vite build",
    "preview": "vite preview --host 0.0.0.0 --port 5173",
    "lint": "biome lint .",
    "format": "biome check --write .",
    "test": "vitest run",
    "test:watch": "vitest",
    "test:coverage": "vitest run --coverage",
    "test:e2e": "playwright test"
  },
  "engines": {
    "node": ">=22.0.0"
  }
}
```

- [ ] **Step 3: Configure strict TypeScript and `/mobile/` base**

`vite.config.ts`:

```ts
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  base: '/mobile/',
  plugins: [react()],
  server: {
    proxy: {
      '/api': { target: 'http://localhost:8081', changeOrigin: true },
    },
  },
});
```

Keep `strict`, `noUncheckedIndexedAccess` and `noImplicitOverride` enabled in app tsconfig.

- [ ] **Step 4: Ignore generated output and local visual artifacts**

Add only these missing entries:

```gitignore
mobile/node_modules/
mobile/dist/
mobile/coverage/
mobile/playwright-report/
mobile/test-results/
.superpowers/
```

- [ ] **Step 5: Verify and commit**

```powershell
npm run lint
npm test
npm run build
Set-Location ..
git add mobile .gitignore
git commit -m "构建(移动端): 初始化 React 与 Ant Design Mobile 工程"
```

Expected: all commands exit 0 and `mobile/dist/index.html` is generated.

## Task 2: Establish App Providers And Route Boundaries

**Files:**
- Create: `mobile/src/app/App.tsx`
- Create: `mobile/src/app/AppProviders.tsx`
- Create: `mobile/src/app/router.tsx`
- Create: `mobile/src/app/RouteErrorPage.tsx`
- Create: `mobile/src/app/router.test.tsx`
- Create: `mobile/src/test/setup.ts`
- Create: `mobile/vitest.config.ts`
- Modify: `mobile/src/main.tsx`

- [ ] **Step 1: Configure Vitest**

```ts
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'happy-dom',
    setupFiles: ['./src/test/setup.ts'],
    globals: true,
  },
});
```

`setup.ts` imports `@testing-library/jest-dom/vitest` and resets local/session storage after each test.

- [ ] **Step 2: Write route smoke tests**

```tsx
it.each([
  ['/login', '登录 AntFlow'],
  ['/workbench', '工作台'],
  ['/tasks', '待办'],
  ['/profile', '我的'],
])('renders %s', async (path, title) => {
  const router = createTestRouter(path);
  render(<RouterProvider router={router} />);
  expect(await screen.findByText(title)).toBeInTheDocument();
});
```

- [ ] **Step 3: Verify RED**

```powershell
npm test -- src/app/router.test.tsx
```

Expected: failure because router/pages do not exist.

- [ ] **Step 4: Implement providers**

```tsx
const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 30_000, retry: 1, refetchOnWindowFocus: false },
    mutations: { retry: false },
  },
});

export function AppProviders({ children }: PropsWithChildren) {
  return (
    <QueryClientProvider client={queryClient}>
      <BrandProvider>
        <PlatformProvider adapter={browserAdapter}>{children}</PlatformProvider>
      </BrandProvider>
    </QueryClientProvider>
  );
}
```

Use temporary semantic page stubs in router tests; replace them in later tasks.

- [ ] **Step 5: Define lazy routes**

Top-level route objects:

```ts
{ path: '/login', lazy: () => import('../features/auth/LoginPage') }
{ element: <AuthenticatedRoute />, children: [
  { element: <MobileShell />, children: [
    { path: '/workbench', lazy: () => import('../features/workbench/WorkbenchPage') },
    { path: '/tasks', lazy: () => import('../features/tasks/TaskCenterPage') },
    { path: '/profile', lazy: () => import('../features/profile/ProfilePage') },
  ] },
  { path: '/apps', lazy: () => import('../features/workbench/AppCatalogPage') },
  { path: '/apps/favorites', lazy: () => import('../features/workbench/FavoriteAppsPage') },
] }
```

表单和流程详情路径在计划三创建；本计划只固定认证守卫和三栏壳层的路由边界，不在路由测试中引用尚未创建的 feature 模块。

- [ ] **Step 6: Verify and commit**

```powershell
npm test -- src/app/router.test.tsx
npm run build
git add mobile/src/app mobile/src/main.tsx mobile/src/test mobile/vitest.config.ts
git commit -m "功能(移动端): 建立应用 Provider 与路由边界"
```

## Task 3: Build The Typed API Client And Error Model

**Files:**
- Create: `mobile/src/shared/api/http.ts`
- Create: `mobile/src/shared/api/errors.ts`
- Create: `mobile/src/shared/api/queryKeys.ts`
- Create: `mobile/src/shared/api/http.test.ts`
- Create: `mobile/src/shared/api/types.ts`

- [ ] **Step 1: Define the error contract**

```ts
export type ApiErrorBody = {
  code: string;
  message: string;
  traceId?: string;
  fieldErrors?: Array<{ field: string; message: string }>;
  retryAfter?: number;
};

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly body: ApiErrorBody,
  ) {
    super(body.message);
    this.name = 'ApiError';
  }
}
```

- [ ] **Step 2: Write HTTP tests**

Cover JSON success, empty 204, structured error, `401` refresh once, and no automatic retry for POST.

Also assert refresh/logout read the `antflow-csrf` cookie and send it as `X-CSRF-Token`; normal bearer-authenticated business requests do not copy arbitrary cookies into headers.

- [ ] **Step 3: Implement a single fetch wrapper**

```ts
export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(path, {
    ...init,
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      ...authStore.getState().authorizationHeader(),
      ...init.headers,
    },
  });
  if (response.status === 401 && !path.endsWith('/auth/refresh')) {
    await authStore.getState().refresh();
    return apiRequest<T>(path, { ...init, headers: { ...init.headers, 'X-AF-Retry': '1' } });
  }
  if (!response.ok) throw await ApiErrorFactory.fromResponse(response);
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}
```

Guard the retry with `X-AF-Retry` so a failed refresh cannot recurse.

- [ ] **Step 4: Centralize query keys**

```ts
export const queryKeys = {
  branding: ['branding'] as const,
  bootstrap: ['mobile', 'bootstrap'] as const,
  apps: (filters: AppFilters) => ['mobile', 'apps', filters] as const,
  sessions: ['auth', 'sessions'] as const,
};
```

- [ ] **Step 5: Verify and commit**

```powershell
npm test -- src/shared/api/http.test.ts
npm run tsc --if-present
npm run build
git add mobile/src/shared/api
git commit -m "功能(移动端): 增加类型化请求与统一错误模型"
```

Use `npm run build` as the strict TypeScript gate if no standalone `tsc` script exists.

## Task 4: Implement Secure Session Restore And Login

**Files:**
- Create: `mobile/src/features/auth/auth.store.ts`
- Create: `mobile/src/features/auth/auth.api.ts`
- Create: `mobile/src/features/auth/AuthenticatedRoute.tsx`
- Create: `mobile/src/features/auth/LoginPage.tsx`
- Create: `mobile/src/features/auth/LoginPage.test.tsx`
- Create: `mobile/src/features/auth/auth.store.test.ts`

- [ ] **Step 1: Write session tests**

Assert access token stays in Zustand memory, refresh uses cookie credentials, logout clears state/query cache, and returnUrl accepts only same-origin paths.

- [ ] **Step 2: Define auth state**

```ts
type AuthState = {
  status: 'unknown' | 'authenticated' | 'anonymous';
  accessToken: string | null;
  user: MobileUser | null;
  restore(): Promise<void>;
  login(username: string, password: string): Promise<void>;
  refresh(): Promise<void>;
  logout(): Promise<void>;
  authorizationHeader(): Record<string, string>;
};
```

Do not add Zustand persist middleware for accessToken.

- [ ] **Step 3: Implement login UI**

Use Ant Design Mobile `Form`, `Input`, `Button`, `SafeArea` and `Toast`. Required behavior:

- Brand logo/title from BrandProvider.
- Username/password autocomplete attributes.
- Submit loading state.
- Inline required validation.
- Generic invalid-credential message.
- Safe returnUrl after success.

- [ ] **Step 4: Implement route guard**

`unknown` renders a full-screen skeleton; `anonymous` navigates to `/login?returnUrl=...`; authenticated renders `<Outlet />`.

- [ ] **Step 5: Verify and commit**

```powershell
npm test -- src/features/auth
npm run lint
npm run build
git add mobile/src/features/auth
git commit -m "功能(移动端): 实现安全会话恢复与登录"
```

## Task 5: Apply Published Branding Through Controlled Tokens

**Files:**
- Create: `mobile/src/features/branding/branding.api.ts`
- Create: `mobile/src/features/branding/BrandProvider.tsx`
- Create: `mobile/src/features/branding/brandTokens.ts`
- Create: `mobile/src/features/branding/BrandProvider.test.tsx`
- Create: `mobile/src/styles/tokens.css`
- Create: `mobile/src/styles/global.css`
- Modify: `mobile/src/main.tsx`

- [ ] **Step 1: Write token safety tests**

Test valid `#1677ff`, invalid color fallback, document title, favicon update and fixed semantic colors.

- [ ] **Step 2: Define immutable fallback branding**

```ts
export const fallbackBranding: PublicBranding = {
  version: 'builtin-1',
  appName: 'AntFlow 审批',
  companyName: 'AntFlow',
  primaryColor: '#1677ff',
  mobileHeaderTitle: '工作台',
  loginTitle: '登录 AntFlow',
  showLoginFooter: true,
  footerText: '© 2026 AntFlow',
};
```

- [ ] **Step 3: Set only allowlisted CSS variables**

```ts
export function applyBrandTokens(branding: PublicBranding) {
  const primary = isHexColor(branding.primaryColor) ? branding.primaryColor : '#1677ff';
  const root = document.documentElement;
  root.style.setProperty('--af-color-primary', primary);
  root.style.setProperty('--adm-color-primary', primary);
  document.title = branding.appName;
}
```

Never accept server-provided arbitrary CSS.

Set or update a `<link rel="manifest">` element to `/api/public/branding/manifest.webmanifest?v=${branding.version}` so installed PWA metadata follows only published branding.

- [ ] **Step 4: Define fixed semantic tokens**

`tokens.css` must include success `#31a354`, warning `#fa8c16`, danger `#ff4d4f`, text `#202830`, background `#f4f6f8`, surface `#fff`, surface radius `8px`, control radius `6px`.

- [ ] **Step 5: Verify and commit**

```powershell
npm test -- src/features/branding
npm run build
git add mobile/src/features/branding mobile/src/styles mobile/src/main.tsx
git commit -m "功能(移动端): 接入可发布品牌与受控主题变量"
```

## Task 6: Build The Three-Tab Mobile Shell

**Files:**
- Create: `mobile/src/app/MobileShell.tsx`
- Create: `mobile/src/app/MobileShell.test.tsx`
- Create: `mobile/src/app/MobileShell.module.css`
- Create: `mobile/src/shared/ui/AppPage.tsx`
- Create: `mobile/src/shared/ui/PageStates.tsx`

- [ ] **Step 1: Write navigation tests**

Assert three tabs exactly, task badge rendering, active route state and safe-area CSS class.

- [ ] **Step 2: Implement stable tab configuration**

```ts
const tabs = [
  { key: '/workbench', title: '工作台', icon: <AppOutline /> },
  { key: '/tasks', title: '待办', icon: <CheckShieldOutline />, badge: pendingCount || undefined },
  { key: '/profile', title: '我的', icon: <UserOutline /> },
] as const;
```

Use `TabBar` and React Router navigation. Do not render the shell on detail/form routes.

- [ ] **Step 3: Lock dimensions**

```css
.content {
  min-height: 100dvh;
  padding-bottom: calc(56px + env(safe-area-inset-bottom));
  background: var(--af-color-bg);
}
.tabBar {
  position: fixed;
  inset-inline: 0;
  bottom: 0;
  height: calc(56px + env(safe-area-inset-bottom));
  background: var(--af-color-surface);
  border-top: 1px solid var(--af-color-border);
}
```

- [ ] **Step 4: Add reusable page states**

Create `PageSkeleton`, `PageEmpty`, `PageError` and `OfflineBanner` with 44px minimum actions and no descriptive feature marketing copy.

- [ ] **Step 5: Verify and commit**

```powershell
npm test -- src/app/MobileShell.test.tsx
npm run lint
npm run build
git add mobile/src/app mobile/src/shared/ui
git commit -m "功能(移动端): 建立三栏导航与页面状态框架"
```

## Task 7: Implement Workbench Bootstrap

**Files:**
- Create: `mobile/src/features/workbench/workbench.api.ts`
- Create: `mobile/src/features/workbench/WorkbenchPage.tsx`
- Create: `mobile/src/features/workbench/WorkbenchPage.test.tsx`
- Create: `mobile/src/features/workbench/workbench.module.css`
- Create: `mobile/src/features/workbench/components/AppGrid.tsx`
- Create: `mobile/src/features/workbench/components/RecentProcesses.tsx`

- [ ] **Step 1: Define bootstrap types**

```ts
export type MobileBootstrap = {
  user: MobileUser;
  pendingCount: number;
  favoriteApps: MobileApp[];
  recentProcesses: RecentProcess[];
  brandingVersion: string;
};
```

- [ ] **Step 2: Write page tests**

Assert greeting, max eight apps, max three processes, badge propagation and loading/error/empty states.

- [ ] **Step 3: Implement one bootstrap query**

```ts
export function useBootstrap() {
  return useQuery({
    queryKey: queryKeys.bootstrap,
    queryFn: () => apiRequest<MobileBootstrap>('/api/mobile/bootstrap'),
  });
}
```

- [ ] **Step 4: Build the approved A layout**

Structure: quiet header, compact greeting band, un-nested app surface, recent process surface. Cards use 8px radius and minimal/no shadow. App tiles have stable 48px icon containers and 44px tap areas.

- [ ] **Step 5: Verify and commit**

```powershell
npm test -- src/features/workbench/WorkbenchPage.test.tsx
npm run lint
npm run build
git add mobile/src/features/workbench
git commit -m "功能(移动端): 实现应用入口型工作台"
```

## Task 8: Implement App Catalog And Favorite Ordering

**Files:**
- Create: `mobile/src/features/workbench/AppCatalogPage.tsx`
- Create: `mobile/src/features/workbench/FavoriteAppsPage.tsx`
- Create: `mobile/src/features/workbench/apps.store.ts`
- Create: `mobile/src/features/workbench/AppCatalogPage.test.tsx`
- Create: `mobile/src/features/workbench/FavoriteAppsPage.test.tsx`

- [ ] **Step 1: Test search, categories and max favorites**

Use real Chinese app names and assert selecting a ninth favorite is blocked with `最多添加 8 个常用应用`.

- [ ] **Step 2: Implement catalog queries**

Search is debounced 250ms. Query key includes `{ keyword, category }`. Categories use backend codes and display labels, with uncategorized apps mapped to `其他`.

- [ ] **Step 3: Implement favorite editing state**

```ts
type FavoriteDraftState = {
  ids: number[];
  add(id: number): void;
  remove(id: number): void;
  move(from: number, to: number): void;
  reset(ids: number[]): void;
};
```

Persist only on the explicit 完成 action; on failure retain the draft state.

- [ ] **Step 4: Invalidate bootstrap after save**

```ts
await apiRequest('/api/mobile/preferences/apps', {
  method: 'PUT',
  body: JSON.stringify({ formIds: ids }),
});
await queryClient.invalidateQueries({ queryKey: queryKeys.bootstrap });
```

- [ ] **Step 5: Verify and commit**

```powershell
npm test -- src/features/workbench
npm run build
git add mobile/src/features/workbench
git commit -m "功能(移动端): 增加应用目录与常用应用排序"
```

## Task 9: Implement Profile And Device Sessions

**Files:**
- Create: `mobile/src/features/profile/ProfilePage.tsx`
- Create: `mobile/src/features/profile/SecurityPage.tsx`
- Create: `mobile/src/features/profile/profile.api.ts`
- Create: `mobile/src/features/profile/ProfilePage.test.tsx`
- Create: `mobile/src/features/profile/SecurityPage.test.tsx`

- [ ] **Step 1: Write tests**

Cover profile summary, draft link, session list, revoke one device, logout current device and hidden WeCom binding when feature flag is false.

- [ ] **Step 2: Implement profile from bootstrap**

Reuse bootstrap user and counts; do not issue another user request on normal navigation.

- [ ] **Step 3: Implement session queries**

```ts
export const getSessions = () => apiRequest<DeviceSession[]>('/api/auth/sessions');
export const revokeSession = (id: string) =>
  apiRequest<void>(`/api/auth/sessions/${id}`, { method: 'DELETE' });
```

- [ ] **Step 4: Implement logout cleanup**

Logout must clear auth state, QueryClient, local recovery drafts scoped to the user, and navigate with replace to `/login`.

- [ ] **Step 5: Verify and commit**

```powershell
npm test -- src/features/profile
npm run lint
npm run build
git add mobile/src/features/profile
git commit -m "功能(移动端): 实现个人中心与设备会话管理"
```

## Task 10: Add Browser Platform Adapter

**Files:**
- Create: `mobile/src/shared/platform/PlatformAdapter.ts`
- Create: `mobile/src/shared/platform/BrowserAdapter.ts`
- Create: `mobile/src/shared/platform/PlatformProvider.tsx`
- Create: `mobile/src/shared/platform/BrowserAdapter.test.ts`

- [ ] **Step 1: Define the stable interface**

```ts
export interface PlatformAdapter {
  readonly kind: 'browser' | 'wecom';
  trySilentLogin(): Promise<null>;
  openFile(file: MobileFile): Promise<void>;
  closePage(): void;
  getEnvironment(): { standalone: boolean; userAgent: string };
}
```

- [ ] **Step 2: Implement browser behavior**

`openFile` opens a same-origin signed URL with `noopener,noreferrer`; `closePage` uses history.back when possible and falls back to `/mobile/`; `trySilentLogin` returns null.

- [ ] **Step 3: Verify and commit**

```powershell
npm test -- src/shared/platform
npm run build
git add mobile/src/shared/platform
git commit -m "功能(移动端): 预留企业微信平台适配边界"
```

## Task 11: Add Responsive And Accessibility Tests

**Files:**
- Create: `mobile/src/styles/responsive.css`
- Create: `mobile/src/shared/ui/accessibility.test.tsx`
- Create: `mobile/e2e/shell-visual.spec.ts`
- Create: `mobile/playwright.config.ts`

- [ ] **Step 1: Add viewport projects**

```ts
projects: [
  { name: 'android-360', use: { viewport: { width: 360, height: 800 } } },
  { name: 'iphone-375', use: { viewport: { width: 375, height: 812 } } },
  { name: 'iphone-390', use: { viewport: { width: 390, height: 844 } } },
  { name: 'iphone-430', use: { viewport: { width: 430, height: 932 } } },
]
```

- [ ] **Step 2: Test stable shell dimensions**

Assert tab items have at least 44px bounding boxes, no horizontal overflow, active tab is visible, and primary content is not hidden behind the tab bar.

- [ ] **Step 3: Capture visual baselines**

Capture login, workbench, app catalog and profile. Use deterministic API fixtures and disable animation during screenshot tests.

- [ ] **Step 4: Verify and commit**

```powershell
npx playwright install chromium
npm run test:e2e -- shell-visual.spec.ts
npm test
npm run build
git add mobile/src/styles mobile/src/shared/ui mobile/e2e mobile/playwright.config.ts
git commit -m "测试(移动端): 覆盖主壳响应式与可访问性"
```

## Task 12: Add Mobile Build To CI And Deployment Notes

**Files:**
- Modify: `.github/workflows/ci.yml`
- Create: `infra/mobile-nginx.example.conf`
- Create: `mobile/README.md`

- [ ] **Step 1: Add a blocking mobile CI job**

Run `npm ci`, `npm run lint`, `npm test`, `npm run build` from `mobile/` on Node 22. Cache `mobile/package-lock.json` separately from frontend.

- [ ] **Step 2: Add same-origin routing example**

```nginx
location /mobile/ {
    try_files $uri $uri/ /mobile/index.html;
}
location /api/ {
    proxy_pass http://antflow-backend:8081;
}
```

- [ ] **Step 3: Document local and production commands**

README must state backend 8081, mobile 5173, `/mobile/` base, brand fallback behavior, and that enterprise WeChat is phase two.

- [ ] **Step 4: Verify and commit**

```powershell
Set-Location mobile
npm ci
npm run lint
npm test
npm run build
Set-Location ..
git add .github/workflows/ci.yml infra/mobile-nginx.example.conf mobile/README.md
git commit -m "构建(移动端): 接入持续集成与同域部署示例"
```

## Completion Gate

The approval workflow plan may start only when:

- `mobile/` installs with npm and all strict checks pass.
- Access token is memory-only and refresh rotation works.
- Published branding applies safely with fallback.
- Exactly three bottom tabs render with stable safe-area dimensions.
- Workbench uses one bootstrap query.
- App search/favorites persist and invalidate bootstrap.
- Profile/session revoke/logout work.
- BrowserAdapter tests pass and no page contains WeCom-specific branching.
- Four target viewport screenshots are non-overlapping.
- Mobile CI is blocking.
