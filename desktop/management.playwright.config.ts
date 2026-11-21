import {defineConfig} from '@playwright/test';
export default defineConfig({
 testDir:'./tests',testMatch:'management.spec.ts',workers:1,timeout:60_000,
 use:{baseURL:'http://127.0.0.1:1443',viewport:{width:1120,height:760},launchOptions:{channel:'msedge'}},
 webServer:{command:'node node_modules/vite/bin/vite.js preview --host 127.0.0.1 --port 1443 --strictPort',url:'http://127.0.0.1:1443',reuseExistingServer:false,timeout:30_000},
 outputDir:'../.local/v070/desktop-browser',
});
