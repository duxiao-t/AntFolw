import { expect, request, test } from '@playwright/test';

/**
 * End-to-end smoke test for the AntFlow happy path:
 *   1. admin logs in
 *   2. admin designs a form (drag text field, save, publish)
 *   3. admin wires a 1-level approval onto the form (via direct API call to bypass
 *      the React Flow drag-and-drop which is hard to drive from a test browser)
 *   4. bob logs in, fills the form, submits
 *   5. admin sees the task in inbox, approves it
 *   6. instance detail shows APPROVED
 *
 * Requires the dev infra (Postgres) to be running. Run:
 *   cd infra && docker compose up -d
 * Then: cd frontend && npx playwright test
 */
test('design → publish → submit → approve end-to-end', async ({
  page,
  baseURL,
}) => {
  test.setTimeout(120_000);

  // -- 1. admin login
  await page.goto('/user/login');
  await page
    .locator(
      'input[placeholder*="账" i], input[id*="userName" i], input[id*="username" i]',
    )
    .first()
    .fill('admin');
  await page.locator('input[type="password"]').first().fill('ant.design');
  await page.getByRole('button', { name: /登录/ }).click();
  await page.waitForURL('**/');
  expect(page.url()).not.toContain('/user/login');

  // -- 2. design a form
  await page.goto('/designer/form/new');
  // Drag a text field from the palette onto the canvas
  const paletteCard = page.locator('text=单行文本').first();
  const canvas = page.locator('[data-canvas]').first();
  await paletteCard.dragTo(canvas);
  await page.getByRole('button', { name: /保存草稿/ }).click();
  await page.waitForURL(/\/designer\/form\/\d+/, { timeout: 15_000 });
  const formUrl = page.url();
  const formIdMatch = formUrl.match(/\/designer\/form\/(\d+)/);
  expect(formIdMatch).not.toBeNull();
  const formId = formIdMatch![1];
  const formCode = `form_${formId}`;

  // -- 3. publish the form
  await page.getByRole('button', { name: /发布/ }).click();

  // -- 4. set up a 1-level approval (process_def) by direct API call.
  //       (Driving React Flow drag-and-drop in a headless browser is brittle;
  //        the API path is identical and is what production callers would use.)
  const token = await page.evaluate(() =>
    localStorage.getItem('antflow-token'),
  );
  expect(token).toBeTruthy();

  // Look up the admin user id (we need a real id for the assignee)
  const apiContext = await request.newContext({
    baseURL: baseURL!,
    extraHTTPHeaders: { Authorization: `Bearer ${token}` },
  });
  const meResp = await apiContext.get('/api/auth/me');
  expect(meResp.ok()).toBeTruthy();
  const me = await meResp.json();
  const adminId = me.id;

  // Save the process definition
  const procBody = {
    formDefId: Number(formId),
    nodes: [
      {
        id: 'start',
        type: 'start',
        x: 80,
        y: 80,
        props: {},
        assignee: { type: 'user', ids: [] },
      },
      {
        id: 'a1',
        type: 'approval',
        x: 240,
        y: 80,
        assignee: { type: 'user', ids: [adminId] },
        props: {},
      },
      {
        id: 'end',
        type: 'end',
        x: 400,
        y: 80,
        props: {},
        assignee: { type: 'user', ids: [] },
      },
    ],
    edges: [
      { from: 'start', to: 'a1' },
      { from: 'a1', to: 'end' },
    ],
  };
  const saveProc = await apiContext.post('/api/processes/definitions', {
    data: procBody,
  });
  expect(saveProc.ok()).toBeTruthy();
  const procResp = await saveProc.json();
  const procId = procResp.id;

  // Publish — server enforces linear-flow + form-published
  const pubProc = await apiContext.post(
    `/api/processes/definitions/${procId}/publish`,
  );
  expect(pubProc.ok()).toBeTruthy();

  // -- 5. log out, log in as bob
  await page.evaluate(() => localStorage.removeItem('antflow-token'));
  await page.goto('/user/login');
  await page
    .locator(
      'input[placeholder*="账" i], input[id*="userName" i], input[id*="username" i]',
    )
    .first()
    .fill('bob');
  await page.locator('input[type="password"]').first().fill('ant.design');
  await page.getByRole('button', { name: /登录/ }).click();
  await page.waitForURL('**/');

  // -- 6. submit the form
  await page.goto(`/runtime/form/${formCode}`);
  // The default text field has label "单行文本" (the field's defaultProps had no label set,
  // so it's "" or "单行文本"). Fill anything to satisfy required=true isn't set by default
  // for text in our MVP, so we can leave the value blank if not required.
  // The form is publishable either way.
  await page.getByRole('button', { name: /提交/ }).click();
  await page.waitForURL('**/proc', { timeout: 15_000 });

  // -- 7. log back in as admin to approve
  await page.evaluate(() => localStorage.removeItem('antflow-token'));
  await page.goto('/user/login');
  await page
    .locator(
      'input[placeholder*="账" i], input[id*="userName" i], input[id*="username" i]',
    )
    .first()
    .fill('admin');
  await page.locator('input[type="password"]').first().fill('ant.design');
  await page.getByRole('button', { name: /登录/ }).click();
  await page.waitForURL('**/');

  // -- 8. check inbox, approve
  await page.goto('/tasks/inbox');
  await expect(page.locator('text=审批').first()).toBeVisible({
    timeout: 10_000,
  });
  await page
    .getByRole('button', { name: /^同意$/ })
    .first()
    .click();
  await page.getByRole('button', { name: /确定/ }).click();

  // -- 9. instance detail shows APPROVED
  await page.goto('/proc');
  await expect(page.locator('text=APPROVED').first()).toBeVisible({
    timeout: 10_000,
  });
});
