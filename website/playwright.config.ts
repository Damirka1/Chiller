import { defineConfig } from '@playwright/test';
const port = Number(process.env.PLAYWRIGHT_PORT || 4321);

export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  use: { baseURL: `http://127.0.0.1:${port}`, browserName: 'chromium', channel: process.env.CI ? undefined : 'chrome', trace: 'retain-on-failure' },
  webServer: { command: `npm run preview -- --port ${port}`, url: `http://127.0.0.1:${port}/Chiller/`, reuseExistingServer: false },
});
