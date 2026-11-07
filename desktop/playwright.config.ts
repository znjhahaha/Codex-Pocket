import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests', workers: 1, timeout: 60_000,
  use: { baseURL: 'http://127.0.0.1:1420', viewport: { width: 1120, height: 850 }, launchOptions: { channel: 'msedge' } },
  webServer: { command: 'npm run dev', url: 'http://127.0.0.1:1420', reuseExistingServer: false, timeout: 30_000 },
  outputDir: '../.local/v02-desktop-ui',
});
