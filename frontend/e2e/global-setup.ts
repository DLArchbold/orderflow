import { chromium, FullConfig } from '@playwright/test';
import fs from 'node:fs';

// Logs in once per user through the real Keycloak form and saves the cookies, so the tests do not log in each time.
// The app keeps its token in memory, so a saved state holds Keycloak's SSO cookie: opening the app and pressing
// "Log in" then signs in without asking for the password again.
const USERS = ['alice', 'bob'];

export default async function globalSetup(config: FullConfig) {
  const baseURL = config.projects[0].use.baseURL ?? 'http://localhost:5173';
  const channel = config.projects[0].use.channel;
  fs.mkdirSync('e2e/.auth', { recursive: true });

  const browser = await chromium.launch({ channel });
  for (const user of USERS) {
    const context = await browser.newContext();
    const page = await context.newPage();
    await page.goto(baseURL);
    await page.getByRole('button', { name: 'Log in' }).click();
    await page.locator('#username').fill(user);
    await page.locator('#password').fill(`${user}-dev-pw`);
    await page.locator('#kc-login').click();
    await page.getByText(`Signed in as ${user}`).waitFor();
    await context.storageState({ path: `e2e/.auth/${user}.json` });
    await context.close();
  }
  await browser.close();
}
