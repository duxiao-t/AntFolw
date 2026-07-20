import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  expect: {
    timeout: 5_000,
  },
  snapshotPathTemplate: '{testDir}/{testFilePath}-snapshots/{arg}-{projectName}{ext}',
  webServer: {
    command: 'npm run dev',
    url: 'http://127.0.0.1:5173/mobile/login',
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
  use: {
    baseURL: 'http://127.0.0.1:5173/mobile',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'android-360', use: { browserName: 'chromium', viewport: { width: 360, height: 800 }, isMobile: true, hasTouch: true } },
    { name: 'iphone-375', use: { browserName: 'chromium', viewport: { width: 375, height: 812 }, isMobile: true, hasTouch: true } },
    { name: 'iphone-390', use: { browserName: 'chromium', viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true } },
    { name: 'iphone-430', use: { browserName: 'chromium', viewport: { width: 430, height: 932 }, isMobile: true, hasTouch: true } },
  ],
});
