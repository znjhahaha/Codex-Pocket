import {nativePresence} from './nativePresence';
import {Computer,ShieldCheck,RefreshCw,ArrowDownToLine,Command} from './PocketIcons';
import {type RuntimeReadiness,type UpdateState} from './domain';
import {installerCoordinatorNotice} from './upgradeRecovery';
import {useRestartNoticeReceipt} from './useRestartNoticeReceipt';
const roles:Record<string,string>={desktop:'Codex 桌面',auxiliary:'辅助运行时',managedHost:'电脑助理',cli:'Codex CLI',cliDaemon:'Codex CLI'};
export function assistantPreparationMessage(status?:{ready?:boolean;error?:string}) {
 if(status?.ready)return '助理已通过本机认证核验';
 switch(status?.error){
  case 'assistant_runtime_start_failed':return '助理尚未启动。重新准备后会再次核验，当前任务继续保留。';
  case 'assistant_preparing':return '正在准备助理，请稍候。';
  case 'assistant_ownership_unconfirmed':return '原有任务的运行状态尚未确认，请查看诊断详情。';
  case 'assistant_profile_invalid':case 'assistant_restore_failed':return '助理配置需要检查，请查看诊断详情后修复。';
  default:return '准备状态等待核验，可以重新准备助理。';
 }
}
export function RuntimeReadinessPanel({readiness,now}:{readiness?:RuntimeReadiness;now:number}) {
 const {current,desktop:detectedDesktop,cli,desktopControl,cliControl,desktopVersion,history}=nativePresence(readiness,now);
 const detected=detectedDesktop||cli;
 const control=desktopControl||cliControl;
 return <section className="section readiness-panel" aria-label="原生运行时就绪状态">
  <div className="section-heading"><h2><Computer size={18}/>Codex 接入</h2><span className={`status ${control?'good':''}`}><i/>{control?'实时控制已就绪':detected?'已发现运行中的 Codex':!current?'状态等待核验':readiness?.installed?'已安装 · 实时控制未连接':'安装状态待核验'}</span></div>
  <p className="panel-intro">{history?'历史可读，可在手机浏览已保存的 CLI、桌面与 IDE 对话。':'历史目录等待核验。'} 实时续聊需要连接原生运行时；程序已安装不代表已经接入。</p>
  {current&&<p className="panel-intro">CLI：{readiness?.cliInstalled===true?'已安装':readiness?.cliInstalled===false?'未发现':'待核验'} · 官方桌面：{readiness?.desktopInstalled===true?'已安装':readiness?.desktopInstalled===false?'未发现':'待核验'}</p>}
  {detectedDesktop&&<p className="panel-intro">官方桌面正在运行{desktopVersion?` · ${desktopVersion}`:''}{!desktopControl?' · 实时控制未核验。':''}</p>}
  {cli&&<p className="panel-intro">CLI 正在运行 · {cliControl?'共享服务已连接。':'未连接共享服务。'}</p>}
  {readiness?.runtimes.length?<div className="readiness-list">{readiness.runtimes.map((r,i)=><article className="readiness-runtime" key={r.id||i}>
   <div className="runtime-title"><Command size={18}/><strong>{roles[r.role]||'其他运行时'}</strong><span>{current&&r.online?'已连接':'等待连接'}</span></div>
   <div className="readiness-stages">{[{label:'原生初始化',ready:r.initialized===true,pending:'等待初始化'},{label:'接口能力',ready:r.schemaReady,pending:'等待接口'},{label:'会话目录',ready:r.inventoryReady,pending:'等待目录'}].map(stage=><div key={stage.label} className={current&&stage.ready?'verified':''}><i/><span>{stage.label}</span><small>{current&&stage.ready?'已核验':stage.pending}</small></div>)}</div>
  </article>)}</div>:!detected&&<div className="empty-line">打开 Codex 后，此处显示已认证的运行时与准备阶段。</div>}
 </section>
}
const updateLabels:Record<string,string>={idle:'尚未检查',checking:'正在核验更新',current:'已是当前版本',downloading:'正在下载签名更新',staged:'下载已核验',waiting_idle:'等待任务空闲',waiting_cold_start:'等待后台切换',restarting_legacy:'正在核对旧后台任务',switching:'正在保存状态并切换',cold_starting:'正在核验新版后台启动',succeeded:'更新已完成',rolled_back:'已恢复之前的后台',failed:'更新需要处理',unavailable:'更新来源尚未配置'};
export function UpdatesPanel({status,busy,onCheck,onRestart,onAutomatic,runningVersion,installedVersion}:{status?:UpdateState;busy:boolean;onCheck:()=>void;onRestart?:(operationId:string)=>void;onAutomatic:(enabled:boolean)=>void;runningVersion?:string;installedVersion?:string}) {
 const waitReason=status?.waitReason||status?.coordinator?.reason;
 const coordinatorNotice=installerCoordinatorNotice(status);
 const noticeRef=useRestartNoticeReceipt(coordinatorNotice?.restartNotice,status);
 const verifyingRollback=status?.phase==='cold_starting'&&status.coordinator?.phase==='verifying_rollback';
 const active=!coordinatorNotice?.stopped&&['checking','downloading','switching','restarting_legacy','cold_starting'].includes(status?.phase||'');
 const total=status?.totalBytes||0,downloaded=status?.downloadedBytes||0;
 const restartable=!!status?.operationId&&(['staged','waiting_idle','waiting_cold_start'].includes(status.phase)||status.phase==='failed'&&status.failure==='legacy_restart_unverified'||coordinatorNotice?.stopped&&['restarting_legacy','switching','cold_starting'].includes(status.phase));
 const restarting=!coordinatorNotice?.stopped&&status?.phase!=='failed'&&status?.restartRequested&&status.coordinator?.phase!=='waiting_login'&&status.coordinator?.phase!=='finished';
 return <section className="section updates-panel" aria-label="签名自动更新">
  <div className="section-heading"><h2><ArrowDownToLine size={18}/>更新</h2><button className="button" disabled={busy||active} onClick={onCheck}><RefreshCw size={16}/>检查并下载更新</button></div>
  <div className="update-summary"><span className="update-shield"><ShieldCheck size={28}/></span><div><span className="eyebrow">{status?.version?`版本 ${status.version}`:'Codex Pocket'}</span><h3>{coordinatorNotice?coordinatorNotice.stopped?'后台更新尚未完成':'正在重启 Pocket 后台':verifyingRollback?'正在核验旧后台恢复':status?.phase==='waiting_idle'&&['runtime_activity_unverified','unconfirmed_operations'].includes(waitReason||'')?'等待核对旧后台':updateLabels[status?.phase||'idle']||'状态等待核验'}</h3><p ref={noticeRef}>{coordinatorNotice?.description||'签名核验后自动下载。任务空闲时保存检查点并切换，核验失败后恢复之前的后台。'}</p></div></div>
  <dl className="update-identities"><div><dt>目标版本</dt><dd>{status?.version||'尚未选择'}</dd></div><div><dt>已安装版本</dt><dd>{installedVersion||'待核验'}</dd></div><div><dt>后台实际运行</dt><dd>{runningVersion||'未取得有效握手'}</dd></div></dl>
  {restartable&&<div className="update-restart"><button className="button primary" disabled={busy||restarting} onClick={()=>status?.operationId&&onRestart?.(status.operationId)}><RefreshCw size={17}/>{restarting?'已安排重启更新':'重启并更新'}</button><p>使用已下载的更新。保存当前工作后，自动重启 Pocket 并核验后台版本。</p></div>}
  {total>0&&<div className="update-download"><progress aria-label="更新下载进度" value={Math.min(downloaded,total)} max={total}/><small>{(downloaded/1048576).toFixed(1)} / {(total/1048576).toFixed(1)} MB</small></div>}
  {!coordinatorNotice&&status?.phase==='waiting_idle'&&<p className="update-note">{status.coordinator?.reason==='restart_cli_task_active'?'Pocket 自己启动的 Codex CLI 任务还在运行，结束后自动完成更新。':status.coordinator?.reason==='restart_write_in_flight'?'手机刚发出的操作正在送达 Codex，送达后自动完成更新。':status.coordinator?.reason==='restart_retrying'?'上一次重启 Pocket 后台没有完成，正在自动重试。':waitReason==='runtime_activity_unverified'?'旧后台无法确认运行时状态，未执行切换。关闭官方 Codex 不会清除 Pocket 的待核对记录。':waitReason==='unconfirmed_operations'?'旧后台有结果未确认的操作，候选版已暂存，现有数据保持不变。':'当前任务继续运行；待处理问题和未确认操作完成后再切换。'}</p>}
  {!coordinatorNotice&&status?.phase==='waiting_cold_start'&&<p className="update-note">Pocket 会在当前 Windows 会话中核对任务、重启后台，并验证运行版本。</p>}
  {(coordinatorNotice?.stopped||status?.coordinator?.phase==='waiting_login')&&<p className="update-note">{restartable||status?.coordinator?.phase==='waiting_login'?'点击“重启并更新”重试。':'点击“检查并下载更新”重新核验并重试。'}</p>}
  {verifyingRollback&&<p className="update-note">新版未通过启动核验。Pocket 会在旧后台通过本机认证并读到配置后确认恢复。</p>}
  {status?.coordinator?.reason==='live_owner_not_verified'&&<p className="update-note">新版后台已占用状态目录，但尚未通过核验。现有进程保持运行；请查看后台启动诊断。</p>}
  {status?.manager?.phase==='applying'&&<p className="update-note">后台已通过核验，正在更新管理入口。</p>}
  {status?.manager?.phase==='failed'&&<p className="update-note">管理入口更新未完成，后台和任务仍保留。点击“检查并下载更新”可重新核验并重试。</p>}
  {status?.manager?.phase==='migration_required'&&<p className="update-note">后台更新已保留。此电脑尚未迁移管理入口，请运行新版完整安装包完成迁移。</p>}
  {status?.failure&&<p className="update-note">{status.failure==='update_trust_unavailable'?'当前安装包未提供受信任的签名更新来源。请使用已验证的完整安装包更新。':status.failure==='legacy_restart_unverified'?'旧后台已恢复服务。当前操作已保留，稍后点击“重启并更新”重试。':status.phase==='rolled_back'?'候选后台未通过核验，恢复结果已记录。':'下载、签名或后台切换未完成，请查看诊断。'}</p>}
  <div className="update-setting"><div><strong>自动下载与更新</strong><p>后台每小时检查，空闲后自动应用。</p></div><button role="switch" aria-checked={status?.automatic===true} aria-label="自动下载与更新" disabled={busy||!status} className={`toggle ${status?.automatic?'on':''}`} onClick={()=>onAutomatic(!status?.automatic)}><span/></button></div>
 </section>
}
