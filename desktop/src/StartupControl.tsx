export function startupDescription(status?:'known'|'pending'|'unknown',enabled?:boolean|null){
 if(status==='pending')return '正在核验登录任务，手动连接可继续';
 if(status!=='known'||typeof enabled!=='boolean')return '登录任务尚未核验或核验已过期，手动连接可继续';
 return '仅对当前用户生效，异常退出后自动恢复';
}

export function StartupControl({status,enabled,disabled,onCheck,onToggle}:{status?:'known'|'pending'|'unknown';enabled:boolean|null;disabled:boolean;onCheck:()=>void;onToggle:()=>void}){
 const known=status==='known'&&typeof enabled==='boolean';
 return <>
  <button className="button" disabled={disabled||status==='pending'} onClick={onCheck}>核验登录任务</button>
  <button className={`switch ${known&&enabled?'on':''}`} role="switch" aria-label="登录后自动运行" aria-checked={known&&enabled===true} disabled={disabled||!known} onClick={onToggle}><span/></button>
 </>;
}
