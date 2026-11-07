import { defineConfig } from '@playwright/test';

// Locally this drives the installed Microsoft Edge (channel 'msedge') so no browser download is needed. CI (the CI
// environment variable is set by GitHub Actions) installs Playwright's own Chromium and uses the default channel.
const channel = process.env.CI ? undefined : (process.env.PW_CHANNEL ?? 'msedge');

export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
  timeout: 60_000,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.BASE_URL ?? 'http://localhost:5173',
    channel,
    trace: 'retain-on-failure',
  },
  webServer: process.env.BASE_URL
    ? undefined
    : { command: 'npm run dev', url: 'http://localhost:5173', reuseExistingServer: true },
});
