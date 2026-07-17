import { expect, request, test, type Page } from '@playwright/test';

async function loginAsAdmin(page: Page) {
  await page.goto('/user/login');
  await page
    .locator('input[placeholder*="账" i], input[id*="userName" i], input[id*="username" i]')
    .first()
    .fill('admin');
  await page.locator('input[type="password"]').first().fill('ant.design');
  await Promise.all([
    page.waitForURL((url) => !url.pathname.includes('/user/login')),
    page.getByRole('button', { name: /登录|Login/ }).click(),
  ]);
  const token = await page.evaluate(() => localStorage.getItem('antflow-token'));
  expect(token).toBeTruthy();
  return token as string;
}

function parseJsonValue<T>(value: T | string | undefined, fallback: T): T {
  if (typeof value !== 'string') return value ?? fallback;
  try {
    return JSON.parse(value);
  } catch {
    return fallback;
  }
}

test('form management wizard creates a form, saves designer and process, then publishes', async ({ page, baseURL }) => {
  test.setTimeout(120_000);
  const token = await loginAsAdmin(page);
  const api = await request.newContext({
    baseURL: baseURL!,
    extraHTTPHeaders: { Authorization: `Bearer ${token}` },
  });
  const stamp = Date.now();
  const formName = `E2E分步表单${stamp}`;
  const formCode = `e2e_step_form_${stamp}`;

  await page.goto('/approval/forms/new');
  await page.getByLabel('表单名称').fill(formName);
  await page.getByLabel('表单编码').fill(formCode);
  await page.getByRole('button', { name: /保存并进入表单制作/ }).click();
  await page.waitForURL(/\/approval\/forms\/\d+\/wizard\?step=designer/);
  const formId = Number(page.url().match(/\/approval\/forms\/(\d+)\/wizard/)?.[1]);
  expect(Number.isFinite(formId)).toBeTruthy();

  await page.getByRole('button', { name: '单行文本' }).dblclick();
  await expect(page.locator('[data-field-id]').first()).toBeVisible();
  await Promise.all([
    page.waitForResponse((response) =>
      response.url().includes('/api/forms/definitions')
      && response.request().method() === 'POST'
      && response.ok(),
    ),
    page.getByRole('button', { name: /^保存草稿$/ }).click(),
  ]);
  await expect.poll(async () => {
    const formDefinition = await (await api.get(`/api/forms/definitions/${formId}`)).json();
    return parseJsonValue<any[]>(formDefinition.schema, []).length;
  }).toBeGreaterThan(0);

  await page.goto(`/approval/forms/${formId}/wizard?step=process`);
  await Promise.all([
    page.waitForResponse((response) =>
      response.url().includes('/api/processes/definitions')
      && response.request().method() === 'POST'
      && response.ok(),
    ),
    page.getByRole('button', { name: /^保存草稿$/ }).click(),
  ]);
  await expect.poll(async () => {
    const processDefinition = await (await api.get(`/api/processes/definitions/draft/by-form/${formId}`)).json();
    return processDefinition?.id ?? null;
  }).not.toBeNull();

  await page.goto(`/approval/forms/${formId}/wizard?step=publish`);
  await expect(page.getByText(formName)).toBeVisible();
  await page.getByRole('button', { name: /^发\s*布$/ }).click();
  await expect(page.getByText('表单和流程已发布')).toBeVisible();

  const formDefinition = await (await api.get(`/api/forms/definitions/${formId}`)).json();
  expect(formDefinition.status).toBe('PUBLISHED');
  const processDefinition = await (await api.get(`/api/processes/definitions/draft/by-form/${formId}`)).json();
  expect(processDefinition.status).toBe('PUBLISHED');

  await api.dispose();
});
