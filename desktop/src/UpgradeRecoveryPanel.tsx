import {diagnosticRuntimeStatus,type Snapshot} from './domain';
import {Info,LoaderCircle,RefreshCw,ShieldCheck} from './PocketIcons';
import {upgradeRecovery} from './upgradeRecovery';
import {BackgroundFailureDetails} from './BackgroundFailureDetails';

interface Props {
 snapshot:Snapshot;now:number;busy:boolean;
 onAction:()=>void;onRefresh:()=>void;onDiagnostics:()=>void;
}
export function UpgradeRecoveryPanel({snapshot,now,busy,onAction,onRefresh,onDiagnostics}:Props) {
 const recovery=upgradeRecovery(snapshot,now),installation=snapshot.installation;
 if(!recovery.visible)return null;
 const Icon=recovery.tone==='progress'?LoaderCircle:recovery.tone==='success'?ShieldCheck:Info;
 return <section className={`recovery-panel recovery-${recovery.tone}`} aria-label="更新与后台恢复">
  <div className="recovery-heading">
   <span className="recovery-symbol"><Icon size={23} className={recovery.tone==='progress'?'spin':undefined}/></span>
   <div aria-live="polite" aria-atomic="true"><span className="eyebrow">更新与后台恢复</span><h2>{recovery.title}</h2><p>{recovery.description}</p></div>
  </div>
  {recovery.retry&&<div className="recovery-retry" aria-label="后台自动重试进度">
   <strong>自动重试 {recovery.retry.attempts} / {recovery.retry.maxAttempts}</strong>
   {recovery.retry.remainingSeconds!==undefined&&<p>{recovery.retry.remainingSeconds>0
    ?recovery.retry.verifying?`核验中，最多再等待 ${recovery.retry.remainingSeconds} 秒。`:`${recovery.retry.remainingSeconds} 秒后自动重试启动后台。`
    :'正在等待后台更新重试状态；刷新只读取进度，不会重复启动。'}</p>}
  </div>}
  {!snapshot.desired&&recovery.kind!=='paused'&&<p className="recovery-note">远程连接保持暂停；更新检查不会替你开启远程连接。</p>}
  <details className="recovery-details"><summary>查看版本详情</summary>
  <dl className="recovery-evidence">
   <div><dt>已安装</dt><dd>{installation?.installedVersion||'版本待核验'}<code title={installation?.installedGeneration}>{installation?.installedGeneration?.slice(0,12)||'代次待核验'}</code></dd><small>安装文件</small></div>
   {(['supervisor','helper'] as const).map(kind=>{
    const runtime=installation?.[kind],verified=diagnosticRuntimeStatus(snapshot,kind,now).good;
    return <div key={kind}><dt>{kind==='supervisor'?'后台监督':'电脑助手'}</dt>
     <dd>{runtime?.version||'版本待核验'}<code title={runtime?.generation}>{runtime?.generation?.slice(0,12)||'代次待核验'}</code></dd>
     <small>{verified?'已核验运行进程':runtime?.generation?'报告代次，运行状态待核验':'尚未取得运行证据'}</small>
    </div>;
   })}
  </dl>
  {recovery.bridge&&<div className="recovery-bridge" aria-label="Codex bridge 单独核验">
   <strong>Codex bridge 单独核验</strong>
   <p>{recovery.bridge==='pending'?'正在运行的 Codex bridge 仍等待重启生效。':'Codex bridge 的运行版本尚未核验。'}后台交接只核验后台监督和电脑助手。完成并保存当前 Codex 任务后，重新打开 Codex，再核验 bridge 版本。</p>
  </div>}
  {installation?.upgrade&&<p className="subtle">更新记录 · 操作 <code>{installation.upgrade.operationId}</code></p>}
  </details>
  <BackgroundFailureDetails supervisor={snapshot.supervisor}/>
  <div className="recovery-actions">
   {recovery.action&&<button className="button primary" disabled={busy} onClick={onAction}>{recovery.actionLabel}</button>}
   <button className="button" onClick={onRefresh}><RefreshCw size={16}/>刷新恢复状态</button>
   <button className="text-link" onClick={onDiagnostics}>检查详情</button>
  </div>
 </section>;
}
