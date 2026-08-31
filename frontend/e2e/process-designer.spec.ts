import { expect, type Page, request, test } from '@playwright/test';

async function loginAsAdmin(page: Page) {
  await page.goto('/user/login');
  await page
    .locator(
      'input[placeholder*="账" i], input[id*="userName" i], input[id*="username" i]',
    )
    .first()
    .fill('admin');
  await page.locator('input[type="password"]').first().fill('ant.design');
  await Promise.all([
    page.waitForURL((url) => !url.pathname.includes('/user/login')),
    page.getByRole('button', { name: /登录|Login/ }).click(),
  ]);
  const token = await page.evaluate(() =>
    localStorage.getItem('antflow-token'),
  );
  expect(token).toBeTruthy();
  return token as string;
}

test('process designer renders nested lanes, zoom and trigger drawer without overlap', async ({
  page,
  baseURL,
}) => {
  test.setTimeout(120_000);
  const runtimeErrors: string[] = [];
  const failedResponses: string[] = [];
  page.on('pageerror', (error) => runtimeErrors.push(error.message));
  page.on('response', (response) => {
    if (response.status() >= 400) {
      failedResponses.push(
        `${response.status()} ${response.request().resourceType()} ${response.url()}`,
      );
    }
  });
  page.on('console', (message) => {
    if (
      message.type() === 'error' &&
      !message.text().startsWith('[React Intl] Missing message:') &&
      !message.text().startsWith('Failed to load resource:')
    ) {
      runtimeErrors.push(message.text());
    }
  });
  const token = await loginAsAdmin(page);
  const api = await request.newContext({
    baseURL: baseURL ?? 'http://localhost:8000',
    extraHTTPHeaders: { Authorization: `Bearer ${token}` },
  });
  const stamp = Date.now();
  const formResponse = await api.post('/api/forms/definitions', {
    data: {
      id: null,
      code: `e2e_process_canvas_${stamp}`,
      name: `流程画布校验 ${stamp}`,
      schema: [],
      settings: { workflowEnabled: true },
    },
  });
  expect(formResponse.ok()).toBeTruthy();
  const form = await formResponse.json();

  const process = {
    id: 'root',
    type: 'ROOT',
    name: '发起人',
    props: { assignedUser: [] },
    children: {
      id: 'approval-submit',
      type: 'APPROVAL',
      name: '部门主管审批',
      props: { assignedType: 'SELF', mode: 'OR' },
      children: {
        id: 'conditions-amount',
        type: 'CONDITIONS',
        name: '金额条件',
        branchs: [
          {
            id: 'condition-high',
            type: 'CONDITION',
            name: '金额大于 5000',
            props: {
              isDefault: false,
              groupsType: 'OR',
              groups: [
                {
                  groupType: 'AND',
                  conditions: [
                    {
                      id: 'rule-amount',
                      field: 'amount',
                      operator: '>',
                      value: '5000',
                    },
                  ],
                },
              ],
            },
            children: {
              id: 'parallel-review',
              type: 'PARALLEL',
              name: '并行会审',
              branchs: [
                {
                  id: 'branch-finance',
                  type: 'BRANCH',
                  name: '财务复核',
                  props: {
                    conditionMode: 'ALWAYS',
                  },
                  children: {
                    id: 'approval-finance',
                    type: 'APPROVAL',
                    name: '财务负责人',
                    props: { assignedType: 'SELF', mode: 'OR' },
                    children: {
                      id: 'nested-conditions',
                      type: 'CONDITIONS',
                      name: '二级条件',
                      branchs: [
                        {
                          id: 'nested-condition-match',
                          type: 'CONDITION',
                          name: '需要复核',
                          props: { groups: [], groupsType: 'OR' },
                          children: null,
                        },
                        {
                          id: 'nested-condition-default',
                          type: 'CONDITION',
                          name: '其他情况',
                          props: { isDefault: true },
                          children: null,
                        },
                      ],
                      children: { id: 'nested-condition-join', type: 'EMPTY', children: null },
                    },
                  },
                },
                {
                  id: 'branch-record',
                  type: 'BRANCH',
                  name: '行政备案',
                  props: { conditionMode: 'ALWAYS' },
                  children: {
                    id: 'cc-record',
                    type: 'CC',
                    name: '抄送行政',
                    props: { assignedUser: [1] },
                    children: null,
                  },
                },
              ],
              children: {
                id: 'parallel-join',
                type: 'EMPTY',
                children: {
                  id: 'delay-notice',
                  type: 'DELAY',
                  name: '等待资料同步',
                  props: { mode: 'DURATION', amount: 2, unit: 'HOURS' },
                  children: {
                    id: 'trigger-archive',
                    type: 'TRIGGER',
                    name: '推送归档系统',
                    props: {
                      method: 'POST',
                      url: 'https://hooks.example.com/archive',
                      contentType: 'application/json',
                      continueMode: 'ON_SUCCESS',
                      secret: 'e2e-signing-secret',
                      headers: [],
                      parameters: [],
                    },
                    children: null,
                  },
                },
              },
            },
          },
          {
            id: 'condition-default',
            type: 'CONDITION',
            name: '常规流程',
            props: { isDefault: true },
            children: {
              id: 'cc-owner',
              type: 'CC',
              name: '抄送申请人',
              props: { assignedUser: [1] },
              children: null,
            },
          },
        ],
        children: {
          id: 'condition-join',
          type: 'EMPTY',
          children: {
            id: 'approval-final',
            type: 'APPROVAL',
            name: '最终确认',
            props: { assignedType: 'SELF', mode: 'OR' },
            children: null,
          },
        },
      },
    },
  };
  const processResponse = await api.post('/api/processes/definitions', {
    data: { id: null, formDefId: form.id, process },
  });
  expect(processResponse.ok()).toBeTruthy();

  await page.goto(`/approval/forms/${form.id}/wizard?step=process`);
  await expect(
    page.locator('.pt-node--root'),
    `Designer did not render at ${page.url()}; browser errors: ${runtimeErrors.join(' | ') || 'none'}`,
  ).toBeVisible();
  expect(runtimeErrors).toEqual([]);
  expect(failedResponses).toEqual([]);
  await expect(page.locator('.pt-band')).toHaveCount(3);
  await expect(page.getByText('流程校验通过')).toHaveCount(0);

  const parallelBand = page.locator('.pt-band[data-node-id="parallel-review"]');
  await expect(parallelBand.locator(':scope > .pt-band__gateway')).toHaveCount(
    0,
  );
  await expect(parallelBand.locator(':scope > .pt-band__add')).toHaveText(
    '添加分支',
  );
  const addOffset = await parallelBand.evaluate((band) => {
    const add = band.querySelector<HTMLElement>(':scope > .pt-band__add');
    if (!add) return Number.POSITIVE_INFINITY;
    const bandRect = band.getBoundingClientRect();
    const addRect = add.getBoundingClientRect();
    return Math.abs(
      addRect.left + addRect.width / 2 - (bandRect.left + bandRect.width / 2),
    );
  });
  expect(addOffset).toBeLessThan(2);

  const conditionLane = page.locator(
    '.pt-band__branch[data-node-id="condition-high"]',
  );
  const [plusBox, addBranchBox] = await Promise.all([
    conditionLane.locator(':scope > .pt-add').boundingBox(),
    parallelBand.locator(':scope > .pt-band__add').boundingBox(),
  ]);
  expect(plusBox).not.toBeNull();
  expect(addBranchBox).not.toBeNull();
  expect(
    Math.abs(
      (plusBox?.x ?? 0) +
        (plusBox?.width ?? 0) / 2 -
        ((addBranchBox?.x ?? 0) + (addBranchBox?.width ?? 0) / 2),
    ),
  ).toBeLessThan(2);
  expect(addBranchBox?.y ?? 0).toBeGreaterThanOrEqual(
    (plusBox?.y ?? 0) + (plusBox?.height ?? 0),
  );
  const nestedLastConnectorLeft = await page
    .locator('[data-node-id="nested-condition-default"] > .pt-band__connector--top')
    .evaluate((connector) => getComputedStyle(connector, '::before').left);
  expect(Number.parseFloat(nestedLastConnectorLeft)).toBeLessThan(1);
  await expect(
    page
      .locator('[data-node-id="branch-finance"]')
      .locator(':scope > .pt-branch > .pt-branch__actions')
      .getByRole('button', { name: '删除分支' }),
  ).toBeEnabled();

  await page
    .locator('[data-node-id="branch-finance"] > .pt-branch > .pt-branch__main')
    .click();
  const branchDrawer = page.locator('.ant-drawer-content-wrapper');
  await expect(branchDrawer.getByText('执行方式')).toBeVisible();
  await expect(branchDrawer.locator('input[value="始终执行"]')).toBeVisible();
  await page.screenshot({
    path: '../_preview/process-designer-branch-drawer.png',
    fullPage: false,
    animations: 'disabled',
  });
  await page.locator('.ant-drawer-close').click();
  await expect(branchDrawer).toBeHidden();

  await page.locator('[data-node-id="cc-record"] .pt-node__del').click();
  await expect(page.locator('[data-node-id="cc-record"]')).toHaveCount(0);
  const emptyLaneGap = await page
    .locator('.pt-band__branch[data-node-id="branch-record"]')
    .evaluate((lane) => {
      const add = lane.querySelector<HTMLElement>(':scope > .pt-add');
      const connector = lane.querySelector<HTMLElement>(
        ':scope > .pt-band__connector--bottom',
      );
      if (!add || !connector) return Number.POSITIVE_INFINITY;
      const addRect = add.getBoundingClientRect();
      const connectorRect = connector.getBoundingClientRect();
      return connectorRect.top - addRect.bottom;
    });
  expect(emptyLaneGap).toBeLessThanOrEqual(1);

  const assertNoCardOverlap = async () => {
    const overlaps = await page
      .locator('.process-designer__viewport')
      .evaluate((viewport) => {
        const elements = [
          ...viewport.querySelectorAll<HTMLElement>('.pt-node, .pt-branch'),
        ];
        const collisions: string[] = [];
        for (let left = 0; left < elements.length; left += 1) {
          const a = elements[left].getBoundingClientRect();
          for (let right = left + 1; right < elements.length; right += 1) {
            const b = elements[right].getBoundingClientRect();
            const width = Math.min(a.right, b.right) - Math.max(a.left, b.left);
            const height =
              Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
            if (width > 1 && height > 1) {
              collisions.push(
                `${elements[left].className} <> ${elements[right].className}`,
              );
            }
          }
        }
        return collisions;
      });
    expect(overlaps).toEqual([]);
  };

  for (const viewport of [
    { width: 1920, height: 1080 },
    { width: 1440, height: 900 },
    { width: 1024, height: 768 },
  ]) {
    await page.setViewportSize(viewport);
    await assertNoCardOverlap();
    await page.screenshot({
      path: `../_preview/process-designer-${viewport.width}x${viewport.height}.png`,
      fullPage: false,
    });
  }

  await page.getByLabel('缩小画布').click();
  await expect(page.getByText('90%', { exact: true })).toBeVisible();
  const trigger = page.locator('.pt-node--trigger');
  await trigger.scrollIntoViewIfNeeded();
  await trigger.click();
  await expect(page.getByText('HMAC 签名密钥')).toBeVisible();
  await expect(page.locator('.ant-drawer-content-wrapper')).toBeVisible();
  await page.screenshot({
    path: '../_preview/process-designer-trigger-drawer.png',
    fullPage: false,
    animations: 'disabled',
  });

  expect(
    (await api.delete(`/api/processes/definitions/by-form/${form.id}`)).ok(),
  ).toBeTruthy();
  expect(
    (await api.delete(`/api/forms/definitions/${form.id}`)).ok(),
  ).toBeTruthy();
  await api.dispose();
});
