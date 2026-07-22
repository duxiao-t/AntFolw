import { defineConfig } from '@playwright/test';

// Prefer 5174: host 5173 is often claimed by Docker published services on this machine.
const e2ePort = Number(process.env.E2E_PORT || 5174);
const origin = `http://127.0.0.1:${e2ePort}`;

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: {
    timeout: 10_000,
    toHaveScreenshot: {
      maxDiffPixelRatio: 0.02,
      animations: 'disabled',
    },
  },
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 2 : undefined,
  snapshotPathTemplate: '{testDir}/{testFilePath}-snapshots/{arg}-{projectName}{ext}',
  webServer: {
    command: `npx vite --host 127.0.0.1 --port ${e2ePort} --strictPort`,
    url: `${origin}/mobile/login`,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
  use: {
    baseURL: `${origin}/mobile`,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },
  projects: [
    { name: 'android-360', use: { browserName: 'chromium', viewport: { width: 360, height: 800 }, isMobile: true, hasTouch: true } },
    { name: 'iphone-375', use: { browserName: 'chromium', viewport: { width: 375, height: 812 }, isMobile: true, hasTouch: true } },
    { name: 'iphone-390', use: { browserName: 'chromium', viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true } },
    { name: 'iphone-430', use: { browserName: 'chromium', viewport: { width: 430, height: 932 }, isMobile: true, hasTouch: true } },
  ],
});
