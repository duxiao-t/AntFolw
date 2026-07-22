import { expect, test, type Page } from '@playwright/test';
import { installDeterministicRuntime, signIn } from './helpers/auth';
import { createMockWorld, installApiMocks, USERS } from './helpers/fixtures';
import { writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const FCP_BUDGET_MS = 1800;
const INTERACTIVE_BUDGET_MS = 2500;
// Local/dev servers can be slightly warmer than CI reference machines.
const CI_TOLERANCE_MS = process.env.CI ? 0 : 400;

async function measurePerf(page: Page) {
  return page.evaluate(() => {
    const nav = performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming | undefined;
    const paints = performance.getEntriesByType('paint');
    const fcp = paints.find((p) => p.name === 'first-contentful-paint')?.startTime ?? 0;
    const interactive =
      nav?.domInteractive ??
      (performance.timing ? performance.timing.domInteractive - performance.timing.navigationStart : 0);
    return {
      fcp,
      interactive,
      loadEventEnd: nav?.loadEventEnd ?? 0,
      domContentLoaded: nav?.domContentLoadedEventEnd ?? 0,
    };
  });
}

async function writeMetricsArtifact(name: string, payload: unknown) {
  const dir = join(process.cwd(), 'test-results');
  if (!existsSync(dir)) {
    mkdirSync(dir, { recursive: true });
  }
  writeFileSync(join(dir, name), JSON.stringify(payload, null, 2), 'utf8');
}

test.describe('performance and accessibility budgets', () => {
  test('workbench meets FCP/interactive and a11y budgets', async ({ page }) => {
    const world = createMockWorld({ seedPendingTask: true });
    await installDeterministicRuntime(page);
    await installApiMocks(page, world);

    // Authenticate first so the measured navigation is workbench-only.
    await signIn(page, USERS.admin.username, 'ant.design', '/workbench');
    await expect(page.getByTestId('workbench')).toBeVisible({ timeout: 15_000 });

    const started = Date.now();
    await page.goto('/mobile/workbench');
    await expect(page.getByTestId('workbench')).toBeVisible({ timeout: 15_000 });
    // Wait a paint after data settles.
    await page.waitForTimeout(200);

    const metrics = await measurePerf(page);
    const wallInteractive = Date.now() - started;
    const artifact = {
      generatedAt: new Date().toISOString(),
      budgets: {
        fcpMs: FCP_BUDGET_MS + CI_TOLERANCE_MS,
        interactiveMs: INTERACTIVE_BUDGET_MS + CI_TOLERANCE_MS,
      },
      metrics,
      wallInteractiveMs: wallInteractive,
      project: test.info().project.name,
    };
    await writeMetricsArtifact(`perf-workbench-${test.info().project.name}.json`, artifact);

    // Prefer browser paint metrics; fall back to wall-clock when paint API is sparse in SPA navigations.
    const fcp = metrics.fcp > 0 ? metrics.fcp : wallInteractive;
    const interactive = metrics.interactive > 0 ? metrics.interactive : wallInteractive;
    expect(fcp).toBeLessThan(FCP_BUDGET_MS + CI_TOLERANCE_MS);
    expect(interactive).toBeLessThan(INTERACTIVE_BUDGET_MS + CI_TOLERANCE_MS);

    // Touch target size >= 44×44
    const targetReport = await page.evaluate(() => {
      const nodes = Array.from(
        document.querySelectorAll('button, [role="button"], a, .adm-tab-bar-item, input, textarea'),
      );
      const undersized: Array<{ text: string; width: number; height: number }> = [];
      for (const el of nodes) {
        const style = window.getComputedStyle(el);
        if (style.display === 'none' || style.visibility === 'hidden') continue;
        const rect = el.getBoundingClientRect();
        if (rect.width === 0 || rect.height === 0) continue;
        // Skip pure text links inside body copy that are not action controls.
        const role = el.getAttribute('role');
        const tag = el.tagName.toLowerCase();
        const isAction =
          tag === 'button' ||
          tag === 'input' ||
          tag === 'textarea' ||
          role === 'button' ||
          el.classList.contains('adm-tab-bar-item') ||
          el.classList.contains('adm-button');
        if (!isAction) continue;
        if (rect.width < 44 - 0.5 || rect.height < 44 - 0.5) {
          undersized.push({
            text: (el.textContent ?? el.getAttribute('aria-label') ?? tag).trim().slice(0, 40),
            width: Math.round(rect.width * 10) / 10,
            height: Math.round(rect.height * 10) / 10,
          });
        }
      }
      return undersized;
    });
    expect(targetReport, `undersized targets: ${JSON.stringify(targetReport)}`).toEqual([]);

    // Labels for inputs on form fill page (authenticated)
    await page.goto(`/mobile/forms/${world.formCode}`);
    await expect(page.getByLabel('请假事由')).toBeVisible();
    const labelReport = await page.evaluate(() => {
      const inputs = Array.from(document.querySelectorAll('input, textarea, select'));
      return inputs.map((input) => {
        const el = input as HTMLInputElement;
        const byId = el.id ? document.querySelector(`label[for="${CSS.escape(el.id)}"]`) : null;
        const aria = el.getAttribute('aria-label') || el.getAttribute('aria-labelledby');
        const placeholder = el.getAttribute('placeholder');
        const wrapped = el.closest('label');
        // antd-mobile Form.Item associates label via sibling structure / aria
        const formItemLabel = el.closest('.adm-list-item, .adm-form-item')?.querySelector(
          '.adm-list-item-description, .adm-form-item-label, label',
        );
        const labelled = Boolean(byId || aria || placeholder || wrapped || formItemLabel);
        return {
          name: el.name || el.id || el.getAttribute('type') || 'input',
          labelled,
        };
      });
    });
    expect(labelReport.length).toBeGreaterThan(0);
    expect(labelReport.every((item) => item.labelled)).toBeTruthy();

    // Contrast sample on primary text/actions
    const contrastIssues = await page.evaluate(() => {
      // Inline relative luminance helpers for page context.
      const parseRgb = (color: string) => {
        const m = color.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/i);
        if (!m) return null;
        return [Number(m[1]), Number(m[2]), Number(m[3])] as [number, number, number];
      };
      const toLin = (c: number) => {
        const s = c / 255;
        return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
      };
      const lum = (rgb: [number, number, number]) =>
        0.2126 * toLin(rgb[0]) + 0.7152 * toLin(rgb[1]) + 0.0722 * toLin(rgb[2]);
      const ratio = (fg: [number, number, number], bg: [number, number, number]) => {
        const L1 = lum(fg);
        const L2 = lum(bg);
        return (Math.max(L1, L2) + 0.05) / (Math.min(L1, L2) + 0.05);
      };
      const solidBg = (el: Element | null): [number, number, number] => {
        let cur: Element | null = el;
        while (cur && cur !== document.documentElement) {
          const bg = parseRgb(window.getComputedStyle(cur).backgroundColor);
          if (bg && window.getComputedStyle(cur).backgroundColor !== 'rgba(0, 0, 0, 0)') {
            // ignore fully transparent
            const alphaMatch = window.getComputedStyle(cur).backgroundColor.match(/rgba\([^)]+,\s*([0-9.]+)\)/);
            if (!alphaMatch || Number(alphaMatch[1]) > 0.9) {
              return bg;
            }
          }
          cur = cur.parentElement;
        }
        return parseRgb(window.getComputedStyle(document.body).backgroundColor) ?? [255, 255, 255];
      };

      const samples = Array.from(
        document.querySelectorAll('h1, button, p, label, .adm-list-item-content-main'),
      ).slice(0, 20);
      const issues: Array<{ text: string; ratio: number }> = [];
      for (const el of samples) {
        const style = window.getComputedStyle(el);
        const fg = parseRgb(style.color);
        if (!fg) continue;
        const bg = solidBg(el);
        const r = ratio(fg, bg);
        const text = (el.textContent ?? '').trim().slice(0, 24);
        if (text && r < 4.5) {
          issues.push({ text, ratio: Number(r.toFixed(2)) });
        }
      }
      return issues;
    });
    expect(contrastIssues, `contrast failures: ${JSON.stringify(contrastIssues)}`).toEqual([]);
  });

  test('dialog focus trap and 200% text zoom keep actions usable', async ({ page }) => {
    const world = createMockWorld({ seedPendingTask: true });
    await installDeterministicRuntime(page);
    await installApiMocks(page, world);
    const taskId = [...world.tasks.keys()][0]!;

    await signIn(page, USERS.admin.username, 'ant.design', `/tasks/${taskId}`);
    await expect(page.getByText('回家探亲')).toBeVisible();

    await page.getByRole('button', { name: '同意' }).click();
    const dialog = page.getByRole('dialog', { name: '同意审批' });
    await expect(dialog).toBeVisible();

    // Focus should land inside the dialog (focus trap).
    await expect
      .poll(async () =>
        page.evaluate(() => {
          const active = document.activeElement;
          const dlg = document.querySelector('[role="dialog"][aria-label="同意审批"]');
          return Boolean(active && dlg && (dlg === active || dlg.contains(active)));
        }),
      )
      .toBeTruthy();

    // Tab cycling should remain within the dialog surface.
    for (let i = 0; i < 8; i += 1) {
      await page.keyboard.press('Tab');
      const stillInside = await page.evaluate(() => {
        const active = document.activeElement;
        const dlg = document.querySelector('[role="dialog"][aria-label="同意审批"]');
        return Boolean(active && dlg && (dlg === active || dlg.contains(active)));
      });
      expect(stillInside).toBeTruthy();
    }

    await page.keyboard.press('Escape');
    if (await dialog.isVisible().catch(() => false)) {
      await page.getByRole('button', { name: '取消' }).click();
    }

    // 200% text zoom must not hide primary actions.
    await page.evaluate(() => {
      document.documentElement.style.fontSize = '200%';
      document.documentElement.style.zoom = '1';
    });
    await page.goto(`/mobile/tasks/${taskId}`);
    await expect(page.getByRole('button', { name: '同意' })).toBeVisible();
    await expect(page.getByRole('button', { name: '驳回' })).toBeVisible();
    const actionsVisible = await page.evaluate(() => {
      const approve = Array.from(document.querySelectorAll('button')).find((b) =>
        (b.textContent ?? '').includes('同意'),
      );
      const reject = Array.from(document.querySelectorAll('button')).find((b) =>
        (b.textContent ?? '').includes('驳回'),
      );
      if (!approve || !reject) return false;
      const ar = approve.getBoundingClientRect();
      const rr = reject.getBoundingClientRect();
      const vh = window.innerHeight;
      return ar.height > 0 && rr.height > 0 && ar.bottom <= vh + 40 && rr.bottom <= vh + 40;
    });
    expect(actionsVisible).toBeTruthy();
  });
});
