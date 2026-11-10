import {connectionReady,operationError,type OperationError,type Route,type Snapshot} from './domain';

export type ConnectionMethod=Route['kind'];
export type GuideInvoke=(command:string,args?:Record<string,unknown>)=>Promise<unknown>;
export const methods=[
 {id:'cloudflare',title:'Cloudflare 隧道',description:'离开局域网时自动使用隧道。先用临时链接，无需填写账户。',hint:'无需自备服务器'},
 {id:'relay',title:'自有服务器中转',description:'离开局域网时自动使用自己的中转。填写已有地址或通过 SSH 部署。',hint:'适合已有服务器'},
 {id:'lan',title:'暂不配置公网',description:'先使用自动检测的局域网。手机和电脑需连接同一个路由器。',hint:'以后可随时添加公网'}
] as const;

export function relayBaseUrl(input:string) {
 if(input.length>2048||/[\x00-\x1f\x7f?#]/.test(input)||input.includes('/v1/tunnel'))throw Error('invalid_relay_url');
 let url:URL;try{url=new URL(input.trim())}catch{throw new Error('invalid_relay_url')}
 if(url.protocol!=='wss:'||!url.hostname||url.username||url.password||url.search||url.hash||!['/','/pocket','/pocket/'].includes(url.pathname))throw new Error('invalid_relay_url');
 return url.toString();
}
export function savedRelayUrl(snapshot:Snapshot) {
 for(const route of snapshot.routes?.routePreferences?.routes||[]) {
  if(route.kind!=='relay')continue;
  // Pairing endpoints include a room; the editable field only accepts a base URL.
  const base=route.url.replace(/\/v1\/tunnel\/[A-Za-z0-9_-]+\/phone$/, '');
  try{return relayBaseUrl(base)}catch{/* Ignore malformed legacy entries. */}
 }
 return '';
}
// Route endpoints may contain pairing rooms. Only display an allowlisted base.
export function routeDisplayUrl(route:Pick<Route,'kind'|'url'>) {
 try {
  const url=new URL(route.url);
  if(!['ws:','wss:','tcp:','tls:','http:','https:'].includes(url.protocol)||!url.hostname)return '地址待确认';
  return `${url.protocol}//${url.host}${route.kind!=='lan'&&(url.pathname==='/pocket'||url.pathname.startsWith('/pocket/'))?'/pocket':''}`;
 }catch{return '地址待确认'}
}
export function routeReadIssue(snapshot:Snapshot) {
 return snapshot.issues?.find(issue=>issue.stage==='routes_read')
  ??snapshot.issues?.find(issue=>['helper_control','physical_state_path','physical_supervisor_status','installation'].includes(issue.stage));
}
export function requireRoutePreferences(snapshot:Snapshot) {
 const issue=routeReadIssue(snapshot);
 if(issue)throw issue;
 const prefs=snapshot.routes?.routePreferences;
 if(!prefs||!Array.isArray(prefs.routes))throw Error('route_preferences_unavailable');
 return prefs;
}
export function requiresRouteRead(issue:OperationError) {
 return issue.code==='route_preferences_unavailable'||['routes_read','helper_control','physical_state_path','physical_supervisor_status','installation'].includes(issue.stage);
}
export function validLanBind(bind:string) {
 const match=bind.match(/^(\d+\.\d+\.\d+\.\d+):(\d+)$/);
 if(!match)return false;
 const octets=match[1].split('.').map(Number),port=Number(match[2]);
 return octets.every(n=>n>=0&&n<=255)&&((octets[0]===10)||(octets[0]===172&&octets[1]>=16&&octets[1]<=31)||(octets[0]===192&&octets[1]===168))&&port>0&&port<=65535;
}
export function lanCandidate(address:string,savedBind?:string) {
 const storedPort=Number(savedBind?.match(/:(\d+)$/)?.[1]);
 const port=storedPort>0&&storedPort<=65535?storedPort:43821;
 return `${address}:${port}`;
}
export interface LanInterface {name:string;address:string;kind?:'wifi'|'ethernet'|'virtual'|'tunnel'|'unknown';automatic?:boolean}
export function lanInterfaceLabel(row:LanInterface) {
 const kind=row.kind?({wifi:'Wi-Fi',ethernet:'以太网',virtual:'虚拟网卡',tunnel:'VPN / 隧道',unknown:'类型待确认'}[row.kind]):'';
 return `${row.name} · ${row.address}${kind?` · ${kind}`:''}${row.automatic===false?'（仅手动选择）':''}`;
}
export function automaticLanBind(savedBind:string|undefined,interfaces:LanInterface[]) {
 const eligible=interfaces.filter(row=>row.automatic!==false);
 if(savedBind&&validLanBind(savedBind)&&eligible.some(row=>row.address===savedBind.split(':')[0]))return savedBind;
 return eligible.map(row=>lanCandidate(row.address,savedBind)).find(validLanBind)||'';
}
export async function prepareAutomaticLan(values:{lanBind?:string;autoLan?:boolean},snapshot:Snapshot,call:GuideInvoke,current:()=>boolean,required=false) {
 if(!current())return;
 const prefs=requireRoutePreferences(snapshot);
 if(required&&!validLanBind(values.lanBind||''))throw Error('auto_lan_unavailable');
 await call('configure_routes',{preferences:{...prefs,policy:'automatic',autoLan:values.autoLan??true,lan:validLanBind(values.lanBind||'')?{bind:values.lanBind,mdns:prefs.lan?.mdns??true}:prefs.lan||null,gateway:prefs.gateway||{bind:'127.0.0.1:17892'}}});
}
export async function prepareRoute(method:ConnectionMethod,values:{lanBind?:string;relayUrl?:string;autoLan?:boolean},snapshot:Snapshot,call:GuideInvoke,current:()=>boolean) {
 if(!current())return;
 requireRoutePreferences(snapshot);
 const url=method==='relay'?relayBaseUrl(values.relayUrl||''):undefined;
 await prepareAutomaticLan(values,snapshot,call,current,method==='lan');
 if(!current())return;
 if(method==='relay')await call('configure_relay',{url});
 if(!current())return;
 if(!snapshot.desired)await call('set_remote',{enabled:true});
 if(!current())return;
 if(method==='cloudflare')await call('configure_connectivity',{request:{mode:'quick',enabled:true,gatewayUrl:`http://${snapshot.routes!.routePreferences!.gateway?.bind||'127.0.0.1:17892'}`}});
}

export function guideReadiness(snapshot:Snapshot,now:number,method:ConnectionMethod) {
 const selectedReady=connectionReady(snapshot,now,method),lanReady=connectionReady(snapshot,now,'lan');
 return {selectedReady,lanReady,canPair:selectedReady||lanReady,lanOnly:method!=='lan'&&!selectedReady&&lanReady,pairMethod:selectedReady?method:'lan' as ConnectionMethod};
}

const messages:Record<string,string>={
 start_required:'电脑助手尚未启动。请启用所选线路后等待助手就绪。',
 deployment_failed:'服务器部署未完成。请检查服务器信息和端口检查结果后重试。',
 route_preferences_unavailable:'线路设置尚未读取，请重新检查。',
 network_detection_failed:'局域网检测未完成。可重新检测或在高级设置调整网络，也可以继续配置公网。',
 auto_lan_unavailable:'未检测到可用局域网。请连接 Wi-Fi 或网线，在 Windows 网络设置中确认网络后重新检测；也可以配置公网。',
 setup_required:'请先完成自动局域网设置，也可以配置公网连接，然后再扫码。',
 relay_unconfigured:'还没有中转地址。请填写已有中转地址，或选择 SSH 部署。',
 route_not_ready:'线路还没有准备好。请检查所选网络或中转地址，然后重新检查。',
 helper_unavailable:'电脑助手尚未就绪。请完成线路设置并等待启动，再重新检查。',
 operation_busy:'上一项操作仍在处理中，请稍候再试。',
 invalid_relay_url:'请填写 wss://主机 或 wss://主机/pocket，不要包含房间、令牌或账号密码。',
 invalid_lan_bind:'请选择电脑的局域网地址，格式如 192.168.1.20:17891。',
 guide_timeout:'连接准备超时。请检查网络后重新检查，或返回选择其他方式。',
 tunnel_failed:'Cloudflare 隧道没有连接成功。请检查电脑网络，再点击重试。',
 runtime_not_ready:'Codex 尚未就绪。请打开 Codex 桌面并完成登录，然后重新检查。',
 firewall_not_configured:'防火墙授权未完成。可以再次点击允许按钮，在 Windows 提示中授权。'
};
export function guideError(value:unknown):OperationError {
 const code=value instanceof Error&&messages[value.message]?value.message:undefined;
 const e=code?{...operationError(null),code}:operationError(value);
 return {...e,message:messages[e.code]||e.message};
}
