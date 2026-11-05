import { expect, test } from '@playwright/test';

test('home page loads and offers a login', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('button', { name: 'Log in' })).toBeVisible();
});
