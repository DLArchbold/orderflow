import { test, expect } from '@playwright/test';

test('home page loads', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'OrderFlow' })).toBeVisible();
});
// PLAN step 13: login -> create order -> see status move PENDING -> CONFIRMED.
