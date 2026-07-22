import { expect, test, type Page } from '@playwright/test';
import {
  expectVisualIntegrity,
  installDeterministicRuntime,
  signIn,
} from './helpers/auth';
import { createMockWorld, installApiMocks, USERS } from './helpers/fixtures';

async function capture(page: Page, name: string) {
  await expectVisualIntegrity(page);
  await expect(page).toHaveScreenshot(`${name}.png`, {
    fullPage: true,
    animations: 'disabled',
  });
}

test.describe('key pages visual regression', () => {
  test('captures approved enterprise key pages', async ({ page }) => {
    const world = createMockWorld({
      seedPendingTask: true,
      seedStartedProcess: true,
      seedDrafts: true,
    });
    await installDeterministicRuntime(page);
    await installApiMocks(page, world);

    page.on('dialog', (dialog) => {
      void dialog.dismiss();
    });

    const taskId = [...world.tasks.keys()][0]!;
    const instanceId = [...world.instances.keys()][0]!;

    // 1 login
    await page.goto('/mobile/login');
    await expect(page.getByRole('heading', { name: '登录 AntFlow' })).toBeVisible();
    await capture(page, '01-login');

    // 2 workbench
    await signIn(page, USERS.admin.username, 'ant.design', '/workbench');
    await expect(page.getByTestId('workbench')).toBeVisible();
    await capture(page, '02-workbench');

    // 3 apps
    await page.goto('/mobile/apps');
    await expect(page.getByRole('heading', { name: '应用目录' })).toBeVisible();
    await capture(page, '03-apps');

    // 4 favorites
    await page.goto('/mobile/apps/favorites');
    await expect(page.getByRole('heading', { name: '常用应用' })).toBeVisible();
    await capture(page, '04-favorites');

    // 5 form
    await page.goto(`/mobile/forms/${world.formCode}`);
    await expect(page.getByRole('heading', { name: '请假申请' })).toBeVisible();
    await page.getByLabel('请假事由').fill('视觉回归');
    await capture(page, '05-form');

    // 6 self-select
    await page.getByRole('button', { name: '下一步' }).click();
    await expect(page.getByRole('heading', { name: '选择审批人' })).toBeVisible();
    await capture(page, '06-self-select');

    // 7 confirm
    await page.getByText(USERS.admin.displayName, { exact: true }).click();
    await page.getByRole('button', { name: '确认选择' }).click();
    await expect(page.getByRole('heading', { name: '提交确认' })).toBeVisible();
    await capture(page, '07-confirm');

    // 8 success
    await page.getByRole('button', { name: '提交' }).click();
    await expect(page.getByRole('heading', { name: '提交成功' })).toBeVisible();
    await capture(page, '08-success');

    // 9 pending
    await page.goto('/mobile/tasks?view=pending');
    await expect(page.getByRole('heading', { name: '任务中心' })).toBeVisible();
    await capture(page, '09-pending');

    // 10 task detail
    await page.goto(`/mobile/tasks/${taskId}`);
    await expect(page.getByText('回家探亲')).toBeVisible();
    await capture(page, '10-task-detail');

    // 11 approve sheet
    await page.getByRole('button', { name: '同意' }).click();
    await expect(page.getByLabel('同意审批')).toBeVisible();
    await capture(page, '11-approve-sheet');
    await page.keyboard.press('Escape');
    // close sheet by clicking outside if still open
    if (await page.getByLabel('同意审批').isVisible().catch(() => false)) {
      await page.mouse.click(20, 20);
    }

    // 12 reject sheet
    await page.goto(`/mobile/tasks/${taskId}`);
    await page.getByRole('button', { name: '驳回' }).click();
    await expect(page.getByLabel('驳回审批')).toBeVisible();
    await capture(page, '12-reject-sheet');

    // 13 started
    await page.goto('/mobile/tasks?view=process');
    await expect(page.getByRole('heading', { name: '任务中心' })).toBeVisible();
    await capture(page, '13-started');

    // 14 process detail
    await page.goto(`/mobile/processes/${instanceId}`);
    await expect(page.getByRole('heading', { name: '请假申请' })).toBeVisible();
    await capture(page, '14-process-detail');

    // 15 done
    await page.goto('/mobile/tasks?view=done');
    await expect(page.getByRole('heading', { name: '任务中心' })).toBeVisible();
    await capture(page, '15-done');

    // 16 profile
    await page.goto('/mobile/profile');
    await expect(page.getByRole('heading', { name: '我的' })).toBeVisible();
    await capture(page, '16-profile');

    // 17 drafts
    await page.goto('/mobile/forms/drafts');
    await expect(page.getByRole('heading', { name: /草稿/ })).toBeVisible();
    await capture(page, '17-drafts');

    // 18 security
    await page.goto('/mobile/profile/security');
    await expect(page.getByRole('heading', { name: '账号与安全' })).toBeVisible();
    await capture(page, '18-security');

    // 19 offline — set offline while already on a loaded page (goto fails offline).
    await page.goto('/mobile/workbench');
    await expect(page.getByTestId('workbench')).toBeVisible();
    await page.context().setOffline(true);
    await expect(page.getByText('网络已断开，正在尝试恢复…')).toBeVisible();
    await capture(page, '19-offline');
    await page.context().setOffline(false);
  });
});
