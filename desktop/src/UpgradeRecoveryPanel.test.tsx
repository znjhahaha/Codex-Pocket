import {describe,expect,it} from 'vitest';
import {renderToStaticMarkup} from 'react-dom/server';
import type {Snapshot} from './domain';
import {UpgradeRecoveryPanel} from './UpgradeRecoveryPanel';

function snapshot(phase?:string,failure?:string):Snapshot {
 return {computerName:'Fixture',desired:true,supervisor:{phase:'running',updatedAt:100,failures:0},
  relay:{state:'ready',host:''},devices:[],processes:[],version:'0.6.3',startup:true,
  connection:{phase:'ready',connected:false,helperUpdatedAt:100},
  installation:{consistent:true,installedVersion:'0.6.3',installedGeneration:'a'.repeat(64),restartRequired:true,
   ui:{state:'current',runningVerified:true,generation:'a'.repeat(64)},
   supervisor:{state:'restart_required',runningVerified:true,version:'0.6.3',generation:'b'.repeat(64)},
   helper:{state:'restart_required',runningVerified:true,version:'0.6.3',generation:'b'.repeat(64)},
   ...(phase?{upgrade:{phase,failure,operationId:'fixture-operation',updatedAt:100,fromGeneration:'b'.repeat(64),toGeneration:'a'.repeat(64)}}:{})}};
}
const render=(s=snapshot(),busy=false,now=100)=>renderToStaticMarkup(<UpgradeRecoveryPanel snapshot={s} now={now} busy={busy} onAction={()=>{}} onRefresh={()=>{}} onDiagnostics={()=>{}}/>);

describe('home recovery presentation',()=>{
 it('shows installed files and both live backend generations before diagnostics',()=>{
  const html=render();
  expect(html).toContain('aria-label="更新与后台恢复"');
  expect(html).toContain('已安装新版，旧后台仍在运行');
  expect(html).toContain('已安装');expect(html).toContain('后台监督');expect(html).toContain('电脑助手');
  expect(html).toContain('aaaaaaaaaaaa');expect(html).toContain('bbbbbbbbbbbb');
  expect(html).toContain('版本号相同也可能属于不同安装代次');
  expect(html).toContain('检查并更新后台');
 });
 it('disables the primary action while App has an operation in flight',()=>{
  expect(render(snapshot(),true)).toMatch(/disabled=""[^>]*>检查并更新后台/);
 });
 it('explains the unsupported old backend and the Windows login boundary',()=>{
  const html=render(snapshot('waiting_restart','handoff_unsupported'));
  expect(html).toContain('当前旧后台不支持安全交接');
  expect(html).toContain('保存并结束当前 Codex 任务');
  expect(html).toContain('退出并重新登录 Windows');
  expect(html).toContain('仅重开 Codex 不保证更新常驻后台');
  expect(html).not.toContain('检查并更新后台');expect(html).not.toContain('恢复后台</button>');
 });
 it('does not call a capability read failure unsupported',()=>{
  const html=render(snapshot('waiting_restart','capability_failed'));
  expect(html).toContain('未能核验后台的安全交接能力');
  expect(html).not.toContain('当前旧后台不支持安全交接');
 });
 it('labels a dispatch retry as an attempt without claiming recovery',()=>{
  const html=render(snapshot('failed','dispatch_failed'));
  expect(html).toContain('重新尝试更新');
  expect(html).toContain('后台更新尚未完成');
  expect(html).not.toContain('新版后台已核验');
 });
 it('shows progress and read-only controls during handoff',()=>{
  const html=render(snapshot('verifying'));
  expect(html).toContain('正在核验新后台');expect(html).toContain('刷新恢复状态');
  expect(html).toContain('检查详情');
  expect(html).not.toContain('检查并更新后台');expect(html).not.toContain('恢复后台</button>');
 });
 it('sends an old UI to the Start menu',()=>{
  const s=snapshot();s.installation!.ui={state:'restart_required',generation:'b'.repeat(64),runningVerified:true};
  const html=render(s);expect(html).toContain('从 Windows 开始菜单打开新版 Codex Pocket');
  expect(html).not.toContain('检查并更新后台');
 });
 it('shows bridge restart separately from verified backend success',()=>{
  const s=snapshot('succeeded');
  for(const kind of ['supervisor','helper'] as const)s.installation![kind]={state:'current',generation:'a'.repeat(64),runningVerified:true};
  s.processes=[{name:'desktop',state:'在线',bridgeUpdatePending:true}];
  const html=render(s);
  expect(html).toContain('新版后台已核验');
  expect(html).toContain('aria-label="Codex bridge 单独核验"');
  expect(html).toContain('正在运行的 Codex bridge 仍等待重启生效');
  expect(html).not.toContain('重新登录 Windows');
 });
 it('keeps missing bridge information unverified',()=>{
  expect(render(snapshot('succeeded'))).toContain('Codex bridge 的运行版本尚未核验');
 });
 it('does not render a hidden normal state or a paused connection as recovery',()=>{
  const s=snapshot();s.desired=false;s.installation!.restartRequired=false;
  for(const kind of ['supervisor','helper'] as const)s.installation![kind]={state:'current',generation:'a'.repeat(64),runningVerified:true};
  expect(render(s)).toBe('');
  s.desired=true;expect(render(s)).toBe('');
 });
 it('never renders successful styling for a future handoff state',()=>{
  const html=render(snapshot('future_phase'));
  expect(html).toContain('后台状态尚未核验');expect(html).not.toContain('recovery-success');
 });
 it('shows a startup request without claiming the helper is running',()=>{
  const s=snapshot();s.installation!.supervisor={state:'unverified',runningVerified:false};s.installation!.helper={state:'unverified',runningVerified:false};
  s.supervisor={phase:'stopped',updatedAt:0,failures:0};s.connection={phase:'starting',connected:false,helperUpdatedAt:0,desiredChangedAt:95};
  const html=render(s);expect(html).toContain('正在等待后台启动');
  expect(html).toContain('尚未取得运行证据');expect(html).not.toContain('恢复后台</button>');
 });
});

describe('automatic retry presentation',()=>{
 function missing(phase:string,attempts:number,nextAttemptAt?:number):Snapshot {
  const s=snapshot();s.supervisor={phase:'stopped',updatedAt:0,failures:0};
  s.connection={phase:'failed',connected:false,helperUpdatedAt:0};
  s.installation!.supervisor={state:'unverified',runningVerified:false};s.installation!.helper={state:'unverified',runningVerified:false};
  return {...s,backgroundRecovery:{phase,attempts,maxAttempts:3,nextAttemptAt}} as Snapshot;
 }
 it('shows attempts and a ticking countdown in seconds',()=>{
  const s=missing('waiting',1,110);
  expect(render(s)).toContain('自动重试 1 / 3');expect(render(s)).toContain('10 秒后');
  expect(render(s,false,104)).toContain('6 秒后');
 });
 it('waits for native confirmation after the countdown reaches zero',()=>{
  const html=render(missing('waiting',1,100));
  expect(html).toContain('正在等待后台更新重试状态');
  expect(html).not.toContain('0 秒后');expect(html).not.toContain('重试启动后台</button>');
 });
 it('shows final verification without claiming readiness or another attempt',()=>{
  const html=render(missing('starting',3,130));
  expect(html).toContain('核验中');expect(html).toContain('最多再等待 30 秒');expect(html).toContain('自动重试 3 / 3');
  expect(html).not.toContain('后台已恢复并核验');expect(html).not.toContain('重试启动后台</button>');
 });
 it('emphasizes the manual retry only after the native round is exhausted',()=>{
  const s=missing('exhausted',3),html=render(s);
  expect(html).toContain('自动重试已用尽');expect(html).toMatch(/class="button primary"[^>]*>重试启动后台/);
  expect(render(s,true)).toMatch(/disabled=""[^>]*>重试启动后台/);
 });
 it('offers the same clear manual action to older snapshots',()=>{
  const s=missing('idle',0);delete (s as Snapshot & {backgroundRecovery?:unknown}).backgroundRecovery;
  expect(render(s)).toContain('重试启动后台');
 });
 it('shows a previous optional tray failure alongside verified recovery',()=>{
  const s=snapshot();for(const kind of ['supervisor','helper'] as const)s.installation![kind]={state:'current',generation:'a'.repeat(64),runningVerified:true};
  s.backgroundRecovery={phase:'recovered',attempts:1,maxAttempts:3};
  s.supervisor.trayStartupFailure={schema:1,operationId:'tray-history',supervisorPid:42,time:1794376800000,phase:'tray_spawn',code:'tray_spawn_failed',osCode:5,retryable:true};
  const html=render(s);expect(html).toContain('后台已恢复并核验');expect(html).toContain('最近后台启动记录');expect(html).toContain('OS 5');
  expect(html).toContain('recovery-success');expect(html).not.toContain('重试启动后台</button>');
 });
});
