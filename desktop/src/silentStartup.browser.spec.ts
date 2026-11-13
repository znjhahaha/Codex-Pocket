import {test,expect} from '@playwright/test';

async function fixture(page:import('@playwright/test').Page){
 await page.clock.install();
 await page.addInitScript(()=>{
  const w=window as unknown as {__TAURI_INTERNALS__:unknown;calls:string[]};w.calls=[];
  let checkedAt:number|null=null,startup:boolean|null=null;
  w.__TAURI_INTERNALS__={invoke:async(command:string,args?:{enabled:boolean})=>{
   w.calls.push(command);
   if(command==='check_startup'){checkedAt=Date.now();startup=true;return true;}
   if(command==='set_startup'){checkedAt=Date.now();startup=args!.enabled;return;}
   if(command==='repair_installed_integration')throw {stage:'installation_repair',code:'repair_capability_unknown',message:'修复能力尚未核验',operationId:'fixture-id',retryable:true};
   if(command==='dashboard'){
    const fresh=checkedAt!==null&&Date.now()-checkedAt<60000;
    return {computerName:'Fixture',desired:false,supervisor:{phase:'stopped',updatedAt:0,failures:0},relay:{state:'stopped',host:''},devices:[],processes:[],version:'fixture',startup:fresh?startup:null,startupStatus:fresh?'known':'unknown',
     installation:{consistent:true,installedGeneration:'a'.repeat(64),ui:{runningVerified:true,state:'current',generation:'a'.repeat(64)},optionalIntegrations:{recordState:'available',installationState:'installed_incomplete',repairSupported:false,repairCheckAvailable:true,recoveryState:'clear',steps:[{step:'shortcut_0',state:'completed'},{step:'shortcut_1',state:'completed'},{step:'shortcut_2',state:'completed'},{step:'startup',state:'pending'}]}}};
   }
   throw Error(`Unexpected fixture command ${command}`);
  }};
 });
 await page.goto('/');
}

test('polling over two expiry windows never checks startup or repairs until clicked',async({page})=>{
 await fixture(page);
 await page.getByRole('button',{name:'设置',exact:true}).click();
 await expect(page.getByRole('switch',{name:'登录后自动运行'})).toBeDisabled();
 for(let i=0;i<26;i++){await page.clock.runFor(5000);}
 const automatic=await page.evaluate(()=>(window as unknown as {calls:string[]}).calls);
 expect(automatic.length).toBeGreaterThan(2);expect(automatic.every(call=>call==='dashboard')).toBe(true);
 await page.getByRole('button',{name:'核验登录任务',exact:true}).click();
 await expect(page.getByRole('switch',{name:'登录后自动运行'})).toBeEnabled();
 await expect(page.getByRole('switch',{name:'登录后自动运行'})).toBeChecked();
 await page.clock.runFor(65000);
 await expect(page.getByRole('switch',{name:'登录后自动运行'})).toBeDisabled();
 let calls=await page.evaluate(()=>(window as unknown as {calls:string[]}).calls);
 expect(calls.filter(call=>call==='check_startup')).toHaveLength(1);
 expect(calls.filter(call=>call==='repair_installed_integration')).toHaveLength(0);
 await page.getByRole('button',{name:'核验登录任务',exact:true}).click();
 await expect(page.getByRole('switch',{name:'登录后自动运行'})).toBeEnabled();
 await page.getByRole('switch',{name:'登录后自动运行'}).click();
 await expect(page.getByRole('switch',{name:'登录后自动运行'})).not.toBeChecked();
 calls=await page.evaluate(()=>(window as unknown as {calls:string[]}).calls);
 expect(calls.filter(call=>call==='check_startup')).toHaveLength(2);
 expect(calls.filter(call=>call==='set_startup')).toHaveLength(1);
});

test('native integrity evidence defers protocol probing until a specific repair click',async({page})=>{
 await fixture(page);await page.getByRole('button',{name:'查看详情',exact:true}).click();
 await expect(page.getByText(/修复能力待核验/)).toBeVisible();
 for(let i=0;i<26;i++){await page.clock.runFor(5000);}
 expect(await page.evaluate(()=>(window as unknown as {calls:string[]}).calls.filter(call=>call==='repair_installed_integration'))).toEqual([]);
 await page.getByRole('button',{name:'重试登录后台任务',exact:true}).click();
 await expect(page.getByText(/修复能力尚未核验/).last()).toBeVisible();
 await expect(page.getByRole('button',{name:'重试登录后台任务',exact:true})).toBeEnabled();
 await page.getByRole('button',{name:'重试登录后台任务',exact:true}).click();
 expect(await page.evaluate(()=>(window as unknown as {calls:string[]}).calls.filter(call=>call==='repair_installed_integration'))).toHaveLength(2);
});
