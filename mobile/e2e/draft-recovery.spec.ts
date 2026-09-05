import { expect, test } from '@playwright/test';
import { installDeterministicRuntime, signIn } from './helpers/auth';
import { createMockWorld, installApiMocks, USERS } from './helpers/fixtures';

test.describe('draft recovery and offline', () => {
  test('reloads dirty form and restores local values', async ({ page }) => {
    const world = createMockWorld();
    await installDeterministicRuntime(page);
    await installApiMocks(page, world);

    // Accept beforeunload leave prompts and recovery confirms.
    page.on('dialog', async (dialog) => {
      await dialog.accept();
    });

    await signIn(page, USERS.bob.username, 'ant.design', `/forms/${world.formCode}`);
    await expect(page.getByRole('heading', { name: '请假申请' })).toBeVisible();
    await page.getByLabel('请假事由').fill('本地恢复探亲');
    // recovery writer debounce is 500ms
    await page.waitForTimeout(700);
    await page.reload();
    await expect(page.getByLabel('请假事由')).toHaveValue('本地恢复探亲', { timeout: 10_000 });
  });

  test('offline submit keeps values unsent', async ({ page }) => {
    const world = createMockWorld();
    await installDeterministicRuntime(page);
    await installApiMocks(page, world);

    await signIn(page, USERS.bob.username, 'ant.design', `/forms/${world.formCode}`);
    await page.getByLabel('请假事由').fill('离线保留内容');
    await page.getByRole('button', { name: '提交' }).click();
    await page.getByText(USERS.admin.displayName, { exact: true }).click();
    await page.getByRole('button', { name: '完成' }).click();
    await expect(page.getByRole('heading', { name: '请确认本次提交内容' })).toBeVisible();
    await expect(page.getByText('离线保留内容')).toBeVisible();

    await page.context().setOffline(true);
    await expect(page.getByText('网络暂时不可用')).toBeVisible();
    await expect(page.getByRole('button', { name: '确认提交' })).toHaveCount(0);
    expect(world.startPostCount).toBe(0);
    expect(world.instances.size).toBe(0);

    await page.context().setOffline(false);
    await expect(page.getByRole('heading', { name: '请确认本次提交内容' })).toBeVisible();
    await expect(page.getByText('离线保留内容')).toBeVisible();
  });
});
