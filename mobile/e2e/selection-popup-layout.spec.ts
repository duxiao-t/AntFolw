import { expect, test } from '@playwright/test';
import { installDeterministicRuntime, signIn } from './helpers/auth';
import { createMockWorld, installApiMocks, USERS } from './helpers/fixtures';

test.use({ channel: 'chrome' });

test('keeps the selection sheet aligned with the mobile form canvas', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const world = createMockWorld();
  (world.form.schema as unknown[]).push({
    id: 'supply',
    type: 'select',
    label: '下拉单选',
    props: {
      showSearch: true,
      options: [{ label: '选项1', value: 'option_1' }],
    },
  });
  await installDeterministicRuntime(page);
  await installApiMocks(page, world);
  await signIn(page, USERS.admin.username, 'ant.design', `/forms/${world.formCode}`);

  await page.getByRole('button', { name: /选择下拉单选/ }).click();
  const root = await page.locator('#root').boundingBox();
  const sheet = await page.locator('.af-selection-sheet').boundingBox();

  expect(root).not.toBeNull();
  expect(sheet).not.toBeNull();
  expect(Math.abs((sheet?.x ?? 0) - (root?.x ?? 0))).toBeLessThan(1);
  expect(Math.abs((sheet?.width ?? 0) - (root?.width ?? 0))).toBeLessThan(1);
});
