import {freshHeartbeat,type InstalledComponent,type Snapshot} from './domain';

export type RecoveryKind='current'|'inconsistent'|'ui_outdated'|'unknown'|'old_backend'|'backend_missing'|'starting'|'paused'|'handoff'|'waiting_restart'|'failed'|'rolled_back'|'succeeded'|'bridge'|'retry_waiting'|'retry_starting'|'retry_exhausted'|'retry_blocked'|'recovered';
export interface RecoveryModel {
 kind:RecoveryKind;
 visible:boolean;
 title:string;
 description:string;
 tone:'attention'|'progress'|'neutral'|'success';
 blockMutations:boolean;
 action?:'request_upgrade'|'restore_backend';
 actionLabel?:string;
 bridge?:'pending'|'unverified';
 upgradeHistorical?:boolean;
 retry?:{attempts:number;maxAttempts:number;remainingSeconds?:number;verifying?:boolean};
}
const progress:Record<string,string>={
 requested:'正在检查安全交接能力',quiescing:'正在保存状态并暂停新写入',checkpointed:'状态已保存，等待交接',
 releasing:'正在等待旧后台自然退出',starting:'正在启动新后台',verifying:'正在核验新后台',rolling_back:'正在恢复旧后台',
};
const failureReasons:Record<string,string>={
 handoff_unsupported:'当前旧后台不支持安全交接。',capability_failed:'未能核验后台的安全交接能力。',
 quiesce_failed:'保存状态或暂停新写入未完成。',checkpoint_mismatch:'保存的状态与交接记录不一致。',
 release_failed:'旧后台退出或锁释放未通过核验。',start_failed:'新后台启动或状态恢复未完成。',
 verification_failed:'新后台身份、线路或状态核验未通过。',rollback_failed:'旧后台恢复未通过核验。',
 coordinator_interrupted:'上次后台交接已中断。',installation_changed:'交接期间安装代次发生变化。',
 dispatch_failed:'更新检查进程未能启动，后台更新尚未完成。',journal_invalid:'交接记录未通过核验。',
 startup_context_invalid:'启动交接记录未通过核验。',
};
const validGeneration=(generation?:string)=>typeof generation==='string'&&/^[a-f0-9]{64}$/.test(generation);
const restartInstruction='请先保存并结束当前 Codex 任务，退出 Codex，然后退出并重新登录 Windows，再回来刷新核验。仅重开 Codex 不保证更新常驻后台。';

/** Installed files, live process identity and a handoff journal are distinct evidence. */
export function upgradeRecovery(s:Snapshot,now:number):RecoveryModel {
 const installation=s.installation,upgrade=installation?.upgrade,target=installation?.installedGeneration;
 const bridge:RecoveryModel['bridge']=s.processes.some(p=>p.state==='在线'&&p.bridgeUpdatePending===true)||upgrade?.bridgeRestartRequired===true
  ?'pending':upgrade?'unverified':undefined;
 let upgradeHistorical=false;
 const model=(kind:RecoveryKind,title:string,description:string,options:Partial<RecoveryModel>={}):RecoveryModel=>({
  kind,visible:true,title,description,tone:'attention',blockMutations:true,bridge,upgradeHistorical,...options,
 });
 const unknown=(description='尚未取得可靠的安装或后台运行证据。请刷新状态，或在诊断中检查读取结果。')=>model('unknown','后台状态尚未核验',description);
 if(installation?.consistent===false)return model('inconsistent','安装状态需要检查','当前界面与安装或状态目录不一致。请关闭 Pocket 界面，从 Windows 开始菜单打开新版 Codex Pocket，再查看诊断。');
 if(!installation||!validGeneration(target))return unknown('安装目标尚未核验，暂时无法判断新版是否已生效。请从 Windows 开始菜单打开 Codex Pocket，再刷新状态。');
 if(upgrade&&Object.hasOwn(progress,upgrade.phase))return model('handoff',progress[upgrade.phase],'交接仍在进行中，请等待状态核验。此时可以刷新进度或检查诊断。',{tone:'progress'});
 const ui=installation.ui;
 if(ui?.state==='restart_required'||(validGeneration(ui?.generation)&&ui?.generation!==target))return model('ui_outdated','请打开新版 Pocket 界面','当前管理界面仍属于旧安装代次。请关闭此界面，从 Windows 开始菜单打开新版 Codex Pocket。');
 if(!ui?.runningVerified||ui.state!=='current'||ui.generation!==target)return unknown('当前界面的安装代次尚未核验。请从 Windows 开始菜单打开新版 Codex Pocket，再刷新状态。');
 const issue=(stages:string[])=>s.issues?.some(e=>stages.includes(e.stage));
 const criticalReadFailed=issue(['installation','state_read','state_access','desired_read','supervisor_read','upgrade_read']);
 const source=installation.supervisor;
 const supervisorLive=source?.runningVerified===true&&freshHeartbeat(s.supervisor.updatedAt,now)&&!issue(['supervisor_read']);
 const helperLive=installation.helper?.runningVerified===true&&freshHeartbeat(s.connection?.helperUpdatedAt||0,now)&&!issue(['helper_control']);
 const old=(runtime?:InstalledComponent)=>validGeneration(runtime?.generation)&&runtime?.generation!==target;
 const currentHelper=helperLive&&installation.helper?.generation===target&&installation.helper?.state==='current';
 const noVerifiedOldOwner=!(source?.runningVerified&&old(source))&&!(installation.helper?.runningVerified&&old(installation.helper));
 const canUpgrade=supervisorLive&&source?.state==='restart_required'&&old(source)&&!criticalReadFailed&&!issue(['helper_control']);
 const currentBackground=supervisorLive&&helperLive&&source?.generation===target&&installation.helper?.generation===target
  &&source.state==='current'&&installation.helper?.state==='current'&&s.supervisor.phase==='running';
 const background=s.backgroundRecovery;
 const nativeRetry=background&&['waiting','starting','exhausted'].includes(background.phase);
 const noVerifiedOwner=!source?.runningVerified&&!installation.helper?.runningVerified;
 const waitingRestartIsHistory=upgrade?.phase==='waiting_restart'&&(currentBackground||(currentHelper&&noVerifiedOldOwner)||(nativeRetry&&noVerifiedOwner&&upgrade.failure==='handoff_unsupported'));
 upgradeHistorical=waitingRestartIsHistory===true;
 const reason=upgrade?.failure?(Object.hasOwn(failureReasons,upgrade.failure)?failureReasons[upgrade.failure]:'失败原因尚未识别，请检查诊断。'):'';
 if(upgrade&&!waitingRestartIsHistory){
  if(!validGeneration(upgrade.toGeneration))return unknown('交接记录的目标代次尚未核验。请查看诊断并刷新，暂不重复发起更新。');
  if(upgrade.phase==='waiting_restart'&&upgrade.toGeneration===target)return model('waiting_restart','后台更新等待重新登录 Windows',`${reason||'等待重启的原因尚未核验。'}${restartInstruction}`);
  if(upgrade.phase==='failed'){
   const retry=upgrade.failure==='dispatch_failed'&&canUpgrade&&upgrade.fromGeneration===source?.generation&&upgrade.toGeneration===target;
   return model('failed','后台更新尚未完成',`${reason||'失败原因尚未核验。'}${retry?'可以手动重新尝试更新；提交请求后仍需核验结果。':'请先检查诊断中的交接记录，当前不重复发起变更。'}`,
    retry?{action:'request_upgrade',actionLabel:'重新尝试更新'}:{});
  }
  if(upgrade.phase==='rolled_back'){
   if(criticalReadFailed||upgrade.toGeneration!==target||!supervisorLive||!helperLive||source?.generation!==upgrade.fromGeneration||installation.helper?.generation!==upgrade.fromGeneration)
    return unknown(`上次交接记录为已回退；当前后台状态仍需重新核验。${reason}`);
   return model('rolled_back','旧后台已恢复，更新未生效',`${reason}已核验当前仍在运行原代次的后台。请检查诊断后处理更新。`);
  }
  if(upgrade.phase==='succeeded'&&upgrade.toGeneration===target){
   if(criticalReadFailed||!currentBackground)return unknown('交接记录显示曾完成，但当前后台运行证据尚未核验。请刷新并检查诊断。');
   return model('succeeded','新版后台已核验',`后台监督与电脑助手已运行当前安装代次。${s.desired?'手机连接状态仍需单独确认。':'远程连接保持暂停。'}`,{tone:'success',blockMutations:false});
  }
  if(!['waiting_restart','succeeded'].includes(upgrade.phase))return unknown('交接状态尚未识别。请查看诊断并刷新，暂不重复发起更新。');
 }
 if((old(source)||old(installation.helper))&&!(nativeRetry&&noVerifiedOwner)&&!(currentHelper&&noVerifiedOldOwner)){
  const trustedOld=(supervisorLive&&old(source))||(helperLive&&old(installation.helper));
  return model('old_backend',trustedOld?'已安装新版，旧后台仍在运行':'后台仍报告旧代次，运行状态待核验',
   `版本号相同也可能属于不同安装代次。${canUpgrade?'可以检查安全交接能力；检查通过后才会更新后台。':`尚不满足安全交接的核验条件。${restartInstruction}`}`,
   canUpgrade?{action:'request_upgrade',actionLabel:'检查并更新后台'}:{});
 }
 if(!s.desired)return model('paused','远程连接已暂停','当前保留你的暂停设置。需要时可通过“开启连接”手动恢复。',{tone:'neutral',visible:bridge!==undefined,blockMutations:false});
 const retry=background?{
  attempts:background.attempts,maxAttempts:background.maxAttempts,
  ...(typeof background.nextAttemptAt==='number'&&Number.isFinite(background.nextAttemptAt)&&background.nextAttemptAt>0
   ?{remainingSeconds:Math.max(0,Math.ceil(background.nextAttemptAt-now))}:{}),
 }:undefined;
 if(currentBackground&&!criticalReadFailed){
  if(background?.phase==='recovered')return model('recovered','后台已恢复并核验',`后台监督与电脑助手已运行当前安装代次。${connectionDescription(s)}`,{tone:'success',blockMutations:false,retry});
  return bridge?model('bridge','Codex 运行环境需要单独核验','后台已运行最新版。请保存当前任务后重新打开 Codex，再查看版本详情。',{blockMutations:false})
   :model('current','','',{visible:false,tone:'neutral',blockMutations:false});
 }
 if(currentHelper&&noVerifiedOldOwner&&!criticalReadFailed)return model('unknown','电脑助手已核验，后台监督待核验','电脑助手正在运行当前安装版本；后台监督的运行证据仍需刷新核验。',{tone:'neutral'});
 if(background?.phase==='blocked'){
  const descriptions:Record<string,string>={
   remote_paused:'自动重试遵循你的远程连接暂停设置。需要时请手动开启连接。',
   state_unverified:'本机状态尚未通过核验。请检查诊断中的读取结果，再刷新状态。',
   update_required:'后台更新需要先处理。请查看版本详情或诊断，按运行组件的核验结果处理更新。',
   upgrade_pending:'后台更新尚未完成，自动重试等待更新处理。请检查诊断中的交接记录。',
   owner_running:'仍有后台进程，自动重试不会重复启动。请刷新核验，或检查诊断中的后台状态。',
   background_unavailable:'后台仍未通过核验。请检查诊断中的启动记录，再刷新恢复状态。',
  };
  return model('retry_blocked','后台自动重试暂不可用',background.reason&&Object.hasOwn(descriptions,background.reason)?descriptions[background.reason]:'当前不满足安全启动条件，自动重试已暂停。请刷新状态并检查诊断中的原因。');
 }
 if(criticalReadFailed)return unknown('本机安装、配置或交接状态读取失败。请检查诊断中的失败步骤，再刷新核验。');
 if(background&&background.phase!=='idle'){
  if(!Number.isInteger(background.attempts)||background.attempts<0||background.attempts>3||background.maxAttempts!==3)return unknown('后台重试记录尚未核验，请刷新状态并检查诊断。');
  if(source?.runningVerified||installation.helper?.runningVerified)return unknown('仍有后台运行进程，暂不重复启动。请刷新并检查诊断中的运行状态。');
  if(background.phase==='waiting'||background.phase==='starting'){
   const verifying=background.phase==='starting'||background.attempts===background.maxAttempts;
   return model(verifying?'retry_starting':'retry_waiting',verifying?'正在启动并核验后台':'后台未就绪，正在自动重试',
    verifying?'启动请求已发起，核验中。正在等待后台监督与电脑助手的当前运行证据。':'远程连接已开启。电脑正在按计划重试启动后台，核验通过后才会显示可用。',
    {tone:'progress',retry:{...retry!,verifying}});
  }
  if(background.phase==='exhausted'&&background.attempts===background.maxAttempts)return model('retry_exhausted','自动重试已用尽，后台仍未就绪',
   '最后一次启动也未通过核验。可以手动开始新一轮后台重试，或先检查诊断中的失败原因。',
   {action:'restore_backend',actionLabel:'重试启动后台',blockMutations:false,retry});
  return unknown('后台重试记录与当前运行证据尚未核验。请刷新状态并检查诊断，暂不重复启动。');
 }
 if(issue(['supervisor_read','helper_control']))return unknown('后台状态读取失败，尚不能判断进程是否缺失。请检查诊断中的失败步骤，再刷新核验。');
 const changed=s.connection?.desiredChangedAt||0;
 if(s.connection?.phase==='starting'&&changed>0&&changed<=now+2&&now-changed<30)return model('starting','正在等待后台启动','启动请求已保存，正在等待后台监督与电脑助手的运行证据。',{tone:'progress'});
 // Missing stamps allow a manual Start request; stale/contradictory stamps stay unknown.
 const absent=(runtime?:InstalledComponent)=>!!runtime&&runtime.runningVerified===false&&!runtime.generation&&!runtime.pid;
 const supervisorAbsent=absent(source)&&s.supervisor.updatedAt===0&&!s.supervisor.pid&&!s.supervisor.childPid&&['stopped','failed'].includes(s.supervisor.phase);
 const helperAbsent=absent(installation.helper)&&s.connection?.helperUpdatedAt===0;
 if(helperAbsent&&(supervisorAbsent||(supervisorLive&&source?.generation===target)))return model('backend_missing','后台尚未就绪','远程连接已开启，但尚未取得电脑助手的运行证据。可以手动恢复后台，再刷新核验。',
  {action:'restore_backend',actionLabel:'重试启动后台',blockMutations:false});
 return unknown();
}

function connectionDescription(s:Snapshot){return s.connection?.available?'手机连接状态仍需单独确认。':'连接线路仍需单独核验。'}

export function recoveryBlocksMutations(s:Snapshot,now:number){
 return (!!s.installation?.installedGeneration||!!s.backgroundRecovery||s.installation?.consistent===false)&&upgradeRecovery(s,now).blockMutations;
}
