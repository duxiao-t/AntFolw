import { defineConfig } from '@playwright/test';

const frontendPort = Number(process.env.PLAYWRIGHT_FRONTEND_PORT ?? 8010);
const backendPort = Number(process.env.PLAYWRIGHT_BACKEND_PORT ?? 18081);

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: `http://localhost:${frontendPort}`,
    headless: true,
    viewport: { width: 1280, height: 720 },
    trace: 'on-first-retry',
  },
  webServer: [
    {
      command: `mvn.cmd -f ../backend/pom.xml -B spring-boot:run -Dspring-boot.run.arguments=--server.port=${backendPort} -Dspring-boot.run.jvmArguments=-Xmx512m`,
      port: backendPort,
      reuseExistingServer: false,
      timeout: 180_000,
      stdout: 'ignore',
      stderr: 'pipe',
    },
    {
      command: `npx cross-env PORT=${frontendPort} ANTFLOW_API_TARGET=http://localhost:${backendPort} npm run dev`,
      port: frontendPort,
      reuseExistingServer: false,
      timeout: 180_000,
      stdout: 'ignore',
      stderr: 'pipe',
    },
  ],
});
