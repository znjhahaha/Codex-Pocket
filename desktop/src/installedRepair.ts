export const REPAIR_STEPS = [
 {step:'shortcut_0',label:'开始菜单入口'}, {step:'shortcut_1',label:'旧桌面入口清理'},
 {step:'shortcut_2',label:'旧登录快捷方式清理'}, {step:'startup',label:'登录后台任务'},
] as const;
export type RepairStep = typeof REPAIR_STEPS[number]['step'];
export interface OptionalIntegrationStep {step:RepairStep;state:'completed'|'pending'|'unverified';stage?:string;code?:string;call?:string|null;hresult?:string|null}
export interface OptionalIntegrations {
 recordState:'available'|'unverified';reason?:string;installationState?:'installed'|'installed_incomplete'|'unknown';
 recoveryState:'clear'|'pending'|'unknown';repairSupported:boolean;repairCheckAvailable?:boolean;steps:OptionalIntegrationStep[];
}
export interface RepairOperation {step:RepairStep;expectedGeneration:string;operationId:string}
export interface RepairResult extends RepairOperation {state:'completed'|'failed'|'recovery-needed'|'unknown'|'busy';code:string;stage:string;call?:string|null;hresult?:string|null;exitCode:number|null;recoveryState:string}
export interface RepairStore {read:()=>RepairOperation|null;write:(value:RepairOperation|null)=>void}
export function repairStore():RepairStore {
 const key='pocket.installRepair.pending';
 return {read:()=>{try{const value=JSON.parse(localStorage.getItem(key)||'null') as RepairOperation|null;
  return value&&REPAIR_STEPS.some(s=>s.step===value.step)&&/^[a-f0-9]{64}$/.test(value.expectedGeneration)&&/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value.operationId)?{step:value.step,expectedGeneration:value.expectedGeneration,operationId:value.operationId}:null;
 }catch{return null}},write:value=>{try{if(value)localStorage.setItem(key,JSON.stringify(value));else localStorage.removeItem(key)}catch{/* Keep the operation guarded in memory if storage is unavailable. */}}};
}
export function createRepairFlight(send:(command:string,args:Record<string,unknown>)=>Promise<unknown>,id:()=>string=()=>crypto.randomUUID(),store?:RepairStore) {
 let pending=false;
 let current=store?.read()||null;
 const save=(value:RepairOperation|null)=>{current=value;store?.write(value)};
 const dispatch=async(request:RepairOperation,check=false)=>{
  if(pending)throw {code:'operation_busy'};
  pending=true;save(request);
  try{const value=await send('repair_installed_integration',{request:{...request,...(check?{checkRepair:true}:{})}});
   const state=(value as Partial<RepairResult>|null)?.state;
   if(state!=='unknown'&&state!=='recovery-needed')save(null);
   return value as RepairResult;
  }catch(error){
   const code=(error as {code?:string}|null)?.code;
   if(code&&(['repair_unsupported','invalid_params','operation_busy','installation_changed','installation_invalid'].includes(code)||(code==='repair_capability_unknown'&&!check)))save(null);
   throw error;
  }finally{pending=false}
 };
 return {busy:()=>pending,current:()=>current,check:async()=>{
  if(!current)throw {code:'reconciliation_required'};
  return dispatch(current,true);
 },run:async(step:RepairStep,generation:string)=>{
  if(!REPAIR_STEPS.some(s=>s.step===step)||! /^[a-f0-9]{64}$/.test(generation))throw {code:'invalid_params'};
  if(pending)throw {code:'operation_busy'};
  if(current)throw {code:'reconciliation_required'};
  return dispatch({step,expectedGeneration:generation,operationId:id()});
 }};
}
