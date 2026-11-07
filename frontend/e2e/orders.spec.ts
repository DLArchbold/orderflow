import { Browser, expect, Page, test } from '@playwright/test';

// The only test layer that exercises the browser, the Keycloak redirect, the API, Kafka and both databases together.
// Kept to the few flows that matter: browser tests are slow and flaky when there are many.

async function openAs(browser: Browser, user: 'alice' | 'bob'): Promise<Page> {
  const context = await browser.newContext({ storageState: `e2e/.auth/${user}.json` });
  const page = await context.newPage();
  await page.goto('/');
  await page.getByRole('button', { name: 'Log in' }).click();   // the saved Keycloak session signs in without a password
  await expect(page.getByText(`Signed in as ${user}`)).toBeVisible();
  return page;
}

async function createOrder(page: Page, quantity: number) {
  await page.getByLabel('SKU').fill('SKU-1');
  await page.getByLabel('Quantity').fill(String(quantity));
  await page.getByRole('button', { name: 'Create order' }).click();
}

// a quantity that is unique to this run finds the right row among older ones
const uniqueQuantity = () => 1_000 + Math.floor(Math.random() * 9_000);   // small, so repeated runs do not drain the stock
const row = (page: Page, quantity: number) => page.getByRole('listitem').filter({ hasText: `${quantity} x SKU-1` }).first();

test('alice creates an order and sees it become CONFIRMED without refreshing', async ({ browser }) => {
  const page = await openAs(browser, 'alice');
  const quantity = uniqueQuantity();
  await createOrder(page, quantity);

  await expect(row(page, quantity)).toContainText('PENDING');
  await expect(row(page, quantity)).toContainText('CONFIRMED', { timeout: 15_000 });
});

test('an order for more than the stock becomes REJECTED', async ({ browser }) => {
  const page = await openAs(browser, 'alice');
  const quantity = 1_000_001 + Math.floor(Math.random() * 900_000);   // above the seeded 1,000,000 and unique to this run
  await createOrder(page, quantity);

  await expect(row(page, quantity)).toContainText('PENDING');
  await expect(row(page, quantity)).toContainText('REJECTED', { timeout: 15_000 });
});

test("bob's page does not show alice's orders", async ({ browser }) => {
  const alice = await openAs(browser, 'alice');
  const quantity = uniqueQuantity();
  await createOrder(alice, quantity);
  await expect(row(alice, quantity)).toBeVisible();

  const bob = await openAs(browser, 'bob');
  await expect(bob.getByRole('list', { name: 'orders' })).toBeAttached();   // empty list: zero height, so not "visible"
  await expect(bob.getByText(`${quantity} x SKU-1`)).toHaveCount(0);
});
