import {defineConfig} from '@playwright/test';
import {fileURLToPath} from 'node:url';
export default defineConfig({testDir:'.',testMatch:'silentStartup.browser.spec.ts',workers:1,timeout:30_000,
 use:{baseURL:'http://127.0.0.1:1433',viewport:{width:1120,height:850},launchOptions:process.env.CI?{}:{channel:'msedge'}},
 webServer:{command:'npx vite preview --host 127.0.0.1 --port 1433 --strictPort',cwd:fileURLToPath(new URL('..',import.meta.url)),url:'http://127.0.0.1:1433',reuseExistingServer:false},
 outputDir:'../../.local/desktop-powershell-browser-20261112'});
