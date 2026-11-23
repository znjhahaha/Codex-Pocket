import {test, expect, type Page} from '@playwright/test';
import type {ProcessInfo} from '../src/domain';

async function dashboard(page: Page, processes: ProcessInfo[]) {
  await page.addInitScript((rows) => {
    (window as any).__TAURI_INTERNALS__ = {invoke: async (command: string) => {
      if (command !== 'dashboard') throw new Error(`Unexpected operation: ${command}`);
      return {computerName:'运行时展示 fixture', desired:true, version:'0.7.1-rc.20261122.48',
        supervisor:{phase:'running', updatedAt:Date.now()/1000, failures:0},
        relay:{state:'connected', host:'fixture.invalid'}, startup:false,
        devices:[{id:'fixture-phone', name:'测试手机'}], processes:rows,
        installation:{consistent:true},
        connection:{phase:'connected', connected:true, helperUpdatedAt:Date.now()/1000}};
    }};
  }, processes);
  await page.goto('/', {waitUntil:'domcontentloaded'});
  await expect(page.locator('.runtime-list .runtime')).toHaveCount(processes.length);
}

test('managed assistant reports its independent host instead of an absent bridge', async ({page}, testInfo) => {
  await dashboard(page, [{name:'cli', role:'managedHost', state:'在线', version:'unknown', helperVersion:'0.7.1-rc.20261121.47'}]);
  const row = page.locator('.runtime-list .runtime');
  await expect(row.locator('strong')).toHaveText('电脑助理');
  await expect(row.locator('p')).toHaveText('助理任务 · 独立宿主连接 · Helper 0.7.1-rc.20261121.47');
  await expect(row).not.toContainText('Bridge');
  await expect(row).not.toContainText('unknown');
  await expect(row.locator('.status')).toHaveText('在线');
  await row.screenshot({path:testInfo.outputPath('managed-assistant.png')});
});

test('real CLI and desktop keep distinct instances and unavailable bridge versions', async ({page}, testInfo) => {
  await dashboard(page, [
    {name:'cli', role:'managedHost', state:'在线', helperVersion:'helper-fixture'},
    {name:'cli', role:'cliDaemon', state:'在线', bridgeVersion:'unknown', helperVersion:'helper-fixture'},
    {name:'desktop', role:'desktop', state:'离线', helperVersion:'helper-fixture'},
  ]);
  const rows = page.locator('.runtime-list .runtime');
  await expect(rows.nth(0).locator('strong')).toHaveText('电脑助理');
  await expect(rows.nth(1).locator('strong')).toHaveText('Codex CLI');
  await expect(rows.nth(1).locator('p')).toHaveText('终端中的会话 · Bridge 未报告 · Helper helper-fixture');
  await expect(rows.nth(2).locator('strong')).toHaveText('Codex 桌面');
  await expect(rows.nth(2).locator('p')).toHaveText('桌面应用中的会话 · Bridge 未报告 · Helper helper-fixture');
  await expect(rows.nth(2).locator('.status')).toHaveText('离线');
  await page.locator('.runtime-list').screenshot({path:testInfo.outputPath('assistant-cli-desktop.png')});
});

test('known bridge and helper versions remain separate and retain the update notice', async ({page}) => {
  await dashboard(page, [{name:'desktop', role:'desktop', state:'在线', version:'native-fixture',
    bridgeVersion:'bridge-fixture', helperVersion:'helper-fixture', bridgeUpdatePending:true}]);
  const row = page.locator('.runtime-list .runtime');
  await expect(row.locator('p')).toHaveText('桌面应用中的会话 · Bridge bridge-fixture · Helper helper-fixture · 重开此运行环境后更新');
  await expect(row).not.toContainText('独立宿主');
});

test('legacy CLI without a role is never inferred to be the assistant', async ({page}) => {
  await dashboard(page, [{name:'cli', state:'在线', version:'unknown'}]);
  const row = page.locator('.runtime-list .runtime');
  await expect(row.locator('strong')).toHaveText('Codex CLI');
  await expect(row.locator('p')).toHaveText('终端中的会话 · Bridge 未报告 · Helper 未报告');
  await expect(row).not.toContainText('独立宿主');
});
