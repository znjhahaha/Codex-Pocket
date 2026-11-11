export type Theme = 'system'|'light'|'dark';
export interface Device {id:string;name:string;createdAt?:number;lastSeenAt?:number;revoked?:boolean}
export interface ProcessInfo {name:string;pid?:number;state:string;version?:string;bridgeVersion?:string;helperVersion?:string;bridgeUpdatePending?:boolean}
export interface OperationError {code:string;stage:string;message:string;retryable:boolean;operationId:string;exitCode?:number;osCode?:number;actions?:string[]}
export interface OperationFailure {code:string;stage:string;operationId:string;exitCode?:number|null;osCode?:number|null;recordedAt:number}
export interface StartupFailure {schema:number;operationId:string;pid:number;runtimeGeneration?:string|null;time:number;phase:string;code:string;osCode?:number|null}
export interface BackgroundStartupFailure {schema:1;operationId:string;supervisorPid:number;runtimeGeneration?:string|null;time:number;phase:'tray_verify'|'tray_spawn'|'helper_spawn';code:'tray_verification_failed'|'tray_spawn_failed'|'helper_spawn_failed';osCode?:number;retryable:boolean}
export function diagnosticRuntimeStatus(s:Snapshot,kind:'supervisor'|'helper',now:number) {
 if(s.issues?.some(e=>e.stage===(kind==='supervisor'?'supervisor_read':'helper_control')))return {label:'状态读取失败',good:false};
 const evidence=s.installation?.[kind];
 const updated=kind==='supervisor'?s.supervisor.updatedAt:s.connection?.helperUpdatedAt||0;
 return evidence?.runningVerified&&freshHeartbeat(updated,now)?{label:'运行中',good:true}:{label:'尚未取得状态',good:false};
}
export function operationDetails(e:{code:string;stage:string;operationId:string;exitCode?:number|null;osCode?:number|null}) {
 return `${e.stage} · ${e.code}${typeof e.osCode==='number'?` · OS ${e.osCode}`:''}${typeof e.exitCode==='number'?` · 退出码 ${e.exitCode}`:''} · 操作 ${e.operationId}`;
}
export interface InstalledComponent {version?:string;generation?:string;pid?:number;controlProtocol?:number;runningVerified?:boolean;state:'current'|'restart_required'|'unverified'}
export interface UpgradeStatus {operationId:string;phase:string;failure?:string|null;updatedAt:number;fromGeneration?:string;toGeneration?:string;bridgeRestartRequired?:boolean|null}
export interface InstallationStatus {consistent:boolean;installedVersion?:string;installedGeneration?:string;ui?:InstalledComponent;supervisor?:InstalledComponent;helper?:InstalledComponent;restartRequired?:boolean;upgrade?:UpgradeStatus;optionalIntegrations?:import('./installedRepair').OptionalIntegrations}
export interface BackgroundRecovery {phase:'idle'|'waiting'|'starting'|'exhausted'|'blocked'|'recovered';attempts:number;maxAttempts:3;nextAttemptAt?:number|null;reason?:string|null}
export interface Snapshot {computerName:string; desired:boolean;supervisor:{phase:string;pid?:number;childPid?:number;updatedAt:number;failures:number;startupFailure?:StartupFailure|null;trayStartupFailure?:BackgroundStartupFailure|null;helperSpawnFailure?:BackgroundStartupFailure|null};relay:{state:string;host:string};devices:Device[];processes:ProcessInfo[];version:string;error?:string;startup:boolean;startupStatus?:"pending"|"known"|"unknown";routes?:RouteReport;cloudflareReceipt?:{phase:string;operationId:string;hostname?:string;tunnelId?:string;lastError?:string};connectivity?:ConnectivityStatus;sessionPreferences?:{defaultMode:string;revision:number};helperVersion?:string;connection?:{phase:string;helperUpdatedAt:number;connected:boolean;available?:boolean;authenticatedPhone?:boolean;configuredRoutes?:number|null;usableRoutes?:number;readyRouteIds?:string[];lastSuccessAt?:number|null;desiredChangedAt?:number};installation?:InstallationStatus;backgroundRecovery?:BackgroundRecovery;issues?:OperationError[];operationFailures?:OperationFailure[]}
export interface Pairing {payload:string;expiresAt:number;baselineIds:string[]}
export const remainingSeconds=(expires:number,now:number)=> Math.max(0, Math.ceil(expires-now));
export function pairingComplete(pair:Pairing, devices:Device[]) {return devices.some(d=>!d.revoked&&!pair.baselineIds.includes(d.id));}
export const freshHeartbeat=(updated:number,now:number)=>updated>0&&updated<=now+2&&now-updated<=10;
export function health(s:Snapshot,now:number) {
 if(s.installation?.consistent===false)return 'failed';
 if(!s.desired&&['stopping','waiting_restart'].includes(s.connection?.phase||''))return s.connection!.phase;
 if(s.connection?.phase==='setup_required'||s.connection?.configuredRoutes===0)return 'setup_required';
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
 if(s.connection.usableRoutes===0||s.connection.readyRouteIds?.length===0||s.connection.available===false)return 'reconnecting';
 if((s.connection.phase==='ready'||s.connection.authenticatedPhone===false)&&connectionReady(s,now))return 'ready';
 if(!s.connection.connected||!freshHeartbeat(s.connection.helperUpdatedAt,now))return s.connection.phase==='connecting'?'connecting':'reconnecting';
 return s.supervisor.phase==='running'&&s.relay.state==='connected'?'connected':'reconnecting';
}
// Configuration alone cannot authorize pairing. Older snapshots may use connected
// instead of available, but still require fresh evidence from both processes.
export function connectionReady(s:Snapshot,now:number,method?:Route['kind']) {
 const c=s.connection;
 if(!s.desired||s.installation?.consistent===false||s.supervisor.phase!=='running'||!freshHeartbeat(s.supervisor.updatedAt,now)||!c||!freshHeartbeat(c.helperUpdatedAt,now))return false;
 if(['failed','setup_required','stopped'].includes(c.phase)||c.usableRoutes===0||c.readyRouteIds?.length===0||c.configuredRoutes===0||c.available===false||!(c.available===true||c.connected))return false;
 if(method&&!s.routes?.routes.some(r=>r.kind===method&&(!r.expiresAt||r.expiresAt>now)&&(!c.readyRouteIds||c.readyRouteIds.includes(r.id))))return false;
 if(method==='cloudflare'&&(s.connectivity?.phase!=='tunnel_connected'||!s.connectivity.publicUrl||!s.connectivity.enabled))return false;
 return true;
}
export function operationError(value:unknown):OperationError {
 if(value&&typeof value==='object') {const e=value as Partial<OperationError>;if(typeof e.code==='string'&&typeof e.stage==='string'&&typeof e.message==='string'&&typeof e.operationId==='string')return {...e,retryable:e.retryable===true} as OperationError;}
 return {code:'unavailable',stage:'desktop',message:'无法完成此操作，请检查连接诊断。',retryable:true,operationId:''};
}

export interface Route {id:string;kind:'lan'|'cloudflare'|'relay';url:string;priority:number;expiresAt?:number}
export interface RoutePreferences {routes:Route[];policy:'automatic'|'lan'|'public';autoLan?:boolean;lan?:{bind:string;mdns:boolean};gateway?:{bind:string}}
export interface RouteHealth {id:string;kind:string;phase:string;listenerReady?:boolean;outerConnected?:boolean;innerHandshake?:boolean;activeSessions?:number;failure?:{stage:string;code:string;osCode?:number}}
export interface RouteReport {routes:Route[];policy?:string;routePreferences?:RoutePreferences;routeStatus?:RouteHealth[]}
export interface ConnectivityStatus {phase:string;mode:'off'|'quick'|'fixed';enabled:boolean;hostname?:string;publicUrl?:string;lastError?:string;operationId?:string}
