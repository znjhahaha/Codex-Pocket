import {describe,expect,it} from 'vitest';
import type {Snapshot} from './domain';
import {upgradeRecovery,recoveryBlocksMutations} from './upgradeRecovery';

const target='a'.repeat(64),source='b'.repeat(64);
function snapshot():Snapshot {
 return {computerName:'Fixture',desired:true,supervisor:{phase:'running',updatedAt:100,failures:0},
  relay:{state:'ready',host:''},devices:[],processes:[],version:'0.6.3',startup:true,
  connection:{phase:'ready',helperUpdatedAt:100,connected:false,available:true},
  installation:{consistent:true,installedVersion:'0.6.3',installedGeneration:target,
   ui:{state:'current',generation:target,runningVerified:true,version:'0.6.3'},
   supervisor:{state:'current',generation:target,runningVerified:true,version:'0.6.3'},
   helper:{state:'current',generation:target,runningVerified:true,version:'0.6.3'}}};
}
function oldBackend():Snapshot {
 const s=snapshot();
 for(const kind of ['supervisor','helper'] as const)s.installation![kind]={...s.installation![kind]!,generation:source,state:'restart_required'};
 s.installation!.restartRequired=true;
 return s;
}
function record(s:Snapshot,phase:string,failure?:string):Snapshot {
 s.installation!.upgrade={operationId:'fixture-operation',phase,failure,updatedAt:100,fromGeneration:source,toGeneration:target};
 return s;
}
function missingBackend():Snapshot {
 const s=snapshot();
 s.supervisor={phase:'stopped',updatedAt:0,failures:0};
 s.connection={phase:'failed',connected:false,available:false,helperUpdatedAt:0};
 s.installation!.supervisor={state:'unverified',runningVerified:false};
 s.installation!.helper={state:'unverified',runningVerified:false};
 return s;
}

describe('home upgrade and background recovery policy',()=>{
 it('detects an old generation even when all version numbers are identical',()=>{
  expect(upgradeRecovery(oldBackend(),100)).toMatchObject({kind:'old_backend',action:'request_upgrade'});
 });
 it('does not promote a reported old generation into a trusted upgrade source',()=>{
  const s=oldBackend();s.installation!.supervisor!.runningVerified=false;
  expect(upgradeRecovery(s,100).action).toBeUndefined();
 });
 it.each([0,80,103])('rejects an old supervisor with missing, stale or future heartbeat %s',updatedAt=>{
  const s=oldBackend();s.supervisor.updatedAt=updatedAt;
  expect(upgradeRecovery(s,100).action).toBeUndefined();
 });
 it.each(['', 'unknown', undefined])('requires a valid installed target %s',generation=>{
  const s=oldBackend();s.installation!.installedGeneration=generation;
  expect(upgradeRecovery(s,100)).toMatchObject({kind:'unknown',blockMutations:true});
  expect(upgradeRecovery(s,100).action).toBeUndefined();
 });
 it('sends an older UI to the Start menu and prevents changes',()=>{
  const s=oldBackend();s.installation!.ui={state:'restart_required',generation:source,runningVerified:true};
  expect(upgradeRecovery(s,100)).toMatchObject({kind:'ui_outdated',blockMutations:true});
  expect(upgradeRecovery(s,100).action).toBeUndefined();
 });
 it('requires verified UI identity before offering a mutation',()=>{
  const s=oldBackend();s.installation!.ui!.runningVerified=false;
  expect(upgradeRecovery(s,100).action).toBeUndefined();
 });
 it('blocks both upgrade and restore for inconsistent installations',()=>{
  for(const s of [oldBackend(),missingBackend()]){
   s.installation!.consistent=false;
   expect(upgradeRecovery(s,100)).toMatchObject({kind:'inconsistent',blockMutations:true});
   expect(upgradeRecovery(s,100).action).toBeUndefined();
  }
 });
 it.each(['requested','quiescing','checkpointed','releasing','starting','verifying','rolling_back'])('keeps %s read-only',phase=>{
  const model=upgradeRecovery(record(oldBackend(),phase),100);
  expect(model).toMatchObject({kind:'handoff',blockMutations:true});
  expect(model.action).toBeUndefined();
 });
 it.each(['handoff_unsupported','capability_failed',undefined])('never retries a waiting restart for the same target (%s)',failure=>{
  const model=upgradeRecovery(record(oldBackend(),'waiting_restart',failure),100);
  expect(model).toMatchObject({kind:'waiting_restart',blockMutations:true});
  expect(model.action).toBeUndefined();
 });
 it('allows checking a newer target after an older waiting restart',()=>{
  const s=record(oldBackend(),'waiting_restart','handoff_unsupported');s.installation!.upgrade!.toGeneration='c'.repeat(64);
  expect(upgradeRecovery(s,100)).toMatchObject({kind:'old_backend',action:'request_upgrade'});
 });
 it('offers a manual dispatch retry only while source and target still match',()=>{
  const s=record(oldBackend(),'failed','dispatch_failed');
  expect(upgradeRecovery(s,100)).toMatchObject({kind:'failed',action:'request_upgrade',actionLabel:'重新尝试更新'});
  for(const key of ['fromGeneration','toGeneration'] as const){
   const changed={...s,installation:{...s.installation!,upgrade:{...s.installation!.upgrade!,[key]:'c'.repeat(64)}}};
   expect(upgradeRecovery(changed,100).action).toBeUndefined();
  }
 });
 it.each(['start_failed','verification_failed','rollback_failed','coordinator_interrupted','future_failure'])('blocks retry after an actual or unknown failure %s',failure=>{
  expect(upgradeRecovery(record(oldBackend(),'failed',failure),100).action).toBeUndefined();
  expect(upgradeRecovery(record(missingBackend(),'failed',failure),100).action).toBeUndefined();
 });
 it('does not claim current success from an old journal or missing process evidence',()=>{
  const s=record(snapshot(),'succeeded');
  expect(upgradeRecovery(s,100).kind).toBe('succeeded');
  expect(upgradeRecovery(s,120).kind).toBe('unknown');
  s.installation!.helper!.runningVerified=false;
  expect(upgradeRecovery(s,100).kind).toBe('unknown');
  s.installation!.upgrade!.toGeneration='c'.repeat(64);
  expect(upgradeRecovery(s,100).kind).not.toBe('succeeded');
 });
 it('identifies rollback without claiming the installed target is active',()=>{
  expect(upgradeRecovery(record(oldBackend(),'rolled_back','verification_failed'),100).kind).toBe('rolled_back');
  expect(upgradeRecovery(record(oldBackend(),'rolled_back','verification_failed'),120).kind).toBe('unknown');
 });
 it('keeps unknown phases and unbound terminal records read-only',()=>{
  for(const upgrade of [
   {phase:'future_phase',toGeneration:target},
   {phase:'succeeded',toGeneration:undefined},
   {phase:'waiting_restart',toGeneration:undefined},
  ]){
   const s=record(oldBackend(),upgrade.phase);Object.assign(s.installation!.upgrade!,upgrade);
   expect(upgradeRecovery(s,100)).toMatchObject({kind:'unknown',blockMutations:true});
   expect(upgradeRecovery(s,100).action).toBeUndefined();
  }
 });
 it('offers restoring missing background evidence only for an enabled connection',()=>{
  expect(upgradeRecovery(missingBackend(),100)).toMatchObject({kind:'backend_missing',action:'restore_backend'});
  const paused={...missingBackend(),desired:false};
  expect(upgradeRecovery(paused,100)).toMatchObject({kind:'paused'});
  expect(upgradeRecovery(paused,100).action).toBeUndefined();
 });
 it('does not interpret a failed read as an absent backend',()=>{
  for(const stage of ['supervisor_read','helper_control','state_read','desired_read','upgrade_read']){
   const s=missingBackend();s.issues=[{stage,code:'permission_denied',message:'Fixture',retryable:false,operationId:'fixture'}];
   expect(upgradeRecovery(s,100).kind).toBe('unknown');
   expect(upgradeRecovery(s,100).action).toBeUndefined();
  }
 });
 it('does not block recovery on an optional startup task query failure',()=>{
  const s=missingBackend();s.issues=[{stage:'startup',code:'login_task_status_failed',message:'Fixture',retryable:true,operationId:'fixture'}];
  expect(upgradeRecovery(s,100).action).toBe('restore_backend');
 });
 it('waits for a requested startup before offering another restore',()=>{
  const s=missingBackend();s.connection={...s.connection!,phase:'starting',desiredChangedAt:95};
  expect(upgradeRecovery(s,100)).toMatchObject({kind:'starting',blockMutations:true});
  expect(upgradeRecovery(s,100).action).toBeUndefined();
  expect(upgradeRecovery(s,130).action).toBe('restore_backend');
 });
 it('does not mistake a route problem or an absent Codex window for a missing backend',()=>{
  const s=snapshot();s.connection={...s.connection!,phase:'recovering',available:false,usableRoutes:0};
  expect(upgradeRecovery(s,100)).toMatchObject({kind:'current',visible:false});
 });
 it('handles bridge restarts separately and ignores an offline historical process',()=>{
  const s=snapshot();s.processes=[{name:'desktop',state:'在线',bridgeUpdatePending:true}];
  expect(upgradeRecovery(s,100)).toMatchObject({kind:'bridge',bridge:'pending'});
  s.processes[0].state='离线';
  expect(upgradeRecovery(s,100).visible).toBe(false);
 });
 it.each([false,undefined,null])('never claims a bridge is current from flag %s',bridgeRestartRequired=>{
  const s=record(snapshot(),'succeeded');s.installation!.upgrade!.bridgeRestartRequired=bridgeRestartRequired;
  expect(upgradeRecovery(s,100).bridge).toBe('unverified');
 });
 it('does not report success or infer pause from a legacy snapshot without installation evidence',()=>{
  const s=snapshot();delete s.installation;
  expect(upgradeRecovery(s,100)).toMatchObject({kind:'unknown',blockMutations:true});
 });
});

describe('native automatic background recovery',()=>{
 function recovery(phase:string,attempts=0,nextAttemptAt?:number,reason?:string):Snapshot {
  return {...missingBackend(),backgroundRecovery:{phase,attempts,maxAttempts:3,nextAttemptAt,reason}} as Snapshot;
 }
 it('shows the initial native delay without dispatching a frontend retry',()=>{
  expect(upgradeRecovery(recovery('waiting',0,105),100)).toMatchObject({
   kind:'retry_waiting',tone:'progress',blockMutations:true,retry:{attempts:0,maxAttempts:3,remainingSeconds:5},
  });
  expect(upgradeRecovery(recovery('waiting',0,105),100).action).toBeUndefined();
 });
 it.each([[1,110,10],[2,130,30]])('shows delay after attempt %s in seconds', (attempts,nextAttemptAt,seconds)=>{
  expect(upgradeRecovery(recovery('waiting',attempts,nextAttemptAt),100).retry).toMatchObject({attempts,maxAttempts:3,remainingSeconds:seconds});
 });
 it('does not schedule a fourth attempt when the final verification timer expires',()=>{
  for(const now of [100,130,131]){
   const model=upgradeRecovery(recovery('starting',3,130),now);
   expect(model).toMatchObject({kind:'retry_starting',tone:'progress',retry:{attempts:3,maxAttempts:3,remainingSeconds:Math.max(0,130-now),verifying:true}});
   expect(model.action).toBeUndefined();
  }
 });
 it('keeps a final waiting snapshot in verification instead of showing a fourth retry',()=>{
  expect(upgradeRecovery(recovery('waiting',3,130),100)).toMatchObject({kind:'retry_starting',retry:{verifying:true,remainingSeconds:30}});
 });
 it('only offers a new manual round once native reports exhausted',()=>{
  expect(upgradeRecovery(recovery('exhausted',3),100)).toMatchObject({
   kind:'retry_exhausted',tone:'attention',action:'restore_backend',actionLabel:'重试启动后台',blockMutations:false,retry:{attempts:3,maxAttempts:3},
  });
 });
 it('still shows native retries while unavailable helper evidence is being checked',()=>{
  const s=recovery('starting',1,110);s.issues=[{stage:'helper_control',code:'helper_unavailable',message:'Fixture',operationId:'fixture',retryable:true}];
  expect(upgradeRecovery(s,100).kind).toBe('retry_starting');
 });
 it('never calls a recovered flag proof that missing processes are usable',()=>{
  expect(upgradeRecovery(recovery('recovered',1),100)).toMatchObject({kind:'unknown',blockMutations:true});
 });
 it('only acknowledges recovery with current live evidence from both processes',()=>{
  const s={...snapshot(),backgroundRecovery:{phase:'recovered',attempts:2,maxAttempts:3}} as Snapshot;
  expect(upgradeRecovery(s,100)).toMatchObject({kind:'recovered',tone:'success',blockMutations:false,retry:{attempts:2,maxAttempts:3}});
  expect(upgradeRecovery(s,120).tone).not.toBe('success');
 });
 it('clears a stale retry state when both current processes are verified',()=>{
  const s={...snapshot(),backgroundRecovery:{phase:'exhausted',attempts:3,maxAttempts:3}} as Snapshot;
  expect(upgradeRecovery(s,100)).toMatchObject({kind:'current',visible:false});
  expect(upgradeRecovery(s,100).action).toBeUndefined();
 });
 it.each(['waiting','starting','exhausted','blocked','recovered'])('preserves an explicit pause over native %s',phase=>{
  const s=recovery(phase,3,130,'paused');s.desired=false;
  expect(upgradeRecovery(s,100)).toMatchObject({kind:'paused',visible:false});
  expect(upgradeRecovery(s,100).action).toBeUndefined();
 });
 it('does not turn a known old background into unknown on blocked helper reads',()=>{
  const s={...oldBackend(),backgroundRecovery:{phase:'blocked',attempts:0,maxAttempts:3,reason:'update_required'}} as Snapshot;
  s.issues=[{stage:'helper_control',code:'control_rejected',message:'Fixture',operationId:'fixture',retryable:false}];
  expect(upgradeRecovery(s,100)).toMatchObject({kind:'old_backend'});
  expect(upgradeRecovery(s,100).action).toBeUndefined();
 });
 it('keeps an unsupported waiting-restart journal ahead of blocked generic state reads',()=>{
  const s={...record(oldBackend(),'waiting_restart','handoff_unsupported'),backgroundRecovery:{phase:'blocked',attempts:0,maxAttempts:3,reason:'upgrade_pending'}} as Snapshot;
  s.issues=[{stage:'state_read',code:'read_failed',message:'Fixture',operationId:'fixture',retryable:false}];
  expect(upgradeRecovery(s,100)).toMatchObject({kind:'waiting_restart'});
  expect(upgradeRecovery(s,100).description).toContain('当前旧后台不支持安全交接');
 });
 it('explains a native safety block rather than falling through to unknown',()=>{
  const s=recovery('blocked',0,undefined,'owner_running');
  expect(upgradeRecovery(s,100)).toMatchObject({kind:'retry_blocked',blockMutations:true});
  expect(upgradeRecovery(s,100).description).toContain('仍有后台进程');
  expect(upgradeRecovery(s,100).action).toBeUndefined();
 });
 it.each(['requested','verifying','failed'])('does not let native recovery mask an upgrade %s',phase=>{
  const s={...record(missingBackend(),phase,'start_failed'),backgroundRecovery:{phase:'exhausted',attempts:3,maxAttempts:3}} as Snapshot;
  expect(upgradeRecovery(s,100).kind).toBe(phase==='failed'?'failed':'handoff');
  expect(upgradeRecovery(s,100).action).toBeUndefined();
 });
 it.each(['state_read','installation','desired_read','upgrade_read','supervisor_read'])('never offers retry with a critical %s failure',stage=>{
  const s=recovery('exhausted',3);s.issues=[{stage,code:'read_failed',message:'Fixture',operationId:'fixture',retryable:false}];
  expect(upgradeRecovery(s,100).action).toBeUndefined();
 });
 it('does not offer a duplicate launch when a supervisor remains alive',()=>{
  const s=recovery('exhausted',3);s.supervisor=snapshot().supervisor;s.installation!.supervisor=snapshot().installation!.supervisor;
  expect(upgradeRecovery(s,100).action).toBeUndefined();
 });
 it.each(['future_phase','idle'])('keeps %s compatible with ordinary evidence classification',phase=>{
  expect(upgradeRecovery(recovery(phase),100).kind).toBe(phase==='idle'?'backend_missing':'unknown');
 });
 it('keeps the old manual recovery path without the optional native field',()=>{
  expect(upgradeRecovery(missingBackend(),100).action).toBe('restore_backend');
 });
 it('keeps legacy controls working without new installation or recovery evidence',()=>{
  const s=snapshot();s.installation={consistent:true};
  expect(recoveryBlocksMutations(s,100)).toBe(false);
  expect(recoveryBlocksMutations(oldBackend(),100)).toBe(true);
  expect(recoveryBlocksMutations(recovery('waiting',0,105),100)).toBe(true);
 });
 it('rejects impossible native counters instead of offering a new round',()=>{
  for(const attempts of [-1,1.5,4])expect(upgradeRecovery(recovery('exhausted',attempts),100).action).toBeUndefined();
 });
 it('does not trigger startup for current background without a usable network route',()=>{
  const s={...snapshot(),backgroundRecovery:{phase:'idle',attempts:0,maxAttempts:3}} as Snapshot;
  s.connection={...s.connection!,available:false,usableRoutes:0,readyRouteIds:[]};
  expect(upgradeRecovery(s,100).action).toBeUndefined();
  expect(upgradeRecovery(s,100).visible).toBe(false);
 });
 it.each(['recovered','idle','blocked'])('treats an old waiting-restart journal as history after current verified startup (%s)',phase=>{
  const s={...record(snapshot(),'waiting_restart','handoff_unsupported'),backgroundRecovery:{phase,attempts:1,maxAttempts:3,reason:'update_required'}} as Snapshot;
  const model=upgradeRecovery(s,100);
  expect(model.kind).toBe(phase==='recovered'?'recovered':'bridge');
  expect(model.description).not.toContain('重新登录 Windows');expect(model.action).toBeUndefined();
 });
 it('keeps a real handoff ahead of fresh current background',()=>{
  expect(upgradeRecovery(record(snapshot(),'verifying'),100).kind).toBe('handoff');
 });
 it('does not demand another login while native retries after stale legacy owners exited',()=>{
  const s={...record(oldBackend(),'waiting_restart','handoff_unsupported'),backgroundRecovery:{phase:'waiting',attempts:0,maxAttempts:3,nextAttemptAt:105}} as Snapshot;
  for(const kind of ['supervisor','helper'] as const)s.installation![kind]!.runningVerified=false;
  s.supervisor={phase:'stopped',updatedAt:80,failures:0};s.connection={phase:'failed',connected:false,helperUpdatedAt:80};
  expect(upgradeRecovery(s,100)).toMatchObject({kind:'retry_waiting'});
  expect(upgradeRecovery(s,100).description).not.toContain('重新登录 Windows');
 });
 it.each(['remote_paused','state_unverified','update_required','upgrade_pending','owner_running','background_unavailable','future_reason'])('keeps the native block %s read-only',reason=>{
  const model=upgradeRecovery(recovery('blocked',0,undefined,reason),100);
  expect(model.kind).toBe('retry_blocked');expect(model.action).toBeUndefined();expect(model.description).not.toContain(reason);
 });
 it('shows a manual launch as verification before any automatic attempt',()=>{
  expect(upgradeRecovery(recovery('starting',0,130),100)).toMatchObject({kind:'retry_starting',retry:{attempts:0,remainingSeconds:30,verifying:true}});
 });
 it('does not promote a terminal success record past a critical current read failure',()=>{
  const s=record(snapshot(),'succeeded');s.issues=[{stage:'state_read',code:'read_failed',message:'Fixture',operationId:'fixture',retryable:false}];
  expect(upgradeRecovery(s,100).tone).not.toBe('success');expect(upgradeRecovery(s,100).action).toBeUndefined();
 });
 it('does not repeat legacy login guidance when only the current helper is freshly verified',()=>{
  const s=record(snapshot(),'waiting_restart','handoff_unsupported');s.installation!.supervisor={state:'unverified',generation:source,runningVerified:false};
  s.supervisor={phase:'stopped',updatedAt:80,failures:0};s.backgroundRecovery={phase:'recovered',attempts:0,maxAttempts:3};
  const model=upgradeRecovery(s,100);expect(model.description).not.toContain('重新登录 Windows');expect(model.action).toBeUndefined();
  expect(model.tone).not.toBe('success');
 });
});
