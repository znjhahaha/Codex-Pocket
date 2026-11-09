import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests', workers: 1, timeout: 60_000,
  use: { baseURL: 'http://127.0.0.1:1422', viewport: { width: 1120, height: 850 }, launchOptions: process.env.CI ? {} : { channel: 'msedge' } },
  webServer: { command: 'npm run build && npx vite preview --host 127.0.0.1 --port 1422 --strictPort', url: 'http://127.0.0.1:1422', reuseExistingServer: false, timeout: 60_000 },
  outputDir: '../.local/v06-desktop-ui',
});
