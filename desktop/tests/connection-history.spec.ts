import {test,expect,type Page} from '@playwright/test';
import {readFileSync} from 'node:fs';

function fixture() {
 const now=Math.floor(Date.now()/1000),target='a'.repeat(64);
 return {observedAt:now,computerName:'测试电脑',desired:true,version:'fixture',startup:true,
  supervisor:{phase:'running',updatedAt:now,failures:0},relay:{state:'connected',host:''},devices:[],processes:[],
  connection:{phase:'connected',connected:true,available:true,authenticatedPhone:true,helperUpdatedAt:now,usableRoutes:1,readyRouteIds:['lan']},
  installation:{consistent:true,installedGeneration:target,installedVersion:'fixture',
   ui:{state:'current',generation:target,runningVerified:true},supervisor:{state:'current',generation:target,runningVerified:true},helper:{state:'current',generation:target,runningVerified:true},
   upgrade:{phase:'rolled_back',failure:'rollback_failed',operationId:'previous-update',updatedAt:now-86400,fromGeneration:'b'.repeat(64),toGeneration:'c'.repeat(64),bridgeRestartRequired:true}},issues:[]};
}
async function open(page:Page,data:ReturnType<typeof fixture>) {
 await page.clock.install({time:new Date(data.observedAt*1000)});
 await page.clock.pauseAt(new Date(data.observedAt*1000));
 await page.addInitScript(data=>{
  (window as any).__TAURI_INTERNALS__={invoke:async(command:string)=>{
   if(command==='dashboard')return data;
   throw Error('Read-only replay attempted '+command);
  }};
 },data);
 await page.goto('/',{waitUntil:'domcontentloaded'});
}

test('superseded rollback does not hide a connected phone or disable pairing',async({page},info)=>{
 const data=process.env.POCKET_DASHBOARD_SNAPSHOT
  ?JSON.parse(readFileSync(process.env.POCKET_DASHBOARD_SNAPSHOT,'utf8')):fixture();
 await open(page,data);
 await expect(page.getByRole('heading',{name:'一切就绪，接着聊。'})).toBeVisible();
 await expect(page.locator('.status-caption')).toHaveText('已连接');
 await expect(page.locator('.connection-facts')).toContainText('已连接');
 await expect(page.getByRole('button',{name:'添加设备',exact:true})).toBeEnabled();
 await expect(page.getByText('助手未响应',{exact:true})).toHaveCount(0);
 await page.screenshot({path:info.outputPath('connected-after-update.png'),fullPage:true,animations:'disabled'});
 await page.getByRole('navigation').getByRole('button',{name:'更新与修复',exact:true}).click();
 await page.getByText('诊断详情 · 运行环境与安装记录',{exact:true}).click();
 await expect(page.getByText(/上次后台更新记录：/)).toBeVisible();
 await expect(page.getByText('正在运行的 Codex bridge 仍等待重启生效。',{exact:false})).toHaveCount(0);
});

test('active handoff explains the update without misreporting a responding helper',async({page})=>{
 const data=fixture();data.installation.upgrade.phase='verifying';data.installation.upgrade.toGeneration=data.installation.installedGeneration;
 await open(page,data);
 await expect(page.locator('.connection-focus h1')).toHaveText('正在核验新后台');
 await expect(page.getByText('助手未响应',{exact:true})).toHaveCount(0);
 await expect(page.getByRole('button',{name:'添加设备',exact:true})).toBeDisabled();
});

test('historical failure cannot make stale current evidence look connected',async({page})=>{
 const data=fixture();data.connection.helperUpdatedAt-=20;
 await open(page,data);
 await expect(page.getByRole('heading',{name:'一切就绪，接着聊。'})).toHaveCount(0);
 await expect(page.getByRole('button',{name:'添加设备',exact:true})).toBeDisabled();
});
