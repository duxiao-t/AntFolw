import { expect, test } from '@playwright/test';
import {
  expectNoHorizontalOverflow,
  installDeterministicRuntime,
  signIn,
  signOutViaSecurity,
} from './helpers/auth';
import { createMockWorld, installApiMocks, USERS } from './helpers/fixtures';

test.describe('full approval flow', () => {
  test('bob submits and admin approves to APPROVED timeline', async ({ page }) => {
    const world = createMockWorld();
    await installDeterministicRuntime(page);
    await installApiMocks(page, world);

    page.on('dialog', (dialog) => {
      void dialog.accept();
    });

    await signIn(page, USERS.bob.username, 'ant.design', `/forms/${world.formCode}`);
    await expect(page.getByRole('heading')).toBeVisible();
    await page.getByLabel('请假事由').fill('E2E回家探亲');
    await page.getByRole('button', { name: '下一步' }).click();

    await expect(page.getByRole('heading', { name: '选择审批人' })).toBeVisible();
    await page.getByText(USERS.admin.displayName, { exact: true }).click();
    await page.getByRole('button', { name: '确认选择' }).click();

    await expect(page.getByRole('heading', { name: '提交确认' })).toBeVisible();
    await expect(page.getByText('E2E回家探亲')).toBeVisible();
    await expect(page.getByText(USERS.admin.displayName)).toBeVisible();
    await page.getByRole('button', { name: '提交' }).click();

    await expect(page.getByRole('heading', { name: '提交成功' })).toBeVisible();
    await expect(page.getByRole('link', { name: '查看详情' })).toBeVisible();
    expect(world.startPostCount).toBe(1);
    expect(world.instances.size).toBe(1);
    expect(world.tasks.size).toBe(1);

    const instanceId = [...world.instances.keys()][0]!;
    const taskId = [...world.tasks.keys()][0]!;

    await signOutViaSecurity(page);
    await signIn(page, USERS.admin.username, 'ant.design', '/tasks?view=pending');
    await expect(page.getByRole('heading', { name: '待办' })).toBeVisible();
    await expect(page.getByText('请假申请')).toBeVisible();
    await page.getByRole('link', { name: /请假申请/ }).first().click();

    await expect(page.getByText('E2E回家探亲')).toBeVisible();
    await page.getByRole('button', { name: '同意' }).click();
    await expect(page.getByLabel('同意审批')).toBeVisible();
    await page.getByRole('button', { name: '确认同意' }).click();

    await expect(page.getByRole('heading', { name: '待办' })).toBeVisible({ timeout: 15_000 });
    expect(world.tasks.get(taskId)?.taskStatus).toBe('APPROVED');
    expect(world.instances.get(instanceId)?.status).toBe('APPROVED');

    await signOutViaSecurity(page);
    await signIn(page, USERS.bob.username, 'ant.design', `/processes/${instanceId}`);
    await expect(page.getByRole('heading')).toBeVisible();
    await expect(page.getByText('状态：已通过').or(page.getByText('状态：APPROVED'))).toBeVisible();
    await expect(page.getByRole('list', { name: '流程快照进度' })).toBeVisible();
    await expect(page.getByRole('listitem').filter({ hasText: '到达' })).toBeVisible();
    await expect(page.getByRole('listitem').filter({ hasText: '同意' })).toBeVisible();
    await expect(page.getByText('root → 直属主管')).toBeVisible();
    await expectNoHorizontalOverflow(page);
  });
});
