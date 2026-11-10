import {useRef,useState} from 'react';
import {invoke} from '@tauri-apps/api/core';
import {REPAIR_STEPS,createRepairFlight,repairStore,type OptionalIntegrations,type RepairResult,type RepairStep} from './installedRepair';
import {operationError,type OperationError} from './domain';
import './OptionalIntegrationPanel.css';

export function OptionalIntegrationPanel({status,generation,busy,action}:{status?:OptionalIntegrations;generation?:string;busy?:boolean;action?:(fn:()=>Promise<void>)=>Promise<void>}) {
 const flight=useRef<ReturnType<typeof createRepairFlight>|null>(null);
 if(!flight.current)flight.current=createRepairFlight(invoke,undefined,repairStore());
 const [working,setWorking]=useState(false),[result,setResult]=useState<RepairResult|null>(null),[error,setError]=useState<OperationError|null>(null);
 const available=status?.recordState==='available';
 const supported=available&&status.repairSupported===true&&!!generation&&/^[a-f0-9]{64}$/.test(generation);
 const pending=flight.current.current();
 const run=(step?:RepairStep)=>{
  if(busy||working||!supported)return;
  const fn=async()=>{setWorking(true);setError(null);try{setResult(await (step?flight.current!.run(step,generation!):flight.current!.check()))}catch(e){setError(operationError(e));throw e}finally{setWorking(false)}};
  if(action)void action(fn);else void fn().catch(()=>{});
 };
 return <div className="optional-integrations" aria-label="已安装的可选集成">
  <h3>已安装的可选集成</h3>
  <p>{available?'以下是安装器保存的分项结果。记录完成不代表当前系统集成或实际登录已核验。':'尚未取得安装器分项记录，请重新核验或导出诊断。'}</p>
  {available&&status.installationState==='installed_incomplete'&&<p role="status">安装器记录：可选集成待补全。</p>}
  {status?.recoveryState==='pending'?<p role="alert">安装事务恢复待处理。已保存的完成记录不能确认未完成事务的结果，请先检查安装诊断。</p>:status?.recoveryState!=='clear'&&<p role="status">安装事务恢复状态尚未核验。</p>}
  <div className="runtime-list">{REPAIR_STEPS.map(({step,label})=>{
   const detail=available?status.steps?.find(s=>s.step===step):undefined;
   const state=detail?.state==='completed'?'已记录完成':detail?.state==='pending'?'待补全':'分项尚未核验';
   return <div className="runtime optional-integration" key={step}>
    <div><strong>{label}</strong><p>{state}</p>
     {detail&&(detail.stage||detail.code||detail.call||detail.hresult)&&<details><summary>查看步骤详情</summary><small>阶段：{detail.stage||'未知'} · 代码：{detail.code||'未知'}{detail.call&&` · 调用：${detail.call}`}{detail.hresult&&` · HRESULT：${detail.hresult}`}</small></details>}</div>
    <button className="button" disabled={!supported||busy||working||!!pending||detail?.state!=='pending'||status?.recoveryState!=='clear'} aria-describedby="installed-repair-availability" onClick={()=>run(step)}>重试{label}</button>
   </div>;
  })}</div>
  <p id="installed-repair-availability">{supported?'仅重试所选步骤。修复完成仍需分别核验运行版本和实际登录。':'当前安装器尚不支持分项修复，或尚未通过修复能力核验。请保留诊断，使用完整安装包的重试流程补全可选集成。'}</p>
  {result&&<p role={result.state==='completed'?'status':'alert'}>{result.state==='completed'?'所选集成已记录完成，运行生效仍待核验。':result.state==='failed'?'所选集成修复失败。':result.state==='busy'?'安装器正在处理另一项操作。':'本次操作结果尚未确认，请核对同一操作。'} · {result.code} · 操作 {result.operationId} · 阶段 {result.stage}{result.call&&` · 调用 ${result.call}`}{result.hresult&&` · HRESULT ${result.hresult}`}{result.exitCode!==null&&` · 退出码 ${result.exitCode}`}</p>}
  {error&&<p role="alert">{error.message} · {error.code} · 操作 {error.operationId}</p>}
  {pending&&<div><p role="status">待核对操作 {pending.operationId}。核对前不会创建新的修复操作。</p><button className="button" disabled={!supported||busy||working} onClick={()=>run()}>核对上次修复</button></div>}
 </div>;
}
