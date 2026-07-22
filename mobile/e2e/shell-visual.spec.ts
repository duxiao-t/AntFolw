import { expect, test, type Page } from '@playwright/test';

const branding = {
  version: 'e2e-1',
  appName: 'AntFlow 审批',
  companyName: '安',
  primaryColor: '#1677ff',
  mobileHeaderTitle: '工作台',
  loginTitle: '登录 AntFlow',
  showLoginFooter: true,
  footerText: 'AntFlow',
};

const bootstrap = {
  user: { id: 1, username: 'admin', displayName: '管理员', roles: ['admin'] },
  pendingCount: 2,
  favoriteApps: [
    { formId: 1, code: 'leave', name: '请假申请', category: 'hr', categoryLabel: '人事' },
    { formId: 2, code: 'expense', name: '费用报销', category: 'finance', categoryLabel: '财务' },
  ],
  recentProcesses: [
    {
      instanceId: 1,
      formCode: 'leave',
      formTitle: '年假申请',
      status: 'RUNNING',
      updatedAt: '2026-07-20T08:00:00Z',
    },
  ],
  brandingVersion: 'e2e-1',
};

const apps = [
  { formId: 1, code: 'leave', name: '请假申请', category: 'hr', categoryLabel: '人事' },
  { formId: 2, code: 'expense', name: '费用报销', category: 'finance', categoryLabel: '财务' },
  { formId: 3, code: 'travel', name: '出差审批', category: 'admin', categoryLabel: '行政' },
];

async function mockApi(page: Page) {
  await page.addInitScript(() => {
    const fixedNow = new Date('2026-07-20T12:00:00+08:00').getTime();
    const RealDate = Date;
    class FixedDate extends RealDate {
      constructor(value?: string | number | Date) {
        if (value === undefined) {
          super(fixedNow);
        } else {
          super(value);
        }
      }

      static override now() {
        return fixedNow;
      }
    }
    globalThis.Date = FixedDate as DateConstructor;
    const style = document.createElement('style');
    style.textContent = '*, *::before, *::after { animation: none !important; transition: none !important; }';
    document.documentElement.appendChild(style);
  });
  await page.route('**/api/public/branding', async (route) => {
    await route.fulfill({ json: branding });
  });
  await page.route('**/api/auth/login', async (route) => {
    await route.fulfill({
      json: { accessToken: 'e2e-token', user: bootstrap.user },
    });
  });
  await page.route('**/api/auth/refresh', async (route) => {
    // Anonymous until explicit login — keep AuthBootstrap from auto-signing-in.
    await route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'TOKEN_EXPIRED', message: '会话已过期' }),
    });
  });
  await page.route('**/api/mobile/bootstrap', async (route) => {
    expect(route.request().headers().authorization).toBe('Bearer e2e-token');
    await route.fulfill({ json: bootstrap });
  });
  await page.route('**/api/mobile/apps**', async (route) => {
    expect(route.request().headers().authorization).toBe('Bearer e2e-token');
    await route.fulfill({ json: apps });
  });
}

async function signIn(page: Page, returnUrl: string) {
  await page.goto(`/mobile/login?returnUrl=${encodeURIComponent(returnUrl)}`);
  await page.getByPlaceholder('请输入账号').fill('admin');
  await page.getByPlaceholder('请输入密码').fill('ant.design');
  await page.getByRole('button', { name: '登录' }).click();
}

async function expectNoHorizontalOverflow(page: Page) {
  const widths = await page.evaluate(() => {
    const viewport = window.innerWidth;
    const elements = Array.from(document.querySelectorAll('body, #root, main, nav, .app-page, .page'));
    return elements.map((element) => {
      const rect = element.getBoundingClientRect();
      return {
        tag: element.tagName.toLowerCase(),
        className: String(element.getAttribute('class') ?? ''),
        left: rect.left,
        right: rect.right,
        viewport,
      };
    });
  });
  for (const width of widths) {
    expect(width.left).toBeGreaterThanOrEqual(-1);
    expect(width.right).toBeLessThanOrEqual(width.viewport + 1);
  }
}

test.beforeEach(async ({ page }) => {
  await mockApi(page);
});

test('shell dimensions stay usable across mobile viewports', async ({ page }) => {
  await signIn(page, '/workbench');
  await expect(page.getByTestId('workbench')).toBeVisible();

  await expectNoHorizontalOverflow(page);
  const nav = page.getByRole('navigation', { name: '主导航' });
  await expect(nav).toBeVisible();
  const tabBoxes = await nav.locator('.adm-tab-bar-item').evaluateAll((items) =>
    items.map((item) => {
      const rect = item.getBoundingClientRect();
      return { width: rect.width, height: rect.height };
    }),
  );
  expect(tabBoxes).toHaveLength(3);
  for (const box of tabBoxes) {
    expect(box.width).toBeGreaterThanOrEqual(44);
    expect(box.height).toBeGreaterThanOrEqual(44);
  }

  const mainBottom = await page.locator('main').boundingBox();
  const navBox = await nav.boundingBox();
  expect(mainBottom).not.toBeNull();
  expect(navBox).not.toBeNull();
  expect(mainBottom!.y + mainBottom!.height).toBeLessThanOrEqual(navBox!.y);
});

test('captures deterministic key page screenshots', async ({ page }) => {
  await page.goto('/mobile/login');
  await expect(page.getByRole('heading', { name: '登录 AntFlow' })).toBeVisible();
  await expect(page).toHaveScreenshot('login.png', { fullPage: true });

  await signIn(page, '/workbench');
  await expect(page.getByTestId('workbench')).toBeVisible();
  await expectNoHorizontalOverflow(page);
  await expect(page).toHaveScreenshot('workbench.png', { fullPage: true });

  await signIn(page, '/apps');
  await expect(page.getByRole('heading', { name: '应用目录' })).toBeVisible();
  await expectNoHorizontalOverflow(page);
  await expect(page).toHaveScreenshot('app-catalog.png', { fullPage: true });

  await signIn(page, '/profile');
  await expect(page.getByRole('heading', { name: '我的' })).toBeVisible();
  await expectNoHorizontalOverflow(page);
  await expect(page).toHaveScreenshot('profile.png', { fullPage: true });
});
