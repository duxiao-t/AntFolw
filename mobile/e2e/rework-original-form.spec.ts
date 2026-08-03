import { expect, test } from '@playwright/test';
import { installDeterministicRuntime, signIn, signOutViaSecurity } from './helpers/auth';
import { createMockWorld, installApiMocks, USERS } from './helpers/fixtures';

test.describe('rework original form flow', () => {
  test('first-level rejection returns the same instance and form to the applicant', async ({ page }) => {
    const world = createMockWorld();
    await installDeterministicRuntime(page);
    await installApiMocks(page, world);

    await signIn(page, USERS.bob.username, 'ant.design', `/forms/${world.formCode}`);
    await page.getByLabel('请假事由').fill('原始请假事由');
    await page.getByRole('button', { name: '提交' }).click();
    await page.getByText(USERS.admin.displayName, { exact: true }).click();
    await page.getByRole('button', { name: '完成' }).click();
    await page.getByRole('button', { name: '确认提交' }).click();
    await expect(page.getByRole('heading', { name: '提交成功' })).toBeVisible();

    const instanceId = [...world.instances.keys()][0]!;
    const originalBusinessNo = world.instances.get(instanceId)?.businessNo;

    await signOutViaSecurity(page);
    await signIn(page, USERS.admin.username, 'ant.design', '/tasks?view=pending');
    await page.getByRole('link', { name: /请假申请/ }).first().click();
    await page.getByRole('button', { name: '驳回' }).click();
    await page.getByRole('button', { name: '数据不一致' }).click();
    await page.getByRole('button', { name: '确认驳回' }).click();
    await expect(page.getByRole('heading', { name: '需要你处理的审批' })).toBeVisible();

    const reworkTask = [...world.tasks.values()].find((task) => task.taskType === 'REWORK');
    expect(reworkTask?.taskStatus).toBe('PENDING');
    expect(world.instances.size).toBe(1);
    expect(world.instances.get(instanceId)?.businessNo).toBe(originalBusinessNo);
    expect(world.instances.get(instanceId)?.formData).toMatchObject({ reason: '原始请假事由' });

    await signOutViaSecurity(page);
    await signIn(page, USERS.bob.username, 'ant.design', '/tasks?view=pending');
    await expect(page.getByText('待修改', { exact: true })).toBeVisible();
    await page.getByRole('link', { name: /请假申请/ }).click();

    const reason = page.getByLabel('请假事由');
    await expect(reason).toHaveValue('原始请假事由');
    await reason.fill('修改后的请假事由');
    await page.getByRole('button', { name: '提交' }).click();
    await expect(page.getByText('本次提交将保留原单号')).toBeVisible();
    await page.getByRole('button', { name: '确认重提' }).click();
    await expect(page.getByRole('heading', { name: '提交成功' })).toBeVisible();

    expect(world.instances.size).toBe(1);
    expect(world.instances.get(instanceId)?.businessNo).toBe(originalBusinessNo);
    expect(world.instances.get(instanceId)?.formData).toMatchObject({ reason: '修改后的请假事由' });
    expect(world.tasks.get(reworkTask!.id)?.taskStatus).toBe('RESUBMITTED');
    expect([...world.tasks.values()].some((task) =>
      task.taskType === 'APPROVAL' && task.taskStatus === 'PENDING'
      && task.ownerUserId === USERS.admin.id)).toBe(true);
  });
});
