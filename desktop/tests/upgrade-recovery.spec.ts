import {test,expect,type Page} from '@playwright/test';

type Scenario='old'|'missing'|'waiting'|'starting'|'exhausted'|'recovered'|'current'|'waiting_restart'|'verifying';
async function fixture(page:Page,scenario:Scenario='old') {
 await page.addInitScript(scenario=>{
  const w=window as any,now=Date.now()/1000,target='a'.repeat(64),old='b'.repeat(64);
  const absent=['missing','waiting','starting','exhausted'].includes(scenario);
  const outdated=['old','waiting_restart','verifying'].includes(scenario);
  w.recoveryCalls=[];w.deferRecovery=false;w.failRecovery=false;w.nextRecoverySnapshot=null;w.deferRecoveryRead=false;
  w.recoveryState={computerName:'Fixture PC',desired:true,supervisor:{phase:absent?'stopped':'running',updatedAt:absent?0:now,failures:0},
   relay:{state:'waiting',host:''},devices:[{id:'fixture-phone',name:'已配对手机'}],processes:[],version:'0.6.3',startup:true,
   connection:{phase:absent?'failed':'ready',helperUpdatedAt:absent?0:now,connected:false,available:outdated,usableRoutes:outdated?1:0},
   installation:{consistent:true,installedVersion:'0.6.3',installedGeneration:target,restartRequired:outdated,
    ui:{state:'current',generation:target,runningVerified:true},
    supervisor:absent?{state:'unverified',runningVerified:false}:{state:outdated?'restart_required':'current',generation:outdated?old:target,runningVerified:true},
    helper:absent?{state:'unverified',runningVerified:false}:{state:outdated?'restart_required':'current',generation:outdated?old:target,runningVerified:true}},issues:[]};
  if(['waiting','starting','exhausted','recovered'].includes(scenario))w.recoveryState.backgroundRecovery={
   phase:scenario,attempts:scenario==='waiting'?0:scenario==='recovered'?2:3,maxAttempts:3,
   ...(['waiting','starting'].includes(scenario)?{nextAttemptAt:now+(scenario==='waiting'?5:30)}:{}),
  };
  if(['waiting_restart','verifying'].includes(scenario))w.recoveryState.installation.upgrade={phase:scenario,failure:scenario==='waiting_restart'?'handoff_unsupported':null,
   operationId:'fixture-journal',updatedAt:now,fromGeneration:old,toGeneration:target};
  w.__TAURI_INTERNALS__={invoke:async(cmd:string,args:any)=>{
   w.recoveryCalls.push({cmd,args});
   if(cmd==='dashboard'){
    if(w.nextRecoverySnapshot){w.recoveryState=w.nextRecoverySnapshot;w.nextRecoverySnapshot=null;}
    const state=w.recoveryState;
    const snapshot={...state,supervisor:{...state.supervisor,updatedAt:state.installation?.supervisor?.runningVerified?Date.now()/1000:state.supervisor.updatedAt},
     connection:{...state.connection,helperUpdatedAt:state.installation?.helper?.runningVerified?Date.now()/1000:state.connection.helperUpdatedAt}};
    if(w.deferRecoveryRead){w.deferRecoveryRead=false;await new Promise<void>(resolve=>{w.finishRecoveryRead=resolve});}
    return snapshot;
   }
   if(cmd==='set_remote'||cmd==='request_upgrade'){
    if(w.deferRecovery)await new Promise<void>(resolve=>{w.finishRecovery=resolve});
    if(w.failRecovery)throw {code:'start_failed',stage:'supervisor_start',message:'本次后台启动请求未完成',operationId:'fixture-failure',osCode:5,retryable:true};
    if(cmd==='set_remote'){
     w.recoveryState.desired=args.enabled;
     if(args.enabled)w.recoveryState.backgroundRecovery={phase:'starting',attempts:0,maxAttempts:3,nextAttemptAt:Date.now()/1000+30};
     else w.recoveryState.connection.phase='stopped';
    }else w.recoveryState.installation.upgrade={phase:'verifying',operationId:'fixture-request',updatedAt:Date.now()/1000,fromGeneration:old,toGeneration:target};
    return;
   }
   if(cmd==='export_diagnostics')return 'fixture-diagnostics.json';
   throw Error('Unexpected fixture command: '+cmd);
  }};
 },scenario);
 await page.goto('/',{waitUntil:'domcontentloaded'});
 await expect(page.getByRole('region',{name:'更新与后台恢复'})).toHaveCount(scenario==='current'?0:1);
}
const panel=(page:Page)=>page.getByRole('region',{name:'更新与后台恢复'});
const refresh=async(page:Page)=>{await page.getByRole('button',{name:'刷新连接状态',exact:true}).click()};
const mutations=(page:Page)=>page.evaluate(()=>(window as any).recoveryCalls.filter((call:any)=>call.cmd!=='dashboard'));

test('old background is explained on the home page and raw identity stays folded',async({page},info)=>{
 await fixture(page);
 await expect(panel(page).getByRole('heading',{name:'已安装新版，旧后台仍在运行'})).toBeVisible();
 await expect(page.getByRole('heading',{name:'电脑已就绪，连接手机。'})).toHaveCount(0);
 await expect(page.getByRole('button',{name:'开启连接',exact:true})).toBeDisabled();
 await expect(page.getByRole('button',{name:'添加设备',exact:true})).toBeDisabled();
 await expect(page.locator('.connection-facts').getByText('运行中',{exact:true})).toBeVisible();
 await expect(panel(page).getByText('aaaaaaaaaaaa',{exact:true})).not.toBeVisible();
 await panel(page).getByText('查看版本详情',{exact:true}).click();
 await expect(panel(page).getByText('aaaaaaaaaaaa',{exact:true})).toBeVisible();
 await expect(panel(page).getByText('bbbbbbbbbbbb',{exact:true})).toHaveCount(2);
 await panel(page).getByText('查看版本详情',{exact:true}).click();
 await page.screenshot({path:info.outputPath('home-recovery-light.png'),fullPage:true,animations:'disabled'});
 expect(await mutations(page)).toEqual([]);
});

test('upgrade request is single flight across navigation and remains unverified',async({page})=>{
 await fixture(page);await page.evaluate(()=>{(window as any).deferRecovery=true});
 await panel(page).getByRole('button',{name:'检查并更新后台',exact:true}).click();
 await expect(panel(page).getByRole('button',{name:'检查并更新后台',exact:true})).toBeDisabled();
 await page.getByRole('navigation').getByRole('button',{name:'设备',exact:true}).click();
 await expect(page.getByRole('button',{name:'添加设备',exact:true})).toBeDisabled();
 await page.getByRole('navigation').getByRole('button',{name:'连接',exact:true}).click();
 await panel(page).getByRole('button',{name:'刷新恢复状态',exact:true}).click();
 expect((await mutations(page)).map((call:any)=>call.cmd)).toEqual(['request_upgrade']);
 await page.evaluate(()=>{(window as any).finishRecovery()});
 await expect(panel(page).getByRole('heading',{name:'正在核验新后台'})).toBeVisible();
 await expect(page.getByText('新版后台已核验',{exact:true})).toHaveCount(0);
});

test('unsupported legacy backend keeps the Windows login guidance and read-only diagnostics',async({page})=>{
 await fixture(page,'waiting_restart');
 await page.evaluate(()=>{const s=(window as any).recoveryState;s.backgroundRecovery={phase:'blocked',attempts:0,maxAttempts:3,reason:'upgrade_pending'};
  s.issues=[{stage:'helper_control',code:'control_rejected',message:'旧助手尚未支持本次请求',operationId:'fixture-read',exitCode:5,retryable:false}];});
 await refresh(page);
 await expect(panel(page)).toContainText('当前旧后台不支持安全交接');
 await expect(panel(page)).toContainText('退出并重新登录 Windows');
 await expect(panel(page).getByRole('button',{name:'重试启动后台',exact:true})).toHaveCount(0);
 await panel(page).getByRole('button',{name:'检查详情',exact:true}).click();
 await expect(page.getByRole('heading',{name:'连接诊断',exact:true})).toBeVisible();
 await expect(page.getByRole('button',{name:'检查安全交接',exact:true})).toHaveCount(0);
 expect(await mutations(page)).toEqual([]);
});

test('initial countdown and zero remain native-owned without frontend launch calls',async({page})=>{
 await page.clock.install({time:new Date('2026-11-11T06:00:00Z')});
 await page.clock.pauseAt(new Date('2026-11-11T06:00:00Z'));
 await fixture(page,'waiting');
 await expect(panel(page)).toContainText('自动重试 0 / 3');await expect(panel(page)).toContainText('5 秒后');
 await page.clock.runFor(2000);await expect(panel(page)).toContainText('3 秒后');
 await page.clock.runFor(3000);await expect(panel(page)).toContainText('正在等待后台更新重试状态');
 await expect(panel(page).getByRole('button',{name:'重试启动后台',exact:true})).toHaveCount(0);
 expect(await mutations(page)).toEqual([]);
});

test('ten and thirty second retries end in verification until native exhausts the round',async({page})=>{
 await page.clock.install({time:new Date('2026-11-11T06:00:00Z')});await page.clock.pauseAt(new Date('2026-11-11T06:00:00Z'));
 await fixture(page,'waiting');
 for(const [attempts,delay] of [[1,10],[2,30]]){
  await page.evaluate(({attempts,delay})=>{(window as any).recoveryState.backgroundRecovery={phase:'waiting',attempts,maxAttempts:3,nextAttemptAt:Date.now()/1000+delay}},{attempts,delay});
  await refresh(page);await expect(panel(page)).toContainText(`自动重试 ${attempts} / 3`);await expect(panel(page)).toContainText(`${delay} 秒后`);
 }
 await page.evaluate(()=>{(window as any).recoveryState.backgroundRecovery={phase:'starting',attempts:3,maxAttempts:3,nextAttemptAt:Date.now()/1000+30}});
 await refresh(page);await expect(panel(page)).toContainText('核验中，最多再等待 30 秒');
 await page.clock.runFor(30000);await expect(panel(page)).toContainText('正在等待后台更新重试状态');
 await expect(panel(page).getByRole('button',{name:'重试启动后台',exact:true})).toHaveCount(0);
 await page.evaluate(()=>{(window as any).recoveryState.backgroundRecovery={phase:'exhausted',attempts:3,maxAttempts:3,reason:'background_unavailable'}});
 await refresh(page);await expect(panel(page).getByRole('button',{name:'重试启动后台',exact:true})).toBeEnabled();
 expect(await mutations(page)).toEqual([]);
});

test('manual retry resets the round once and verifies for thirty seconds before automatic attempts',async({page})=>{
 await fixture(page,'exhausted');await page.evaluate(()=>{(window as any).deferRecovery=true});
 await panel(page).getByRole('button',{name:'重试启动后台',exact:true}).click();
 await expect(panel(page).getByRole('button',{name:'重试启动后台',exact:true})).toBeDisabled();
 await page.evaluate(()=>{const button=Array.from(document.querySelectorAll('button')).find(button=>button.textContent==='重试启动后台');button?.click();button?.click()});
 await panel(page).getByRole('button',{name:'刷新恢复状态',exact:true}).click();
 expect(await mutations(page)).toEqual([{cmd:'set_remote',args:{enabled:true}}]);
 await page.evaluate(()=>{(window as any).finishRecovery()});
 await expect(panel(page).getByRole('heading',{name:'正在启动并核验后台'})).toBeVisible();
 await expect(panel(page)).toContainText('自动重试 0 / 3');await expect(panel(page)).toContainText(/最多再等待 (29|30) 秒/);
 await expect(page.getByText('后台已恢复并核验',{exact:true})).toHaveCount(0);
});

test('manual retry rechecks the current snapshot and cannot undo a pause',async({page})=>{
 await fixture(page,'exhausted');
 await page.evaluate(()=>{const w=window as any;w.nextRecoverySnapshot={...w.recoveryState,desired:false,connection:{...w.recoveryState.connection,phase:'stopped'}}});
 await panel(page).getByRole('button',{name:'重试启动后台',exact:true}).click();
 await expect(page.getByRole('alert')).toContainText('后台状态已变化');
 await expect(panel(page)).toHaveCount(0);expect(await mutations(page)).toEqual([]);
});

test('a read already in flight cannot authorize retry past a newer pause',async({page})=>{
 await fixture(page,'exhausted');await page.evaluate(()=>{(window as any).deferRecoveryRead=true});
 await refresh(page);await page.waitForFunction(()=>(window as any).finishRecoveryRead);
 await page.evaluate(()=>{const w=window as any;w.nextRecoverySnapshot={...w.recoveryState,desired:false,connection:{...w.recoveryState.connection,phase:'stopped'}}});
 await panel(page).getByRole('button',{name:'重试启动后台',exact:true}).click();
 await page.evaluate(()=>{(window as any).finishRecoveryRead()});
 await expect(page.getByRole('alert')).toContainText('后台状态已变化');expect(await mutations(page)).toEqual([]);
});

test('manual failure keeps its fixed stage and OS code without claiming readiness',async({page})=>{
 await fixture(page,'exhausted');await page.evaluate(()=>{(window as any).failRecovery=true});
 await panel(page).getByRole('button',{name:'重试启动后台',exact:true}).click();
 await expect(page.getByRole('alert')).toContainText('supervisor_start · start_failed · OS 5');
 await expect(panel(page).getByRole('button',{name:'重试启动后台',exact:true})).toBeEnabled();
 await expect(panel(page).getByRole('heading',{name:'后台已恢复并核验'})).toHaveCount(0);
 expect(await mutations(page)).toEqual([{cmd:'set_remote',args:{enabled:true}}]);
});

test('missing optional native field preserves the manual startup path',async({page})=>{
 await fixture(page,'missing');await panel(page).getByRole('button',{name:'重试启动后台',exact:true}).click();
 await expect(panel(page)).toContainText('自动重试 0 / 3');
 expect(await mutations(page)).toEqual([{cmd:'set_remote',args:{enabled:true}}]);
});

test('fresh current startup makes an old waiting-restart journal historical after login',async({page})=>{
 await fixture(page,'waiting_restart');
 await page.evaluate(()=>{const s=(window as any).recoveryState;
  for(const kind of ['supervisor','helper'])s.installation[kind]={state:'current',generation:'a'.repeat(64),runningVerified:true};
  s.installation.restartRequired=false;s.backgroundRecovery={phase:'recovered',attempts:1,maxAttempts:3};s.connection.available=false;s.connection.usableRoutes=0;});
 await refresh(page);await expect(panel(page).getByRole('heading',{name:'后台已恢复并核验'})).toBeVisible();
 await expect(panel(page)).not.toContainText('重新登录 Windows');await expect(panel(page)).toContainText('连接线路仍需单独核验');
 await panel(page).getByRole('button',{name:'检查详情',exact:true}).click();
 await expect(page.getByRole('region',{name:'安装与运行版本'})).toContainText('上次后台更新记录');
 await expect(page.getByRole('region',{name:'安装与运行版本'})).not.toContainText('重新登录 Windows');
 expect(await mutations(page)).toEqual([]);
});

test('stale legacy stamps allow native recovery without another login instruction',async({page})=>{
 await fixture(page,'waiting_restart');
 await page.evaluate(()=>{const s=(window as any).recoveryState;
  for(const kind of ['supervisor','helper'])s.installation[kind].runningVerified=false;
  s.supervisor.updatedAt=Date.now()/1000-30;s.supervisor.phase='stopped';s.connection.helperUpdatedAt=Date.now()/1000-30;
  s.backgroundRecovery={phase:'waiting',attempts:0,maxAttempts:3,nextAttemptAt:Date.now()/1000+5};});
 await refresh(page);await expect(panel(page).getByRole('heading',{name:'后台未就绪，正在自动重试'})).toBeVisible();
 await expect(panel(page)).not.toContainText('重新登录 Windows');expect(await mutations(page)).toEqual([]);
});

test('a current verified helper never repeats legacy login instructions for a stale supervisor stamp',async({page})=>{
 await fixture(page,'waiting_restart');await page.evaluate(()=>{const s=(window as any).recoveryState;
  s.installation.supervisor.runningVerified=false;s.supervisor.updatedAt=Date.now()/1000-30;s.supervisor.phase='stopped';
  s.installation.helper={state:'current',generation:'a'.repeat(64),runningVerified:true};s.backgroundRecovery={phase:'recovered',attempts:0,maxAttempts:3};});
 await refresh(page);await expect(panel(page)).not.toContainText('重新登录 Windows');
 await expect(panel(page).getByRole('button',{name:'重试启动后台',exact:true})).toHaveCount(0);expect(await mutations(page)).toEqual([]);
});

test('healthy backend without a network never triggers a startup or offers manual restart',async({page})=>{
 await fixture(page,'current');await refresh(page);
 await expect(panel(page)).toHaveCount(0);await expect(page.locator('.connection-facts').getByText('运行中',{exact:true})).toBeVisible();
 expect(await mutations(page)).toEqual([]);
});

test('historical tray and helper failures keep OS codes on home and diagnostics after recovery',async({page})=>{
 await fixture(page,'recovered');
 await page.evaluate(()=>{const s=(window as any).recoveryState,record={schema:1,operationId:'fixture-history',supervisorPid:42,time:Date.now()-60000,retryable:true};
  s.supervisor.trayStartupFailure={...record,phase:'tray_spawn',code:'tray_spawn_failed',osCode:5};
  s.supervisor.helperSpawnFailure={...record,phase:'helper_spawn',code:'helper_spawn_failed',osCode:2};});
 await refresh(page);const history=page.getByRole('region',{name:'最近后台启动记录'});
 await expect(panel(page).getByRole('heading',{name:'后台已恢复并核验'})).toBeVisible();
 await expect(history).toContainText('托盘窗口启动失败 · OS 5');await expect(history).toContainText('电脑助手启动失败 · OS 2');
 await expect(history).toContainText('记录于');await expect(page.getByRole('alert')).toHaveCount(0);
 await panel(page).getByRole('button',{name:'检查详情',exact:true}).click();
 await expect(history).toContainText('OS 5');await expect(history).toContainText('OS 2');expect(await mutations(page)).toEqual([]);
});

test('explicit pause stops recovery presentation and the page never re-enables remote access',async({page})=>{
 await fixture(page,'waiting');await page.getByRole('button',{name:'暂停连接',exact:true}).click();
 await page.getByRole('dialog').getByRole('button',{name:'暂停连接',exact:true}).click();
 await expect(panel(page)).toHaveCount(0);await expect(page.getByRole('heading',{name:'连接，随时开始。'})).toBeVisible();
 await refresh(page);expect(await mutations(page)).toEqual([{cmd:'set_remote',args:{enabled:false}}]);
});

test('recovery stays readable in dark and narrow layouts',async({page},info)=>{
 await fixture(page,'exhausted');await page.getByRole('button',{name:'切换明暗主题'}).click();
 await expect(page.locator('html')).toHaveAttribute('data-theme','dark');
 await page.screenshot({path:info.outputPath('home-recovery-dark.png'),fullPage:true,animations:'disabled'});
 await page.setViewportSize({width:620,height:850});
 await expect(panel(page).getByRole('button',{name:'重试启动后台',exact:true})).toBeVisible();
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
 await page.screenshot({path:info.outputPath('home-recovery-narrow.png'),fullPage:true,animations:'disabled'});
});
