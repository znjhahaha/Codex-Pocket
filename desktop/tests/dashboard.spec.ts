import { test, expect } from '@playwright/test';
test('desktop pages, themes, pair countdown and explicit stop', async ({ page }, testInfo) => {
  await page.addInitScript(() => {
    const state = { computerName: 'Studio PC', desired: true, supervisor: { phase: 'running', pid: 1, childPid: 2, updatedAt: Date.now()/1000, failures: 0 }, relay: { state: 'connected', host: 'private-relay.example' }, devices: [{ id: 'test-phone', name: 'Mate 70 Pro', createdAt: Date.now()/1000 }], processes: [{ name: 'desktop', state: '在线' }, { name: 'cli', state: '在线' }], version: '0.2.0', startup: true };
    (window as any).__TAURI_INTERNALS__ = { invoke: async (cmd: string, args: any) => {
      if(cmd === 'dashboard') return { ...state, supervisor: {...state.supervisor,updatedAt:Date.now()/1000}, installation:{consistent:true}, connection:{phase:state.desired?'connected':'stopped',connected:state.desired,helperUpdatedAt:Date.now()/1000} };
      if(cmd === 'pair_device') return { payload: 'fixture-only-no-credentials', expiresAt: Date.now()/1000+300, baselineIds:['test-phone'] };
      if(cmd === 'set_remote') { state.desired = args.enabled; return; }
      if(cmd === 'set_startup') { state.startup = args.enabled; return; }
      if(cmd === 'export_diagnostics') return '本地诊断文件';
      if(cmd === 'revoke_device') { state.devices = state.devices.filter(d=>d.id!==args.id); return; }
      throw new Error('unexpected command');
    } };
  });
  await page.goto('/',{waitUntil:'domcontentloaded'});
  await expect(page.getByRole('heading', {name:'一切就绪，接着聊。'})).toBeVisible();
  await page.screenshot({path:testInfo.outputPath('overview-light.png'),fullPage:true});
  await page.getByRole('button',{name:'切换明暗主题'}).click();
  await expect(page.locator('html')).toHaveAttribute('data-theme','dark');
  await expect(page.getByRole('navigation').getByRole('button',{name:'概览',exact:true})).toHaveCSS('background-color','rgb(35, 35, 35)');
  await page.screenshot({path:testInfo.outputPath('overview-dark.png'),fullPage:true,animations:'disabled'});
  await page.getByRole('navigation').getByRole('button',{name:'设备',exact:true}).click();
  await expect(page.getByText('Mate 70 Pro',{exact:true})).toBeVisible();
  await page.getByRole('button',{name:'添加设备',exact:true}).click();
  await expect(page.getByRole('dialog')).toContainText('后失效');
  await page.getByRole('button',{name:'关闭配对窗口'}).click();
  await page.getByRole('navigation').getByRole('button',{name:'连接诊断'}).click();
  await page.screenshot({path:testInfo.outputPath('diagnosis-dark.png'),fullPage:true});
  await page.getByRole('navigation').getByRole('button',{name:'设置',exact:true}).click();
  await page.screenshot({path:testInfo.outputPath('settings-dark.png'),fullPage:true});
  await expect(page.getByRole('heading',{name:'偏好设置',exact:true})).toBeVisible();
  await page.getByRole('button',{name:'暂停连接',exact:true}).click();
  await page.getByRole('dialog').getByRole('button',{name:'暂停连接',exact:true}).click();
  await expect(page.getByRole('status').getByText('远程连接已暂停',{exact:true})).toBeVisible();
});

test('pairing failure remains actionable after a healthy dashboard refresh', async ({page})=>{
  await page.addInitScript(()=>{
    (window as any).__TAURI_INTERNALS__={invoke:async(cmd:string)=>{
      if(cmd==='dashboard')return {computerName:'Fixture',desired:true,supervisor:{phase:'running',updatedAt:Date.now()/1000,failures:0},relay:{state:'connected',host:'fixture.invalid'},devices:[],processes:[],version:'0.3.0',startup:true,installation:{consistent:true},connection:{phase:'connected',connected:true,helperUpdatedAt:Date.now()/1000},issues:[]};
      if(cmd==='pair_device')throw {code:'pairing_payload_invalid',stage:'pairing_payload',message:'配对码未能读取或已经过期，请重新生成配对码。',retryable:true,operationId:'fixture-operation'};
      throw new Error('Unexpected command');
    }};
  });
  await page.goto('/');
  await page.getByRole('button',{name:'连接新设备',exact:true}).click();
  await expect(page.getByRole('alert')).toContainText('配对码未能读取');
  await page.getByRole('button',{name:'刷新连接状态',exact:true}).click();
  await expect(page.getByRole('alert')).toContainText('fixture-operation');
  await page.getByRole('button',{name:'查看诊断',exact:true}).click();
  await expect(page.getByRole('heading',{name:'连接诊断',exact:true})).toBeVisible();
  await page.getByRole('button',{name:'关闭操作错误'}).click();
  await expect(page.getByRole('alert')).toHaveCount(0);
});

test('missing helper evidence cannot show online after a connected snapshot',async({page})=>{
  await page.addInitScript(()=>{
    (window as any).fixtureHelperPresent=true;
    (window as any).__TAURI_INTERNALS__={invoke:async(cmd:string)=>{
      if(cmd!=='dashboard')throw new Error('Unexpected command');
      return {computerName:'Fixture',desired:true,supervisor:{phase:'running',updatedAt:Date.now()/1000,failures:0},relay:{state:'connected',host:'fixture.invalid'},devices:[],processes:[],version:'0.3.0',startup:true,installation:{consistent:true},issues:[],...((window as any).fixtureHelperPresent?{connection:{phase:'connected',connected:true,helperUpdatedAt:Date.now()/1000}}:{})};
    }};
  });
  await page.goto('/');
  await expect(page.getByRole('heading',{name:'一切就绪，接着聊。'})).toBeVisible();
  await page.evaluate(()=>{(window as any).fixtureHelperPresent=false});
  await page.getByRole('button',{name:'刷新连接状态',exact:true}).click();
  await expect(page.getByRole('heading',{name:'正在恢复你的连接。'})).toBeVisible();
  await expect(page.getByRole('heading',{name:'一切就绪，接着聊。'})).toHaveCount(0);
  await expect(page.getByRole('button',{name:'连接新设备',exact:true})).toHaveCount(0);
  await page.evaluate(()=>{(window as any).fixtureHelperPresent=true});
  await page.getByRole('button',{name:'刷新连接状态',exact:true}).click();
  await expect(page.getByRole('heading',{name:'一切就绪，接着聊。'})).toBeVisible();
});

test('startup timeout and lost heartbeat have distinct visible states',async({page})=>{
  await page.addInitScript(()=>{
    (window as any).fixtureConnectionPhase='starting';
    const changedAt=Date.now()/1000;
    (window as any).__TAURI_INTERNALS__={invoke:async(cmd:string)=>{
      if(cmd!=='dashboard')throw new Error('Unexpected command');
      const phase=(window as any).fixtureConnectionPhase;
      return {computerName:'Fixture',desired:true,supervisor:{phase:'running',updatedAt:phase==='recovering'?Date.now()/1000-20:0,failures:0},relay:{state:'connected',host:'fixture.invalid'},devices:[],processes:[],version:'0.3.0',startup:true,installation:{consistent:true},issues:[],connection:{phase,connected:false,helperUpdatedAt:Date.now()/1000,desiredChangedAt:phase==='starting'?changedAt:changedAt-31}};
    }};
  });
  await page.goto('/');
  await expect(page.getByRole('heading',{name:'正在启动连接助手。'})).toBeVisible();
  await page.evaluate(()=>{(window as any).fixtureConnectionPhase='failed'});
  await page.getByRole('button',{name:'刷新连接状态',exact:true}).click();
  await expect(page.getByRole('heading',{name:'连接需要检查。'})).toBeVisible();
  await page.evaluate(()=>{(window as any).fixtureConnectionPhase='recovering'});
  await page.getByRole('button',{name:'刷新连接状态',exact:true}).click();
  await expect(page.getByRole('heading',{name:'正在恢复你的连接。'})).toBeVisible();
  await expect(page.getByRole('heading',{name:'正在启动连接助手。'})).toHaveCount(0);
});
