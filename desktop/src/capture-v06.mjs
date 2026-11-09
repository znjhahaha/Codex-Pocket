// Local browser verification only. No native application or real credentials.
import {preview} from 'vite';
import {chromium} from '@playwright/test';
import {mkdir} from 'node:fs/promises';
import assert from 'node:assert/strict';
const server=await preview({preview:{host:'127.0.0.1',port:1421,strictPort:true}});
let browser;
try {
 browser=await chromium.launch({channel:'msedge',headless:true});
 const page=await browser.newPage({viewport:{width:1120,height:1000}});
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{
  const state={computerName:'Studio PC',desired:false,supervisor:{phase:'stopped',updatedAt:Date.now()/1000,failures:0},relay:{state:'stopped',host:''},devices:[],processes:[{name:'desktop',state:'离线',bridgeVersion:'0.5.4',helperVersion:'0.6.0',bridgeUpdatePending:true}],version:'0.6.0',startup:true,installation:{consistent:true},routes:{routes:[],routePreferences:{routes:[],policy:'automatic',gateway:{bind:'127.0.0.1:17892'}}},connectivity:{mode:'off',phase:'stopped',enabled:false},sessionPreferences:{defaultMode:'desktop',revision:0}};
  window.fixture=state;window.calls=[];
  window.__TAURI_INTERNALS__={invoke:async(cmd,args)=>{
   window.calls.push({cmd,args});
   if(cmd==='dashboard')return {...state,supervisor:{...state.supervisor,updatedAt:Date.now()/1000},connection:{phase:state.desired?'connected':'stopped',connected:state.desired,helperUpdatedAt:Date.now()/1000}};
   if(cmd==='detect_lan')return [{name:'Wi-Fi',address:'192.168.1.20'}];
   if(cmd==='configure_routes'){state.routes.routePreferences=args.preferences;state.routes.routes=[{id:'lan',kind:'lan',url:'tls://192.168.1.20:17891',priority:0}];return state.routes;}
   if(cmd==='set_remote'){state.desired=args.enabled;state.relay.state=args.enabled?'connected':'stopped';state.supervisor.phase=args.enabled?'running':'stopped';return;}
   if(cmd==='session_preferences_update'){state.sessionPreferences={defaultMode:args.mode,revision:state.sessionPreferences.revision+1};return state.sessionPreferences;}
   if(cmd==='pair_device')return {payload:'SANITIZED-UI-FIXTURE-NOT-A-PAIRING-CREDENTIAL',expiresAt:Date.now()/1000+300,baselineIds:[]};
   if(cmd==='set_startup'){state.startup=args.enabled;return;}
   throw Error('Unmocked command: '+cmd);
  }};
 });
 await page.goto('http://127.0.0.1:1421',{waitUntil:'domcontentloaded'});
 console.log('Browser page loaded');
 await page.getByRole('heading',{name:'把电脑上的工作，带到手机。'}).waitFor();
 await page.getByRole('button',{name:'保存线路',exact:true}).waitFor();
 await page.waitForFunction(()=>document.querySelector('input[placeholder="192.168.1.10:17891"]')?.value==='192.168.1.20:17891');
 await mkdir('../docs/images',{recursive:true});
 await page.screenshot({path:'../docs/images/v06-first-run.png',fullPage:true,animations:'disabled'});
 await page.getByRole('button',{name:'保存线路',exact:true}).click();
 await page.waitForFunction(()=>window.fixture.routes.routePreferences.lan?.mdns===true);
 assert.equal(await page.getByRole('navigation').getByRole('button').count(),3);
 await page.getByRole('button',{name:'开启连接',exact:true}).click();
 await page.getByRole('heading',{name:'一切就绪，接着聊。'}).waitFor();
 await page.getByLabel('默认会话来源').selectOption('cli');
 await page.waitForFunction(()=>window.fixture.sessionPreferences.defaultMode==='cli');
 await page.getByLabel('默认会话来源').selectOption('desktop');
 await page.waitForFunction(()=>window.fixture.sessionPreferences.defaultMode==='desktop');
 await page.getByRole('button',{name:'连接新设备',exact:true}).click();
 await page.getByRole('dialog').waitFor();
 await page.screenshot({path:'../docs/images/v06-pairing-fixture.png',fullPage:false,animations:'disabled'});
 await page.getByRole('button',{name:'关闭配对窗口'}).click();
 await page.evaluate(()=>window.fixture.devices=[{id:'fixture-phone',name:'演示手机',createdAt:1780000000}]);
 await page.getByRole('button',{name:'刷新连接状态'}).click();
 await page.getByText('演示手机',{exact:true}).waitFor();
 await page.screenshot({path:'../docs/images/v06-connection.png',fullPage:true,animations:'disabled'});
 await page.getByRole('navigation').getByRole('button',{name:'设备',exact:true}).click();
 await page.getByRole('heading',{name:'你的设备'}).waitFor();
 await page.screenshot({path:'../docs/images/v06-devices.png',fullPage:true,animations:'disabled'});
 await page.getByRole('navigation').getByRole('button',{name:'设置',exact:true}).click();
 await page.getByRole('heading',{name:'偏好设置'}).waitFor();
 await page.screenshot({path:'../docs/images/v06-settings.png',fullPage:true,animations:'disabled'});
 await page.getByRole('button',{name:'暂停连接',exact:true}).click();
 await page.getByRole('dialog').getByRole('button',{name:'暂停连接',exact:true}).click();
 await page.waitForFunction(()=>window.fixture.desired===false);
 assert.deepEqual(errors,[]);
 console.log('PASS v0.6 browser: first-run LAN detection, mDNS, three tabs, explicit session modes, pairing, pause; five sanitized screenshots.');
} finally {await browser?.close();await new Promise(resolve=>server.httpServer.close(resolve));}
