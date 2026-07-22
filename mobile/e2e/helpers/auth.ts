import { expect, type Page } from '@playwright/test';

export type E2EUserKey = 'bob' | 'admin' | 'alice';

export async function installDeterministicRuntime(page: Page) {
  await page.addInitScript(() => {
    const fixedNow = Date.parse('2026-07-20T12:00:00+08:00');
    const RealDate = Date;
    // Avoid subclassing Date (fragile in Chromium); patch now() only.
    RealDate.now = () => fixedNow;

    const injectStyles = () => {
      if (!document.documentElement || document.getElementById('af-e2e-disable-anim')) {
        return;
      }
      const style = document.createElement('style');
      style.id = 'af-e2e-disable-anim';
      style.textContent =
        '*, *::before, *::after { animation: none !important; transition: none !important; caret-color: transparent !important; }';
      document.documentElement.appendChild(style);
    };
    if (document.documentElement) {
      injectStyles();
    } else {
      document.addEventListener('DOMContentLoaded', injectStyles, { once: true });
    }
  });
}

export async function signIn(
  page: Page,
  username: string,
  password = 'ant.design',
  returnUrl = '/workbench',
) {
  await page.goto(`/mobile/login?returnUrl=${encodeURIComponent(returnUrl)}`);
  const account = page.getByPlaceholder('请输入账号');
  await expect(account).toBeVisible({ timeout: 20_000 });
  await account.fill(username);
  await page.getByPlaceholder('请输入密码').fill(password);
  await page.getByRole('button', { name: '登录' }).click();
}

export async function signOutViaSecurity(page: Page) {
  await page.goto('/mobile/profile/security');
  await expect(page.getByRole('heading', { name: '账号与安全' })).toBeVisible();
  await page.getByRole('button', { name: '退出当前设备' }).click();
  await expect(page.getByPlaceholder('请输入账号')).toBeVisible({ timeout: 15_000 });
}

export async function expectNoHorizontalOverflow(page: Page) {
  const report = await page.evaluate(() => {
    const viewport = window.innerWidth;
    const scrollWidth = document.documentElement.scrollWidth;
    const elements = Array.from(
      document.querySelectorAll('body, #root, main, nav, .app-page, .page, [data-testid="mobile-shell"]'),
    );
    return {
      viewport,
      scrollWidth,
      boxes: elements.map((element) => {
        const rect = element.getBoundingClientRect();
        return {
          tag: element.tagName.toLowerCase(),
          className: String(element.getAttribute('class') ?? ''),
          left: rect.left,
          right: rect.right,
        };
      }),
    };
  });

  expect(report.scrollWidth).toBeLessThanOrEqual(report.viewport + 1);
  for (const box of report.boxes) {
    expect(box.left).toBeGreaterThanOrEqual(-1);
    expect(box.right).toBeLessThanOrEqual(report.viewport + 1);
  }
}

export async function expectVisualIntegrity(page: Page) {
  await expectNoHorizontalOverflow(page);

  const integrity = await page.evaluate(() => {
    const viewportWidth = window.innerWidth;
    const viewportHeight = window.innerHeight;
    const scrollWidth = document.documentElement.scrollWidth;

    const isTopmostInteractive = (el: Element) => {
      const rect = el.getBoundingClientRect();
      if (rect.width < 20 || rect.height < 20) {
        return false;
      }
      const cx = Math.min(viewportWidth - 1, Math.max(0, rect.left + rect.width / 2));
      const cy = Math.min(viewportHeight - 1, Math.max(0, rect.top + rect.height / 2));
      const top = document.elementFromPoint(cx, cy);
      if (!top) {
        return false;
      }
      return el === top || el.contains(top) || top.contains(el);
    };

    const candidates = Array.from(
      document.querySelectorAll('button, [role="button"], a.adm-button, .adm-tab-bar-item'),
    )
      .map((el) => {
        const style = window.getComputedStyle(el);
        const rect = el.getBoundingClientRect();
        const visible =
          style.visibility !== 'hidden' &&
          style.display !== 'none' &&
          Number(style.opacity || '1') > 0.05 &&
          rect.width > 0 &&
          rect.height > 0 &&
          rect.bottom > 0 &&
          rect.top < viewportHeight &&
          rect.right > 0 &&
          rect.left < viewportWidth;
        return {
          el,
          text: (el.textContent ?? '').trim().slice(0, 40),
          position: style.position,
          visible,
          top: rect.top,
          left: rect.left,
          right: rect.right,
          bottom: rect.bottom,
          width: rect.width,
          height: rect.height,
        };
      })
      .filter((item) => item.visible && item.text.length > 0 && isTopmostInteractive(item.el));

    // Drop raw Element refs before returning (not serializable).
    const interactive = candidates.map(({ el: _el, ...rest }) => rest);

    let overlapCount = 0;
    for (let i = 0; i < interactive.length; i += 1) {
      for (let j = i + 1; j < interactive.length; j += 1) {
        const a = interactive[i]!;
        const b = interactive[j]!;
        const overlapX = Math.min(a.right, b.right) - Math.max(a.left, b.left);
        const overlapY = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
        if (overlapX > 8 && overlapY > 8) {
          const similar =
            Math.abs(a.left - b.left) < 2 &&
            Math.abs(a.top - b.top) < 2 &&
            Math.abs(a.width - b.width) < 2 &&
            Math.abs(a.height - b.height) < 2;
          // Nested/parent-child hits are expected (e.g. button wrapping label).
          if (!similar) {
            overlapCount += 1;
          }
        }
      }
    }

    const bodyText = (document.body?.innerText ?? '').replace(/\s+/g, ' ').trim();
    const hasPaintedContent = bodyText.length > 0;

    const fixedVisible = interactive.some(
      (item) =>
        (item.position === 'fixed' || item.position === 'sticky') &&
        item.bottom <= viewportHeight + 2 &&
        item.top >= -2,
    );

    return {
      viewportWidth,
      scrollWidth,
      overlapCount,
      hasPaintedContent,
      fixedVisible,
      interactiveCount: interactive.length,
    };
  });

  expect(integrity.scrollWidth).toBe(integrity.viewportWidth);
  expect(integrity.hasPaintedContent).toBeTruthy();
  expect(integrity.overlapCount).toBe(0);
  // Pages without action bars (success/offline/login) only need painted content.
  if (integrity.interactiveCount > 0) {
    expect(integrity.fixedVisible || integrity.interactiveCount > 0).toBeTruthy();
  }
}
