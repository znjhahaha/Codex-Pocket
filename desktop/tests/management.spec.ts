import {test,expect} from '@playwright/test';
test('downloaded update restarts once with automatic updates disabled and can retry dispatch failure',async({page})=>{
 await page.addInitScript(()=>{
  const calls:any[]=[];(window as any).restartCalls=calls;
  (window as any).restartFixture={requested:false,failed:false};
  (window as any).__TAURI_INTERNALS__={invoke:async(cmd:string,args:any)=>{
   const f=(window as any).restartFixture;
   if(cmd==='dashboard')return {computerName:'Windows',desired:true,version:'1.2.3',startup:true,startupStatus:'known',supervisor:{phase:'running',updatedAt:Date.now()/1000,failures:0},connection:{phase:'ready',available:true,helperUpdatedAt:Date.now()/1000},relay:{state:'ready',host:''},devices:[],processes:[],updates:{operationId:'b85e78a5-4874-4217-9b56-cf77315a6f36',automatic:false,phase:'waiting_idle',version:'1.2.3',downloadedBytes:113243095,totalBytes:113243095,restartRequested:f.requested,coordinator:{phase:f.failed?'waiting_login':'waiting',reason:'runtime_activity_unverified'}},installation:{installedVersion:'1.2.2',helper:{runningVerified:true,version:'1.2.2'}}};
   if(cmd==='restart_update'){calls.push({cmd,...args});await new Promise(resolve=>setTimeout(resolve,350));f.requested=true;f.failed=false;return;}
   if(cmd==='detect_lan')return [];
   if(cmd==='plugin:event|listen')return 1;
   if(cmd==='plugin:event|unlisten')return;
   throw new Error('Unexpected fixture command '+cmd);
  }};
 });
 await page.goto('/');
 await page.getByRole('navigation',{name:'主导航'}).getByRole('button',{name:'更新与修复',exact:true}).click();
 const restart=page.getByRole('button',{name:'重启并更新',exact:true});
 await expect(restart).toBeEnabled();
 await page.screenshot({path:'../.local/restart-update-20261119/button.png',fullPage:true});
 await restart.evaluate((button:HTMLButtonElement)=>{button.click();button.click();});
 await expect(page.getByRole('button',{name:'已安排重启更新'})).toBeDisabled();
 expect(await page.evaluate(()=>(window as any).restartCalls)).toEqual([{cmd:'restart_update',operationId:'b85e78a5-4874-4217-9b56-cf77315a6f36'}]);
 await expect(page.getByRole('switch',{name:'自动下载与更新'})).toHaveAttribute('aria-checked','false');
 await page.evaluate(()=>{(window as any).restartFixture.failed=true;});
 await page.getByRole('button',{name:'重新检查',exact:true}).click();
 await expect(restart).toBeEnabled();
 await restart.click();
 await expect.poll(async()=>page.evaluate(()=>(window as any).restartCalls.length)).toBe(2);
});
test('four management pages use native commands and preserve pending readiness',async({page})=>{
 await page.addInitScript(()=>{
  const calls:string[]=[];(window as any).managementCalls=calls;
  let automatic=true,phase='staged';
  (window as any).__TAURI_INTERNALS__={invoke:async(cmd:string,args:any)=>{
   if(cmd==='dashboard')return {computerName:'Fixture Windows',desired:true,version:'0.6.6',startup:true,supervisor:{phase:'running',updatedAt:Date.now()/1000,failures:0},connection:{phase:'ready',available:true,connected:false,helperUpdatedAt:Date.now()/1000},relay:{state:'ready',host:''},devices:[{id:'fixture-device',name:'Fixture Android'}],processes:[],runtimeReadiness:{authoritative:true,ready:false,updatedAt:Date.now()/1000,nativeObservation:{desktop:{state:'detected',version:'26.1111.2228.0'},cli:{state:'detected'}},runtimes:[{id:'d',role:'desktop',online:true,initialized:false,schemaReady:true,inventoryReady:false,ready:false},{id:'aux',role:'auxiliary',online:true,initialized:true,schemaReady:true,inventoryReady:true,ready:true}]},assistantReadiness:{ready:false,runtimeReady:false,error:'assistant_runtime_start_failed'},updates:{automatic,phase,version:'1.2.3',downloadedBytes:1024,totalBytes:1024}};
   if(cmd==='set_automatic_updates'){calls.push(cmd);automatic=args.enabled;return;}
   if(cmd==='check_updates'){calls.push(cmd);phase='waiting_idle';return {phase};}
   if(cmd==='repair_assistant'){calls.push(cmd);return {requested:true};}
   if(cmd==='detect_lan')return [];
   if(cmd==='plugin:event|listen')return 1;
   if(cmd==='plugin:event|unlisten')return;
   throw new Error('Unexpected fixture command '+cmd);
  }};
 });
 await page.goto('/');
 const nav=page.getByRole('navigation',{name:'主导航'});
 await expect(nav.getByRole('button')).toHaveCount(4);
 await expect(page.getByText('等待初始化',{exact:true})).toBeVisible();
 await expect(page.getByText('官方桌面正在运行 · 26.1111.2228.0 · 实时控制未核验。')).toBeVisible();
 await expect(page.getByText('CLI 正在运行 · 未连接共享服务。')).toBeVisible();
 await expect(page.getByText('实时控制已就绪',{exact:true})).toHaveCount(0);
 await page.screenshot({path:'../.local/desktop-runtime-detection-20261118/overview.png',fullPage:true});
 await nav.getByRole('button',{name:'设备与连接',exact:true}).click();
 await expect(page.getByRole('heading',{name:'设备与连接',exact:true})).toBeVisible();
 await expect(page.getByText('Fixture Android',{exact:true})).toBeVisible();
 await nav.getByRole('button',{name:'更新与修复',exact:true}).click();
 await expect(page.getByText('安装与运行版本',{exact:true})).not.toBeVisible();
 await expect(page.getByText('原生运行时',{exact:true})).not.toBeVisible();
 await expect(page.getByRole('button',{name:'导出诊断',exact:true})).toHaveCount(1);
 await expect(page.getByText('assistant_runtime_start_failed',{exact:true})).toHaveCount(0);
 expect(await page.locator('.app-header').evaluate(e=>getComputedStyle(e).position)).toBe('relative');
 await page.getByRole('button',{name:'检查并下载更新',exact:true}).click();
 await expect(page.getByRole('heading',{name:'等待任务空闲',exact:true})).toBeVisible();
 await page.getByRole('switch',{name:'自动下载与更新'}).click();
 await expect(page.getByRole('switch',{name:'自动下载与更新'})).toHaveAttribute('aria-checked','false');
 await page.getByRole('button',{name:'重新准备助理'}).click();
 await expect(page.getByText('运行时等待准备',{exact:true})).toBeVisible();
 expect(await page.evaluate(()=>(window as any).managementCalls)).toEqual(['check_updates','set_automatic_updates','repair_assistant']);
 await page.evaluate(()=>window.scrollTo(0,0));
 await page.screenshot({path:'../.local/v070/desktop-management-light.png',fullPage:true});
 await page.getByRole('button',{name:'切换明暗主题'}).click();
 await page.screenshot({path:'../.local/v070/desktop-management-dark.png',fullPage:true});
 await page.getByText('诊断详情 · 运行环境与安装记录',{exact:true}).click();
 await expect(page.getByText('安装与运行版本',{exact:true})).toBeVisible();
 await nav.getByRole('button',{name:'设置',exact:true}).click();
 await expect(page.getByRole('heading',{name:'偏好设置',exact:true})).toBeVisible();
});
