import {test,expect} from '@playwright/test';

async function fixture(page:import('@playwright/test').Page,supported=true,unknown=false,verifiedUi=true) {
 await page.addInitScript(({supported,unknown,verifiedUi})=>{
  const w=window as unknown as {__TAURI_INTERNALS__:unknown;repairCalls:unknown[]};w.repairCalls=[];
  w.__TAURI_INTERNALS__={invoke:async(command:string,args:Record<string,unknown>)=>{
   if(command==='dashboard')return {computerName:'Fixture',desired:false,supervisor:{phase:'stopped',updatedAt:0,failures:0},relay:{state:'stopped',host:''},devices:[],processes:[],version:'fixture',startup:false,
    installation:{consistent:true,installedGeneration:'a'.repeat(64),
     ui:verifiedUi?{generation:'a'.repeat(64),runningVerified:true,state:'current'}:undefined,
     optionalIntegrations:{recordState:'available',repairSupported:supported,recoveryState:'clear',installationState:'installed_incomplete',steps:[
     {step:'shortcut_0',state:'completed'},{step:'shortcut_1',state:'pending',stage:'shortcut_verify',code:'shortcut_integration_failed',hresult:'0x80004005'},
     {step:'shortcut_2',state:'completed'},{step:'startup',state:'pending',stage:'startup_read',code:'startup_snapshot_unknown'}]}}};
   if(command==='repair_installed_integration'){
    w.repairCalls.push(args);const request=args.request as Record<string,unknown>;
    const checking=request.checkRepair===true;
    return {...request,schemaVersion:1,action:'repair_integration',state:unknown&&!checking?'unknown':'failed',code:unknown&&!checking?'operation_unknown':'integration_repair_failed',stage:'startup_register',call:'Set-PocketTaskXml',hresult:'0x80070005',exitCode:unknown&&!checking?null:1,recoveryState:unknown&&!checking?'unknown':'clear'};
   }
   if(command==='export_diagnostics')return 'fixture-diagnostics.json';
   throw Error('Unexpected fixture command');
  }};
 },{supported,unknown,verifiedUi});
 await page.goto('/');await page.getByRole('button',{name:'查看详情',exact:true}).click();
}
test('verified current capability enables only pending steps and keeps truthful failure',async({page})=>{
 await fixture(page);
 await expect(page.getByRole('button',{name:'重试开始菜单入口',exact:true})).toBeDisabled();
 await expect(page.getByRole('button',{name:'重试旧桌面入口清理',exact:true})).toBeEnabled();
 await expect(page.getByRole('button',{name:'重试旧登录快捷方式清理',exact:true})).toBeDisabled();
 await page.getByRole('button',{name:'重试登录后台任务',exact:true}).click();
 await expect(page.getByText(/所选集成修复失败/)).toContainText('integration_repair_failed');
 await expect(page.getByText(/所选集成修复失败/)).toContainText('0x80070005');
 const calls=await page.evaluate(()=> (window as unknown as {repairCalls:unknown[]}).repairCalls);
 expect(calls).toHaveLength(1);expect(calls[0]).toMatchObject({request:{step:'startup',expectedGeneration:'a'.repeat(64)}});
 expect(Object.keys((calls[0] as {request:object}).request).sort()).toEqual(['expectedGeneration','operationId','step']);
 await page.screenshot({path:'../.local/install-repair-ui/failure.png',fullPage:true});
});
test('old installed scripts stay read-only',async({page})=>{
 await fixture(page,false);
 for(const name of ['重试开始菜单入口','重试旧桌面入口清理','重试旧登录快捷方式清理','重试登录后台任务'])await expect(page.getByRole('button',{name,exact:true})).toBeDisabled();
 await expect(page.getByText(/当前安装器尚不支持分项修复/)).toBeVisible();
});

test('unverified current UI cannot repair an otherwise supported installation',async({page})=>{
 await fixture(page,true,false,false);
 for(const name of ['重试旧桌面入口清理','重试登录后台任务'])await expect(page.getByRole('button',{name,exact:true})).toBeDisabled();
 expect(await page.evaluate(()=> (window as unknown as {repairCalls:unknown[]}).repairCalls)).toEqual([]);
});
test('unknown operation survives remount and checks the same ID without a fresh repair',async({page})=>{
 await fixture(page,true,true);
 await page.getByRole('button',{name:'重试登录后台任务',exact:true}).click();
 await expect(page.getByRole('button',{name:'核对上次修复',exact:true})).toBeEnabled();
 await expect(page.getByRole('button',{name:'重试旧桌面入口清理',exact:true})).toBeDisabled();
 const originalId=await page.evaluate(()=> (window as unknown as {repairCalls:{request:{operationId:string}}[]}).repairCalls[0].request.operationId);
 await page.reload();await page.getByRole('button',{name:'查看详情',exact:true}).click();
 await page.getByRole('button',{name:'核对上次修复',exact:true}).click();
 await expect(page.getByText(/所选集成修复失败/)).toBeVisible();
 const calls=await page.evaluate(()=> (window as unknown as {repairCalls:{request:Record<string,unknown>}[]}).repairCalls);
 expect(calls).toHaveLength(1);expect(calls[0].request.checkRepair).toBe(true);
 expect(calls[0].request.step).toBe('startup');
 expect(calls[0].request.operationId).toBe(originalId);
 await expect(page.getByRole('button',{name:'核对上次修复',exact:true})).toHaveCount(0);
});
test('local missing required config reports state read rather than a network failure',async({page})=>{
 await page.addInitScript(()=>{
  (window as unknown as {__TAURI_INTERNALS__:unknown}).__TAURI_INTERNALS__={invoke:async(command:string)=>{
   if(command==='dashboard')return {computerName:'Fixture',desired:false,supervisor:{phase:'stopped',updatedAt:0,failures:0},relay:{state:'stopped',host:''},devices:[],processes:[],version:'fixture',startup:false,installation:{consistent:true},
    issues:[{stage:'state_read',code:'state_config_missing',message:'未找到必需的本机配置。',operationId:'1794316209-20576-24',osCode:2,retryable:false}]};
   throw Error('Unexpected fixture command');
  }};
 });
 await page.goto('/');await page.getByRole('button',{name:'查看详情',exact:true}).click();
 await expect(page.getByText(/state_read · state_config_missing · OS 2/).first()).toBeVisible();
 await expect(page.getByText(/state_read · state_config_missing · OS 2/).first()).toContainText('1794316209-20576-24');
 await expect(page.getByText('route_configuration_failed')).toHaveCount(0);
});
