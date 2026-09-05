import { expect, test } from '@playwright/test';
import {
  expectNoHorizontalOverflow,
  installDeterministicRuntime,
  signIn,
  signOutViaSecurity,
} from './helpers/auth';
import { createMockWorld, installApiMocks, USERS } from './helpers/fixtures';

test.describe('full approval flow', () => {
  test('bob submits and admin approves to completed approval records', async ({ page }) => {
    const world = createMockWorld();
    await installDeterministicRuntime(page);
    await installApiMocks(page, world);

    page.on('dialog', (dialog) => {
      void dialog.accept();
    });

    await signIn(page, USERS.bob.username, 'ant.design', `/forms/${world.formCode}`);
    await expect(page.getByRole('heading', { name: '请假申请' })).toBeVisible();
    await page.getByLabel('请假事由').fill('E2E回家探亲');
    await page.getByRole('button', { name: '提交' }).click();

    await expect(page.locator('.app-bar__title')).toHaveText('选择审批人');
    await page.getByText(USERS.admin.displayName, { exact: true }).click();
    await page.getByRole('button', { name: '完成' }).click();

    await expect(page.getByRole('heading', { name: '请确认本次提交内容' })).toBeVisible();
    await expect(page.getByText('E2E回家探亲')).toBeVisible();
    await expect(page.getByText(USERS.admin.displayName)).toBeVisible();
    await expect(page.getByText(`${USERS.admin.displayName} · 直属主管`)).toBeVisible();
    await expect(page.getByText('下一审批节点')).toBeVisible();
    await page.getByRole('button', { name: '确认提交' }).click();

    await expect(page.getByRole('heading', { name: '提交成功' })).toBeVisible();
    await expect(page.getByRole('button', { name: '查看流程' })).toBeVisible();
    expect(world.startPostCount).toBe(1);
    expect(world.instances.size).toBe(1);
    expect(world.tasks.size).toBe(1);

    const instanceId = [...world.instances.keys()][0]!;
    const taskId = [...world.tasks.keys()][0]!;

    await signOutViaSecurity(page);
    await signIn(page, USERS.admin.username, 'ant.design', '/tasks?view=pending');
    await expect(page.getByRole('heading', { name: '需要你处理的审批' })).toBeVisible();
    await expect(page.getByText('请假申请')).toBeVisible();
    await page.getByRole('link', { name: /请假申请/ }).first().click();

    await expect(page.getByText('E2E回家探亲')).toBeVisible();
    await page.getByRole('button', { name: '同意' }).click();
    await expect(page.getByLabel('同意审批')).toBeVisible();
    await page.getByRole('button', { name: '确认同意' }).click();

    await expect(page.getByRole('heading', { name: '需要你处理的审批' })).toBeVisible({ timeout: 15_000 });
    expect(world.tasks.get(taskId)?.taskStatus).toBe('APPROVED');
    expect(world.instances.get(instanceId)?.status).toBe('APPROVED');

    await signOutViaSecurity(page);
    await signIn(page, USERS.bob.username, 'ant.design', `/processes/${instanceId}`);
    await expect(page.locator('.app-bar__title')).toHaveText('审批详情');
    await expect(page.getByText('已通过').first()).toBeVisible();
    await expect(page.locator('.approval-records__list')).toBeVisible();
    await expect(page.locator('.approval-records .approval-panel__summary')).toHaveText('已完成');
    await expect(page.getByRole('listitem').filter({ hasText: '审批中' })).toHaveCount(0);
    await expect(page.getByRole('listitem').filter({ hasText: '已通过' })).toBeVisible();
    await expect(page.getByText(world.instances.get(instanceId)?.businessNo ?? '', { exact: true })).toBeVisible();
    await expectNoHorizontalOverflow(page);
  });
});
