import {describe,it,expect} from 'vitest';
import {renderToStaticMarkup} from 'react-dom/server';
import {RuntimeReadinessPanel,UpdatesPanel,assistantPreparationMessage} from './ManagementPanels';
describe('native management evidence',()=>{
 it('offers restart for the exact downloaded operation even when automatic updates are off',()=>{
  const html=renderToStaticMarkup(<UpdatesPanel status={{automatic:false,operationId:'b85e78a5-4874-4217-9b56-cf77315a6f36',phase:'waiting_idle',version:'1.2.3',downloadedBytes:1024,totalBytes:1024}} busy={false} onCheck={()=>{}} onAutomatic={()=>{}}/>);
  expect(html).toContain('重启并更新');
  expect(html).toContain('已下载的更新');
 });
 it('allows retry of the same interrupted coordinator while switching',()=>{
  const html=renderToStaticMarkup(<UpdatesPanel status={{automatic:false,restartRequested:true,operationId:'b85e78a5-4874-4217-9b56-cf77315a6f36',phase:'switching',version:'1.2.3',downloadedBytes:1024,totalBytes:1024,coordinator:{phase:'waiting_login'}}} busy={false} onCheck={()=>{}} onAutomatic={()=>{}}/>);
  expect(html).toContain('class="button primary"><');
  expect(html).toContain('重启并更新');
  expect(html).not.toContain('已安排重启更新');
 });
 it('uses human preparation guidance without leaking the internal error code',()=>{
  const message=assistantPreparationMessage({error:'assistant_runtime_start_failed'});
  expect(message).toContain('重新准备');expect(message).not.toContain('assistant_');
 });
 it('does not label a transport-only desktop ready and distinguishes auxiliary roles',()=>{
  const html=renderToStaticMarkup(<RuntimeReadinessPanel readiness={{authoritative:true,ready:false,updatedAt:100,runtimes:[{id:'d',role:'desktop',online:true,initialized:false,schemaReady:true,inventoryReady:false,ready:false},{id:'a',role:'auxiliary',online:true,initialized:true,schemaReady:true,inventoryReady:true,ready:true}]}} now={100}/>);
  expect(html).toContain('原生初始化');expect(html).toContain('辅助运行时');expect(html).toContain('等待初始化');expect(html).not.toContain('桌面已就绪');
 });
 it('expires runtime evidence independently of a previous ready flag',()=>{
  const html=renderToStaticMarkup(<RuntimeReadinessPanel readiness={{authoritative:true,ready:true,updatedAt:100,runtimes:[]}} now={111}/>);
  expect(html).toContain('状态等待核验');expect(html).not.toContain('桌面已就绪');
 });
 it('shows the official desktop independently of Pocket control',()=>{
  const html=renderToStaticMarkup(<RuntimeReadinessPanel readiness={{authoritative:true,ready:false,updatedAt:100,installed:true,historyReadable:true,nativeObservation:{desktop:{state:'detected',version:'26.1111.2228.0'},cli:{state:'detected'}},runtimes:[]}} now={100}/>);
  expect(html).toContain('官方桌面正在运行');expect(html).toContain('26.1111.2228.0');expect(html).toContain('CLI 正在运行');expect(html).not.toContain('实时控制已就绪');
 });
 it('shows a fresh Windows process observation even if the broker is offline or legacy',()=>{
  const readiness={authoritative:false,ready:false,updatedAt:0,localDesktop:{observedAt:100,version:'26.1111.2228.0'},runtimes:[]};
  const render=(now:number)=>renderToStaticMarkup(<RuntimeReadinessPanel readiness={readiness} now={now}/>);
  expect(render(100)).toContain('官方桌面正在运行');
  expect(render(100)).toContain('实时控制未核验');
  expect(render(100)).not.toContain('实时控制已就绪');
  expect(render(100)).not.toContain('可在手机浏览');
  expect(render(111)).not.toContain('官方桌面正在运行');
 });
 it('distinguishes a blocked installer from an active task wait',()=>{
  const html=renderToStaticMarkup(<UpdatesPanel status={{automatic:false,phase:'waiting_idle',version:'1.2.3',downloadedBytes:1,totalBytes:1,coordinator:{phase:'waiting',reason:'runtime_activity_unverified'}}} runningVersion="1.2.2" busy={false} onCheck={()=>{}} onAutomatic={()=>{}}/>);
  expect(html).toContain('等待核对旧后台');expect(html).toContain('1.2.2');expect(html).not.toContain('等待任务空闲');
 });
 it('shows that restoring the previous program still requires a working helper handshake',()=>{
  const html=renderToStaticMarkup(<UpdatesPanel status={{automatic:true,phase:'cold_starting',version:'1.2.3',downloadedBytes:1,totalBytes:1,coordinator:{phase:'verifying_rollback',reason:'previous_helper_not_verified'}}} busy={false} onCheck={()=>{}} onAutomatic={()=>{}}/>);
  expect(html).toContain('正在核验旧后台恢复');expect(html).not.toContain('已恢复之前的后台');expect(html).not.toContain('正在核验新版后台启动');
 });
 it('shows the queued upgrade after live handoff requests a cold start',()=>{
  const html=renderToStaticMarkup(<UpdatesPanel status={{automatic:true,phase:'waiting_cold_start',version:'1.2.3',downloadedBytes:1,totalBytes:1}} runningVersion="1.2.2" busy={false} onCheck={()=>{}} onAutomatic={()=>{}}/>);
  expect(html).toContain('等待后台切换');expect(html).not.toMatch(/注销|重新登录/);expect(html).not.toContain('已恢复之前的后台');
 });
 it('offers retry in the window after coordinator dispatch fails',()=>{
  const html=renderToStaticMarkup(<UpdatesPanel status={{automatic:false,phase:'staged',version:'1.2.3',downloadedBytes:1,totalBytes:1,coordinator:{phase:'waiting_login',reason:'independent_dispatch_failed'}}} busy={false} onCheck={()=>{}} onAutomatic={()=>{}}/>);
  expect(html).toContain('点击“重启并更新”重试');expect(html).not.toContain('重新登录');
 });
 it('renders actual staged progress and waiting policy without claiming installation',()=>{
  const html=renderToStaticMarkup(<UpdatesPanel status={{automatic:true,phase:'waiting_idle',version:'1.2.3',downloadedBytes:1024,totalBytes:1024}} busy={false} onCheck={()=>{}} onAutomatic={()=>{}}/>);
  expect(html).toContain('等待任务空闲');expect(html).toContain('1.2.3');expect(html).toContain('自动下载与更新');expect(html).not.toContain('更新已完成');
 });
});
