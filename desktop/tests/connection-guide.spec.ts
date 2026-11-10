import {test,expect,type Page} from '@playwright/test';
import {mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';

type FixtureOptions={noLan?:boolean;detectionFailure?:boolean;missingPrefs?:boolean;staleBind?:boolean};
async function fixture(page:Page,existing=false,localReady=false,options:FixtureOptions={}) {
 await page.addInitScript(({existing,localReady,options})=>{
  const w=window as any;
  w.guideCalls=[];w.guideReady=existing||localReady;w.guidePhase='stopped';w.deferSave=false;w.rejectRoute=false;w.rejectDeploy=false;w.readyIdsOverride=null;w.failNextRead=false;
  w.lanRows=options.noLan?[]:[{name:'Wi-Fi',address:'192.168.1.20'},{name:'以太网',address:'10.0.0.2'}];w.detectionFailure=options.detectionFailure;w.missingPrefs=options.missingPrefs;w.readIssue=null;
  const preferences:any={routes:existing?[{id:'existing',kind:'relay',url:'wss://existing.invalid/pocket',priority:30}]:[],policy:'automatic',gateway:{bind:'127.0.0.1:17892'},...(localReady||options.staleBind?{lan:{bind:options.staleBind?'192.168.0.50:43821':'192.168.1.20:43821',mdns:true}}:{})};
  let desired=existing||localReady,publicMethod='',runtime=existing;
  w.__TAURI_INTERNALS__={invoke:async(cmd:string,args:any)=>{
   w.guideCalls.push({cmd,args});
   if(cmd==='detect_lan'){if(w.detectionFailure)throw {code:'network_detection_failed',stage:'network',message:'局域网检测失败，可继续公网',operationId:'fixture',retryable:true};return w.lanRows;}
   const routes=()=>[...(preferences.lan?[{id:'lan',kind:'lan',url:'ws://'+preferences.lan.bind,priority:0}]:[]),...preferences.routes,...(publicMethod==='cloudflare'&&w.guidePhase==='tunnel_connected'?[{id:'cf',kind:'cloudflare',url:'wss://fixture.trycloudflare.com/pocket',priority:10}]:[])];
   if(cmd==='dashboard'){
    if(w.failNextRead){w.failNextRead=false;w.guideReady=false;w.readyIdsOverride=[];}
    const configured=routes(),readyIds=w.readyIdsOverride??(w.guideReady?configured.map((r:any)=>r.id):[]);
    return {computerName:'Fixture PC',desired,supervisor:{phase:desired?'running':'stopped',updatedAt:Date.now()/1000,failures:0},relay:{state:existing?'connected':'waiting',host:''},devices:existing?[{id:'old',name:'已有手机'}]:[],processes:runtime?[{name:'desktop',state:'在线'}]:[],version:'fixture',startup:false,installation:{consistent:true},issues:w.readIssue?[w.readIssue]:[],routes:{routes:configured,...(!w.missingPrefs?{routePreferences:preferences}:{})},connection:{phase:existing?'connected':desired?'connecting':'setup_required',connected:existing&&desired,available:desired&&readyIds.length>0,helperUpdatedAt:desired?Date.now()/1000:0,configuredRoutes:configured.length,usableRoutes:desired?readyIds.length:0,readyRouteIds:desired?readyIds:[]},connectivity:{phase:w.guidePhase,mode:publicMethod==='cloudflare'?'quick':'off',enabled:publicMethod==='cloudflare',...(w.guidePhase==='tunnel_connected'?{publicUrl:'https://fixture.trycloudflare.com'}:{})}};
   }
   if(cmd==='configure_routes'){if(w.deferSave)await new Promise<void>(resolve=>{w.finishSave=resolve});Object.assign(preferences,args.preferences);w.savedPreferences=args.preferences;return {routes:routes(),routePreferences:preferences};}
   if(cmd==='set_remote'){desired=args.enabled;return;}
   if(cmd==='configure_connectivity'){publicMethod='cloudflare';w.guidePhase='starting';return {phase:'starting',enabled:true};}
   if(cmd==='configure_relay'){if(w.rejectRoute)throw {code:'route_not_ready',stage:'routes',message:'fixture failure',operationId:'fixture',retryable:true};preferences.routes=preferences.routes.filter((r:any)=>r.id!=='manual');preferences.routes.push({id:'manual',kind:'relay',url:args.url,priority:30});return {routes:routes(),routePreferences:preferences};}
   if(cmd==='open_codex'){runtime=true;return;}
   if(cmd==='allow_lan_firewall')return {configured:true,bind:preferences.lan.bind};
   if(cmd==='pair_device')return {payload:'fixture-only-no-credentials',expiresAt:Date.now()/1000+300,baselineIds:existing?['old']:[]};
   if(cmd==='inspect_ssh')return {fingerprint:'SHA256:fixture-fingerprint'};
   if(cmd==='preflight_ssh')return {dockerInstalled:true,canElevate:true,portsFree:true,ownProject:false};
   if(cmd==='deploy_relay'){if(w.rejectDeploy)throw {code:'deployment_failed',stage:'ssh',message:'fixture failure',operationId:'fixture',retryable:true};return {operationId:'fixture-operation',phase:'deployed_pending_handshake'};}
   if(cmd==='activate_relay'){preferences.routes.push({id:'ssh',kind:'relay',url:'wss://ssh.invalid/pocket',priority:30});return {routes:routes(),routePreferences:preferences};}
   throw Error('Unexpected fixture command: '+cmd);
  }};
 },{existing,localReady,options});
 await page.goto('/',{waitUntil:'domcontentloaded'});
}
async function mutations(page:Page){return page.evaluate(()=>(window as any).guideCalls.filter((c:any)=>!['dashboard','detect_lan'].includes(c.cmd)))}
async function choose(page:Page,name:string){await page.getByRole('button',{name:'开启连接',exact:true}).first().click();await page.getByRole('button',{name,exact:true}).click()}

test('routes read failure retains its stage and retry never writes settings',async({page})=>{
 await fixture(page);await choose(page,'暂不配置公网');
 await page.evaluate(()=>{(window as any).missingPrefs=true;(window as any).readIssue={code:'control_rejected',stage:'routes_read',message:'后台拒绝读取线路，请检查运行代次。',operationId:'read-fixture',exitCode:5,retryable:true};});
 await page.getByRole('button',{name:'保存并启用自动连接',exact:true}).click();
 const guide=page.getByRole('region',{name:'连接引导'});
 await expect(guide.getByRole('alert')).toContainText('routes_read');
 await expect(guide.getByRole('alert')).toContainText('退出码 5');
 await expect(guide.getByRole('button',{name:'重试连接',exact:true})).toHaveCount(0);
 await guide.getByRole('button',{name:'重新检查',exact:true}).click();
 await expect(guide.getByRole('alert')).toContainText('read-fixture');
 expect(await mutations(page)).toEqual([]);
 await page.evaluate(()=>{(window as any).missingPrefs=false;(window as any).readIssue=null;});
 await guide.getByRole('button',{name:'重新检查',exact:true}).click();
 await expect(guide.getByRole('alert')).toHaveCount(0);
 expect(await mutations(page)).toEqual([]);
});

test('saved relay is shown and polling preserves a user edit',async({page})=>{
 await fixture(page,true);
 await page.getByRole('button',{name:'更换连接方式',exact:true}).click();
 await page.getByRole('button',{name:'自有服务器中转',exact:true}).click();
 const field=page.getByPlaceholder('wss://relay.example.com');
 await expect(field).toHaveValue('wss://existing.invalid/pocket');
 await field.fill('wss://replacement.invalid/pocket');
 await page.waitForTimeout(5200);
 await expect(field).toHaveValue('wss://replacement.invalid/pocket');
 await field.fill('');
 await page.waitForTimeout(5200);
 await expect(field).toHaveValue('');
 expect(await mutations(page)).toEqual([]);
});

test('first connection and Add device show three choices without starting or pairing',async({page},info)=>{
 await fixture(page);
 await page.getByRole('button',{name:'添加设备',exact:true}).click();
 await expect(page.getByRole('heading',{name:'选择公网连接（可选）',exact:true})).toBeVisible();
 for(const name of ['Cloudflare 隧道','自有服务器中转','暂不配置公网'])await expect(page.getByRole('button',{name,exact:true})).toBeVisible();
 expect(await mutations(page)).toEqual([]);
 await page.screenshot({path:info.outputPath('three-choices.png'),fullPage:true});
 await page.getByRole('button',{name:'取消引导',exact:true}).click();
 expect(await mutations(page)).toEqual([]);
});

test('new user with auto-configured ready LAN still chooses a method and keeps its port',async({page})=>{
 await fixture(page,false,true);
 await choose(page,'暂不配置公网');
 await expect(page.getByText(/已自动匹配 192.168.1.20:43821/)).toBeVisible();
 await expect(page.getByRole('combobox',{name:'电脑网络'})).toHaveCount(0);
 await page.getByRole('button',{name:'高级网络调整',exact:true}).click();
 await expect(page.getByRole('combobox',{name:'电脑网络'})).toHaveValue('192.168.1.20:43821');
 await page.getByRole('combobox',{name:'电脑网络'}).selectOption('10.0.0.2:43821');
 await page.getByRole('button',{name:'保存并启用自动连接',exact:true}).click();
 expect((await mutations(page))[0].args.preferences.lan.bind).toBe('10.0.0.2:43821');
 expect((await mutations(page)).filter((c:any)=>c.cmd==='pair_device')).toHaveLength(0);
});

test('failed relay readiness can edit address, while SSH failure retains SSH retry steps',async({page})=>{
 await fixture(page);await choose(page,'自有服务器中转');
 const input=page.getByRole('textbox',{name:'中转地址',exact:true});
 await input.fill('wss://relay.invalid');
 await page.getByRole('button',{name:'保存并启用中转',exact:true}).click();
 await page.getByRole('button',{name:'编辑连接设置',exact:true}).click();
 await expect(input).toBeEditable();await input.fill('wss://other.invalid');
 await page.getByRole('button',{name:'通过 SSH 部署',exact:true}).click();
 await page.getByRole('textbox',{name:'服务器地址',exact:true}).fill('ssh.invalid');
 await page.getByRole('textbox',{name:'中转域名',exact:true}).fill('ssh.invalid');
 await page.getByRole('button',{name:'读取服务器指纹',exact:true}).click();
 await page.getByRole('checkbox',{name:'我已与服务器控制台核对指纹'}).check();
 await page.getByRole('button',{name:'检查服务器',exact:true}).click();
 await page.evaluate(()=>{(window as any).rejectDeploy=true});
 await page.getByRole('button',{name:'部署到此服务器',exact:true}).click();
 await expect(page.getByRole('alert')).toContainText('服务器部署未完成');
 await expect(page.getByRole('button',{name:'重试连接',exact:true})).toHaveCount(0);
 await expect(page.getByRole('button',{name:'部署到此服务器',exact:true})).toBeEnabled();
});

test('Cloudflare advanced configuration returns to the same guide without implicit mutation',async({page})=>{
 await fixture(page);await choose(page,'Cloudflare 隧道');
 await page.getByText('固定域名或账户 Token（可选）',{exact:true}).click();
 await page.getByRole('button',{name:'打开高级连接设置',exact:true}).click();
 await expect(page.getByRole('heading',{name:'高级连接设置',exact:true})).toBeVisible();
 await page.getByRole('button',{name:'返回连接引导',exact:true}).click();
 await expect(page.getByRole('heading',{name:'Cloudflare 隧道',exact:true})).toBeVisible();
 expect(await mutations(page)).toEqual([]);
});

test('public configuration prepares automatic LAN and replaces stale bind without a network choice',async({page})=>{
 await fixture(page,false,false,{staleBind:true});await choose(page,'Cloudflare 隧道');
 await expect(page.getByText(/已自动匹配 192.168.1.20:43821/)).toBeVisible();
 await expect(page.getByRole('combobox',{name:'电脑网络'})).toHaveCount(0);
 expect(await mutations(page)).toEqual([]);
 await page.getByRole('button',{name:'开启临时公网连接',exact:true}).click();
 await expect(page.getByText('正在等待 Cloudflare 隧道',{exact:true})).toBeVisible();
 const calls=await mutations(page);expect(calls.map((c:any)=>c.cmd)).toEqual(['configure_routes','set_remote','configure_connectivity']);
 expect(calls[0].args.preferences).toMatchObject({policy:'automatic',autoLan:true,lan:{bind:'192.168.1.20:43821',mdns:true}});
});

test('skip public preserves the configured relay and only enables automatic LAN',async({page})=>{
 await fixture(page,true);await page.getByRole('button',{name:'更换连接方式',exact:true}).click();
 await page.getByRole('button',{name:'暂不配置公网',exact:true}).click();
 await page.getByRole('button',{name:'保存并启用自动连接',exact:true}).click();
 await expect(page.getByText('局域网已就绪',{exact:true})).toBeVisible();
 const calls=await mutations(page);expect(calls.map((c:any)=>c.cmd)).toEqual(['configure_routes']);
 expect(calls[0].args.preferences.routes).toEqual([{id:'existing',kind:'relay',url:'wss://existing.invalid/pocket',priority:30}]);
 expect(calls[0].args.preferences).toMatchObject({policy:'automatic',autoLan:true});
});

test('zero networks leaves LAN unavailable while Cloudflare still starts with automatic discovery enabled',async({page})=>{
 await fixture(page,false,false,{noLan:true});await choose(page,'暂不配置公网');
 await expect(page.getByText(/局域网暂不可用/)).toBeVisible();
 await expect(page.getByRole('button',{name:'保存并启用自动连接',exact:true})).toBeDisabled();
 await expect(page.getByRole('button',{name:'下一步：打开 Codex',exact:true})).toBeDisabled();
 expect(await mutations(page)).toEqual([]);
 await page.getByRole('button',{name:'上一步',exact:true}).click();
 await page.getByRole('button',{name:'Cloudflare 隧道',exact:true}).click();
 await page.getByRole('button',{name:'开启临时公网连接',exact:true}).click();
 await expect(page.getByText('正在等待 Cloudflare 隧道',{exact:true})).toBeVisible();
 const calls=await mutations(page);expect(calls[0].args.preferences).toMatchObject({autoLan:true,lan:null,policy:'automatic'});
 expect(calls.map((c:any)=>c.cmd)).toEqual(['configure_routes','set_remote','configure_connectivity']);
});

test('network detection failure is local and cannot prevent relay setup',async({page})=>{
 await fixture(page,false,false,{detectionFailure:true});await choose(page,'自有服务器中转');
 await expect(page.getByText(/局域网检测未完成/)).toBeVisible();
 await expect(page.getByRole('alert')).toHaveCount(0);
 await page.getByRole('textbox',{name:'中转地址',exact:true}).fill('wss://relay.invalid');
 await page.getByRole('button',{name:'保存并启用中转',exact:true}).click();
 await expect(page.getByText('正在等待中转就绪',{exact:true})).toBeVisible();
 const calls=await mutations(page);expect(calls.map((c:any)=>c.cmd)).toEqual(['configure_routes','configure_relay','set_remote']);
 expect(calls[0].args.preferences).toMatchObject({lan:null,autoLan:true});
});

test('network recheck after offline detection only updates the candidate until explicit save',async({page})=>{
 await fixture(page,false,false,{noLan:true});await choose(page,'暂不配置公网');
 await expect(page.getByRole('button',{name:'保存并启用自动连接',exact:true})).toBeDisabled();
 await page.evaluate(()=>{(window as any).lanRows=[{name:'Wi-Fi',address:'10.0.0.20'}]});
 await page.getByRole('button',{name:'重新检测网络',exact:true}).click();
 await expect(page.getByText(/已自动匹配 10.0.0.20:43821/)).toBeVisible();
 expect(await mutations(page)).toEqual([]);
 await page.getByRole('button',{name:'保存并启用自动连接',exact:true}).click();
 await expect(page.getByText('正在等待局域网就绪',{exact:true})).toBeVisible();
 expect((await mutations(page))[0].args.preferences).toMatchObject({autoLan:true,lan:{bind:'10.0.0.20:43821',mdns:true}});
});

test('missing authoritative preferences cannot overwrite existing route configuration',async({page})=>{
 await fixture(page,false,false,{missingPrefs:true});await choose(page,'Cloudflare 隧道');
 await page.getByRole('button',{name:'开启临时公网连接',exact:true}).click();
 await expect(page.getByRole('alert')).toContainText('线路设置尚未读取，请重新检查');
 expect(await mutations(page)).toEqual([]);
 await page.evaluate(()=>{(window as any).missingPrefs=false});
 await page.getByRole('button',{name:'重新检查',exact:true}).click();
 await page.getByRole('button',{name:'开启临时公网连接',exact:true}).click();
 await expect(page.getByText('正在等待 Cloudflare 隧道',{exact:true})).toBeVisible();
});

test('failed public connection falls back to verified LAN and explains LAN-only pairing',async({page})=>{
 await fixture(page);await choose(page,'Cloudflare 隧道');
 await page.getByRole('button',{name:'开启临时公网连接',exact:true}).click();
 await page.evaluate(()=>{const w=window as any;w.guidePhase='failed';w.readyIdsOverride=['lan']});
 await expect(page.getByText('仅当前局域网可用',{exact:true})).toBeVisible();
 await expect(page.getByRole('alert')).toContainText('Cloudflare 隧道没有连接成功');
 await page.getByRole('button',{name:'下一步：仅局域网连接',exact:true}).click();
 await expect(page.getByText(/仅当前局域网可用。请保持手机/)).toBeVisible();
 await page.getByRole('button',{name:'打开 Codex 桌面',exact:true}).click();
 await page.getByRole('button',{name:'生成配对二维码',exact:true}).click();
 await expect(page.getByRole('dialog')).toContainText('后失效');
 expect((await mutations(page)).filter((c:any)=>c.cmd==='pair_device')).toHaveLength(1);
});

test('LAN saves the selected subnet, enables sequentially, offers explicit UAC, then Codex and QR',async({page},info)=>{
 await fixture(page);await choose(page,'暂不配置公网');
 await expect(page.getByText(/已自动匹配 192.168.1.20:43821/)).toBeVisible();
 await page.getByRole('button',{name:'高级网络调整',exact:true}).click();
 await page.getByRole('combobox',{name:'电脑网络'}).selectOption('10.0.0.2:43821');
 await page.getByRole('button',{name:'保存并启用自动连接',exact:true}).click();
 await expect(page.getByText('正在等待局域网就绪', {exact:true})).toBeVisible();
 const calls=await mutations(page);expect(calls.map((c:any)=>c.cmd)).toEqual(['configure_routes','set_remote']);
 expect(calls[0].args.preferences.lan).toEqual({bind:'10.0.0.2:43821',mdns:true});
 expect(calls[0].args.preferences.autoLan).toBe(false);
 await expect(page.getByText(/点击后 Windows 将请求管理员授权/)).toBeVisible();
 await page.getByRole('button',{name:'允许局域网通过防火墙',exact:true}).click();
 await expect(page.getByText(/已允许此局域网端口/)).toBeVisible();
 await page.evaluate(()=>{(window as any).guideReady=true});
 await expect(page.getByText('局域网已就绪',{exact:true})).toBeVisible();
 await expect(page.getByRole('heading',{name:'一切就绪，接着聊。'})).toHaveCount(0);
 await page.getByRole('button',{name:'下一步：打开 Codex',exact:true}).click();
 await page.getByRole('button',{name:'打开 Codex 桌面',exact:true}).click();
 await expect(page.getByText('Codex 已就绪',{exact:true})).toBeVisible();
 await page.getByRole('button',{name:'生成配对二维码',exact:true}).click();
 await expect(page.getByRole('dialog')).toContainText('后失效');
 await page.screenshot({path:info.outputPath('lan-qr.png'),fullPage:true});
 expect((await mutations(page)).map((c:any)=>c.cmd)).toEqual(['configure_routes','set_remote','allow_lan_firewall','open_codex','pair_device']);
});

test('Cloudflare waiting, failure, explicit retry, public URL and readiness remain distinct',async({page})=>{
 await fixture(page);await choose(page,'Cloudflare 隧道');
 expect(await mutations(page)).toEqual([]);
 await page.getByRole('button',{name:'开启临时公网连接',exact:true}).click();
 await expect(page.getByText('正在等待 Cloudflare 隧道',{exact:true})).toBeVisible();
 await expect(page.getByRole('button',{name:'下一步：打开 Codex',exact:true})).toBeDisabled();
 await page.evaluate(()=>{(window as any).guidePhase='failed'});
 await expect(page.getByRole('alert')).toContainText('Cloudflare 隧道没有连接成功');
 await page.getByRole('button',{name:'重试连接',exact:true}).click();
 await expect(page.getByText('正在等待 Cloudflare 隧道',{exact:true})).toBeVisible();
 await page.evaluate(()=>{(window as any).guidePhase='tunnel_connected'});
 await expect(page.getByText('https://fixture.trycloudflare.com',{exact:true})).toBeVisible();
 await expect(page.getByRole('button',{name:'下一步：打开 Codex',exact:true})).toBeDisabled();
 await page.evaluate(()=>{(window as any).guideReady=true});
 await expect(page.getByText('Cloudflare 隧道已就绪',{exact:true})).toBeVisible();
 await expect(page.getByRole('button',{name:'下一步：打开 Codex',exact:true})).toBeEnabled();
 expect((await mutations(page)).filter((c:any)=>c.cmd==='configure_connectivity')).toHaveLength(2);
 expect((await mutations(page)).filter((c:any)=>c.cmd==='pair_device')).toHaveLength(0);
});

test('manual relay validates a base URL and cancellation never stops or pairs',async({page})=>{
 await fixture(page);await choose(page,'自有服务器中转');
 await page.getByRole('textbox',{name:'中转地址',exact:true}).fill('wss://host/pocket?token=secret');
 await page.getByRole('button',{name:'保存并启用中转',exact:true}).click();
 await expect(page.getByRole('alert')).toContainText('不要包含房间、令牌');
 expect(await mutations(page)).toEqual([]);
 await page.getByRole('textbox',{name:'中转地址',exact:true}).fill('wss://relay.invalid/pocket');
 await page.getByRole('button',{name:'保存并启用中转',exact:true}).click();
 await expect(page.getByText('正在等待中转就绪',{exact:true})).toBeVisible();
 await page.getByRole('button',{name:'取消引导',exact:true}).click();
 const saved=await mutations(page);expect(saved.map((c:any)=>c.cmd)).toEqual(['configure_routes','configure_relay','set_remote']);expect(saved[1].args).toEqual({url:'wss://relay.invalid/pocket'});
 await expect(page.getByRole('heading',{name:'一切就绪，接着聊。'})).toHaveCount(0);
});

test('change method preserves an existing connection and its pairing entrypoint',async({page})=>{
 await fixture(page,true);
 await page.getByRole('button',{name:'更换连接方式',exact:true}).click();
 await page.getByRole('button',{name:'Cloudflare 隧道',exact:true}).click();
 await page.getByRole('button',{name:'上一步',exact:true}).click();
 await page.getByRole('button',{name:'取消引导',exact:true}).click();
 expect(await mutations(page)).toEqual([]);
 await expect(page.getByRole('heading',{name:'一切就绪，接着聊。'})).toBeVisible();
 await page.getByRole('button',{name:'连接新设备',exact:true}).click();
 await expect(page.getByRole('dialog')).toContainText('后失效');
 expect((await mutations(page)).map((c:any)=>c.cmd)).toEqual(['pair_device']);
});

test('only IDs for the selected route can unlock pairing, and the final snapshot is checked again',async({page})=>{
 await fixture(page);await choose(page,'自有服务器中转');
 await page.getByRole('textbox',{name:'中转地址',exact:true}).fill('wss://relay.invalid');
 await page.getByRole('button',{name:'保存并启用中转',exact:true}).click();
 await page.evaluate(()=>{const w=window as any;w.guideReady=true;w.readyIdsOverride=['other-lan']});
 await expect(page.getByRole('button',{name:'下一步：打开 Codex',exact:true})).toBeDisabled();
 await page.evaluate(()=>{(window as any).readyIdsOverride=['manual']});
 await expect(page.getByRole('button',{name:'下一步：打开 Codex',exact:true})).toBeEnabled();
 await page.getByRole('button',{name:'下一步：打开 Codex',exact:true}).click();
 await page.getByRole('button',{name:'打开 Codex 桌面',exact:true}).click();
 await expect(page.getByRole('button',{name:'生成配对二维码',exact:true})).toBeEnabled();
 await page.evaluate(()=>{(window as any).failNextRead=true});
 await page.getByRole('button',{name:'生成配对二维码',exact:true}).click();
 await expect(page.getByRole('alert')).toContainText('线路尚未就绪');
 expect((await mutations(page)).filter((c:any)=>c.cmd==='pair_device')).toHaveLength(0);
});

test('cancelled pending save has no duplicate request or follow-up startup',async({page})=>{
 await fixture(page);await choose(page,'暂不配置公网');
 await page.evaluate(()=>{(window as any).deferSave=true});
 await page.getByRole('button',{name:'保存并启用自动连接',exact:true}).click();
 await page.getByRole('button',{name:'取消引导',exact:true}).click();
 await expect(page.getByRole('button',{name:'开启连接',exact:true}).first()).toBeDisabled();
 await page.evaluate(()=>{(window as any).finishSave()});
 await expect(page.getByRole('button',{name:'开启连接',exact:true}).first()).toBeEnabled();
 expect((await mutations(page)).map((c:any)=>c.cmd)).toEqual(['configure_routes']);
});

test('navigating to Settings cancels the remaining wizard sequence',async({page})=>{
 await fixture(page);await choose(page,'暂不配置公网');
 await page.evaluate(()=>{(window as any).deferSave=true});
 await page.getByRole('button',{name:'保存并启用自动连接',exact:true}).click();
 await page.getByRole('navigation').getByRole('button',{name:'设置',exact:true}).click();
 await expect(page.getByRole('heading',{name:'偏好设置',exact:true})).toBeVisible();
 await expect(page.getByRole('region',{name:'连接引导'})).toHaveCount(0);
 await page.evaluate(()=>{(window as any).finishSave()});
 await expect(page.getByRole('switch',{name:'登录后自动运行'})).toBeEnabled();
 expect((await mutations(page)).map((c:any)=>c.cmd)).toEqual(['configure_routes']);
});

test('SSH requires explicit fingerprint trust and server check, clears password on exit',async({page})=>{
 await fixture(page);await choose(page,'自有服务器中转');
 await page.getByRole('button',{name:'通过 SSH 部署',exact:true}).click();
 await page.getByRole('textbox',{name:'服务器地址',exact:true}).fill('ssh.invalid');
 await page.getByLabel('SSH 密码',{exact:true}).fill('fixture-password');
 await page.getByRole('textbox',{name:'中转域名',exact:true}).fill('ssh.invalid');
 await page.getByRole('button',{name:'读取服务器指纹',exact:true}).click();
 await expect(page.getByRole('button',{name:'检查服务器',exact:true})).toBeDisabled();
 await page.getByRole('checkbox',{name:'我已与服务器控制台核对指纹'}).check();
 await page.getByRole('button',{name:'检查服务器',exact:true}).click();
 await page.getByRole('button',{name:'部署到此服务器',exact:true}).click();
 await page.getByRole('button',{name:'使用此中转',exact:true}).click();
 await expect(page.getByText('正在等待中转就绪',{exact:true})).toBeVisible();
 const cmds=(await mutations(page)).map((c:any)=>c.cmd);expect(cmds).toEqual(['inspect_ssh','preflight_ssh','deploy_relay','configure_routes','activate_relay','set_remote']);
 await page.getByRole('button',{name:'取消引导',exact:true}).click();
 await page.getByRole('button',{name:'开启连接',exact:true}).first().click();
 await page.getByRole('button',{name:'自有服务器中转',exact:true}).click();
 await page.getByRole('button',{name:'通过 SSH 部署',exact:true}).click();
 await expect(page.getByLabel('SSH 密码',{exact:true})).toHaveValue('');
});

test('capture real fixture UI in light, dark and narrow layouts',async({page})=>{
 const directory=resolve('../.local/v063-ui');await mkdir(directory,{recursive:true});
 await page.emulateMedia({colorScheme:'light',reducedMotion:'reduce'});
 await fixture(page);
 await page.getByRole('button',{name:'开启连接',exact:true}).first().click();
 await expect(page.getByRole('heading',{name:'选择公网连接（可选）',exact:true})).toBeVisible();
 await page.screenshot({path:resolve(directory,'auto-connection-choices-light.png'),fullPage:true,animations:'disabled'});
 await page.getByRole('button',{name:'暂不配置公网',exact:true}).click();
 await expect(page.getByText(/已自动匹配 192.168.1.20:43821/)).toBeVisible();
 await expect(page.getByRole('combobox',{name:'电脑网络'})).toHaveCount(0);
 await page.screenshot({path:resolve(directory,'auto-lan-config-light.png'),fullPage:true,animations:'disabled'});
 await page.getByRole('button',{name:'上一步',exact:true}).click();
 await page.getByRole('button',{name:'Cloudflare 隧道',exact:true}).click();
 await page.getByRole('button',{name:'开启临时公网连接',exact:true}).click();
 await expect(page.getByText('正在等待 Cloudflare 隧道',{exact:true})).toBeVisible();
 await page.screenshot({path:resolve(directory,'auto-cloudflare-waiting-light.png'),fullPage:true,animations:'disabled'});
 await page.getByRole('button',{name:'切换明暗主题'}).click();
 await expect(page.locator('html')).toHaveAttribute('data-theme','dark');
 await page.screenshot({path:resolve(directory,'auto-cloudflare-waiting-dark.png'),fullPage:true,animations:'disabled'});
 await page.getByRole('button',{name:'上一步',exact:true}).click();
 await page.setViewportSize({width:620,height:850});
 await expect(page.getByRole('button',{name:'自有服务器中转',exact:true})).toBeVisible();
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
 await page.screenshot({path:resolve(directory,'auto-connection-choices-narrow-dark.png'),fullPage:true,animations:'disabled'});
 await page.getByRole('button',{name:'暂不配置公网',exact:true}).click();
 await expect(page.getByRole('button',{name:'保存并启用自动连接',exact:true})).toBeEnabled();
 await page.screenshot({path:resolve(directory,'auto-lan-config-narrow-dark.png'),fullPage:true,animations:'disabled'});
});
