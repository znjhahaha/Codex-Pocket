import type {InstallationStatus} from './domain';
import {OptionalIntegrationPanel} from './OptionalIntegrationPanel';

const upgradePhases:Record<string,string>={
 requested:'等待检查安全交接能力',quiescing:'暂停新写入并保存状态',checkpointed:'状态已保存并核验',
 releasing:'释放旧后台并等待自然退出',starting:'启动新后台并恢复状态',verifying:'核验新后台身份、线路与状态',
 rolling_back:'正在恢复旧后台',waiting_restart:'后台等待重启生效',rolled_back:'旧后台已恢复并核验',
 succeeded:'后台交接已核验',failed:'后台恢复未完成',
};
const upgradeReasons:Record<string,string>={
 handoff_unsupported:'当前后台不支持安全交接',capability_failed:'未能核验后台安全交接能力',
 quiesce_failed:'暂停新写入或保存状态未完成',checkpoint_mismatch:'保存的状态与交接记录不一致',
 release_failed:'旧后台自然退出或锁释放未通过核验',start_failed:'新后台启动或状态恢复未完成',
 verification_failed:'新后台身份、线路或状态核验未通过',rollback_failed:'旧后台恢复未通过核验',
 coordinator_interrupted:'上次后台交接中断',installation_changed:'安装代次在交接期间发生变化',
 dispatch_failed:'安全交接进程未能启动',journal_invalid:'交接记录未通过核验',
 startup_context_invalid:'启动交接记录未通过核验',
};

export function InstallationPanel({status,onRefresh,onDiagnostics,onUpgrade,busy,action}:{status?:InstallationStatus;onRefresh:()=>void;onDiagnostics:()=>void;onUpgrade?:()=>void;busy?:boolean;action?:(fn:()=>Promise<void>)=>Promise<void>}) {
 const installed=status?.installedGeneration;
 const upgrade=status?.upgrade;
 const phase=upgrade&&Object.hasOwn(upgradePhases,upgrade.phase)?upgradePhases[upgrade.phase]:'状态待核验';
 const reason=upgrade?.failure&&Object.hasOwn(upgradeReasons,upgrade.failure)?upgradeReasons[upgrade.failure]:undefined;
 const components=[['管理界面',status?.ui],['后台监督',status?.supervisor],['电脑助手',status?.helper]] as const;
 return <section className="section installation-panel" aria-label="安装与运行版本">
  <div className="section-heading"><h2>安装与运行版本</h2><button className="button" onClick={onRefresh}>重新核验</button></div>
  <p>已安装 {status?.installedVersion||'尚未核验'} · 安装代次 <code title={installed}>{installed?.slice(0,12)||'尚未读取'}</code></p>
  <div className="runtime-list">{components.map(([name,runtime])=><div className="runtime" key={name}>
   <div><strong>{name}</strong><p>{runtime?.runningVerified?'运行版本':'报告版本'} {runtime?.version||'未知'} · 代次 <code title={runtime?.generation}>{runtime?.generation?.slice(0,12)||'未知'}</code>
    {runtime?.controlProtocol?` · 控制协议 ${runtime.controlProtocol}`:''}</p>
    <small>{runtime?.runningVerified?'已核对进程、创建时间、程序路径和摘要':'尚未取得有效的运行进程证据'}</small></div>
   <span className={`status ${runtime?.state==='current'?'good':''}`}>{runtime?.state==='current'?'当前安装已生效':runtime?.state==='restart_required'?'等待重启生效':'尚未核验'}</span>
  </div>)}</div>
  {status?.restartRequired&&<p role="status">有组件仍报告旧安装代次。请按上方各组件的核验结果处理；管理界面可从开始菜单重新打开 Pocket。</p>}
  {upgrade&&<>
   <p role="status">后台更新：{phase} · 操作 <code>{upgrade.operationId}</code>
    {upgrade.failure&&<>。原因：{reason||'原因尚未识别，请导出诊断'}{reason&&<>（<code>{upgrade.failure}</code>）</>}</>}
    {upgrade.phase==='waiting_restart'&&<>{!upgrade.failure&&'。等待重启的原因尚未核验'}。请先完成当前任务，再重新登录 Windows，点击“重新核验”。</>}
   </p>
   <p role="status" aria-label="正在运行的 Codex bridge">后台交接只核验后台监督和电脑助手。
    {upgrade.bridgeRestartRequired===true?'正在运行的 Codex bridge 仍等待重启生效。':'Codex bridge 的运行版本尚未核验。'}
    {upgrade.bridgeRestartRequired!==false&&'完成当前Codex任务后重新打开Codex，再核验 bridge 版本。'}
   </p>
  </>}
  {status?.restartRequired&&onUpgrade&&<button className="button" disabled={busy||(status.upgrade?.phase==='waiting_restart'&&status.upgrade.toGeneration===installed)} onClick={onUpgrade}>检查安全交接</button>}
  <OptionalIntegrationPanel status={status?.optionalIntegrations} generation={installed} busy={busy||status?.consistent!==true} action={action}/>
  <button className="button" disabled={busy} onClick={onDiagnostics}>导出诊断</button>
 </section>;
}
