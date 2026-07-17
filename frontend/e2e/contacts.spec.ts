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

test('contacts supports selecting and batch deleting temporary members', async ({ page, baseURL }) => {
  const token = await loginAsAdmin(page);
  const api = await request.newContext({
    baseURL: baseURL!,
    extraHTTPHeaders: { Authorization: `Bearer ${token}` },
  });
  const createdUserIds: number[] = [];
  let departmentId: number | null = null;
  const stamp = Date.now();
  const departmentName = `E2E批量删除${stamp}`;
  const members = [
    { username: `e2e_bulk_${stamp}_1`, displayName: `批量删除甲${stamp}`, gender: 'M' },
    { username: `e2e_bulk_${stamp}_2`, displayName: `批量删除乙${stamp}`, gender: 'F' },
  ];

  try {
    const companies = await (await api.get('/api/companies')).json();
    const departmentResp = await api.post('/api/departments', {
      data: { companyId: companies[0].id, parentId: null, name: departmentName },
    });
    expect(departmentResp.ok()).toBeTruthy();
    departmentId = (await departmentResp.json()).id;

    for (const member of members) {
      const createResp = await api.post('/api/users', {
        data: { ...member, deptId: departmentId },
      });
      expect(createResp.ok()).toBeTruthy();
      createdUserIds.push((await createResp.json()).id);
    }

    await page.goto('/org/contacts');
    await page.getByRole('treeitem', { name: new RegExp(departmentName) }).click();
    await expect(page.getByRole('heading', { name: new RegExp(`${departmentName} · 2人`) })).toBeVisible();

    const maleRow = page.locator('tr').filter({ hasText: members[0].displayName });
    const femaleRow = page.locator('tr').filter({ hasText: members[1].displayName });
    await expect(maleRow.getByText('男', { exact: true })).toBeVisible();
    await expect(femaleRow.getByText('女', { exact: true })).toBeVisible();
    await maleRow.getByRole('checkbox').check();
    await femaleRow.getByRole('checkbox').check();

    const bulkDelete = page.getByRole('button', { name: /批量删除/ });
    await expect(bulkDelete).toBeEnabled();
    await bulkDelete.click();
    await page.getByRole('button', { name: /确定|OK/ }).click();
    await expect(page.getByText(members[0].displayName)).toBeHidden();
    await expect(page.getByText(members[1].displayName)).toBeHidden();

    const remaining = await (await api.get(`/api/users?deptId=${departmentId}`)).json();
    expect(remaining.filter((u: any) => u.username.startsWith(`e2e_bulk_${stamp}`))).toEqual([]);
    createdUserIds.length = 0;
  } finally {
    for (const userId of createdUserIds) {
      await api.delete(`/api/users/${userId}`).catch(() => undefined);
    }
    if (departmentId !== null) {
      await api.delete(`/api/departments/${departmentId}`).catch(() => undefined);
    }
    await api.dispose();
  }
});
