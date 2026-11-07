export type Theme = 'system'|'light'|'dark';
export interface Device {id:string;name:string;createdAt?:number;lastSeenAt?:number;revoked?:boolean}
export interface ProcessInfo {name:string;pid?:number;state:string}
export interface OperationError {code:string;stage:string;message:string;retryable:boolean;operationId:string}
export interface Snapshot {computerName:string; desired:boolean;supervisor:{phase:string;pid?:number;childPid?:number;updatedAt:number;failures:number};relay:{state:string;host:string};devices:Device[];processes:ProcessInfo[];version:string;error?:string;startup:boolean;connection?:{phase:string;helperUpdatedAt:number;connected:boolean;lastSuccessAt?:number|null;desiredChangedAt?:number};installation?:{consistent:boolean};issues?:OperationError[]}
export interface Pairing {payload:string;expiresAt:number;baselineIds:string[]}
export const remainingSeconds=(expires:number,now:number)=> Math.max(0, Math.ceil(expires-now));
export function pairingComplete(pair:Pairing, devices:Device[]) {return devices.some(d=>!d.revoked&&!pair.baselineIds.includes(d.id));}
export const freshHeartbeat=(updated:number,now:number)=>updated>0&&updated<=now+2&&now-updated<=10;
export function health(s:Snapshot,now:number) {
 if(s.installation?.consistent===false)return 'failed';
 if(!s.desired)return 'stopped';
 if(s.connection?.phase==='failed')return 'failed';
 if(!freshHeartbeat(s.supervisor.updatedAt,now)){
  if(s.connection?.phase==='starting'){
   const changed=s.connection.desiredChangedAt??0;
   return changed>0&&changed<=now+2&&now-changed<30?'starting':'failed';
  }
  return s.connection?.phase==='recovering'?'reconnecting':'unavailable';
 }
 // Loading or incomplete snapshots carry no authoritative helper evidence.
 if(!s.connection)return 'reconnecting';
 if(!s.connection.connected||!freshHeartbeat(s.connection.helperUpdatedAt,now))return s.connection.phase==='connecting'?'connecting':'reconnecting';
 return s.supervisor.phase==='running'&&s.relay.state==='connected'?'connected':'reconnecting';
}
export function operationError(value:unknown):OperationError {
 if(value&&typeof value==='object') {const e=value as Partial<OperationError>;if(typeof e.code==='string'&&typeof e.stage==='string'&&typeof e.message==='string'&&typeof e.operationId==='string')return {...e,retryable:e.retryable===true} as OperationError;}
 return {code:'unavailable',stage:'desktop',message:'无法完成此操作，请检查连接诊断。',retryable:true,operationId:''};
}
