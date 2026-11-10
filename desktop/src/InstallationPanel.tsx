import type {InstallationStatus} from './domain';
import {OptionalIntegrationPanel} from './OptionalIntegrationPanel';

export function InstallationPanel({status,onRefresh,onDiagnostics,onUpgrade,busy,action}:{status?:InstallationStatus;onRefresh:()=>void;onDiagnostics:()=>void;onUpgrade?:()=>void;busy?:boolean;action?:(fn:()=>Promise<void>)=>Promise<void>}) {
 const installed=status?.installedGeneration;
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
  {status?.restartRequired&&<p role="status">有组件仍报告旧安装代次。请保留正在执行的任务，任务结束后重新登录 Windows，再核验后台。管理界面可从开始菜单重新打开；后台交接尚未确认完成。</p>}
  {status?.upgrade&&<p role="status">后台更新：{({requested:'等待检查',quiescing:'等待安全时机',checkpointed:'状态已保存',releasing:'释放旧后台',starting:'启动新后台',verifying:'核验新后台',rolling_back:'正在恢复旧后台',waiting_restart:'等待重启生效',rolled_back:'已回滚',succeeded:'交接已核验',failed:'需要恢复'} as Record<string,string>)[status.upgrade.phase]||'状态待核验'} · 操作 {status.upgrade.operationId}{status.upgrade.phase==='waiting_restart'?'。当前后台缺少安全交接能力；任务完成后重新登录 Windows，再点击重新核验。':''}</p>}
  {status?.restartRequired&&onUpgrade&&<button className="button" disabled={busy||(status.upgrade?.phase==='waiting_restart'&&status.upgrade.toGeneration===installed)} onClick={onUpgrade}>检查安全交接</button>}
  <OptionalIntegrationPanel status={status?.optionalIntegrations} generation={installed} busy={busy||status?.consistent!==true} action={action}/>
  <button className="button" disabled={busy} onClick={onDiagnostics}>导出诊断</button>
 </section>;
}
