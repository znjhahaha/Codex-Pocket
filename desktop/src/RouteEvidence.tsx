import type {Snapshot} from './domain';
const reasons:Record<string,string>={discovery_bind_failed:'发现端口绑定失败',discovery_multicast_failed:'无法加入发现组播',discovery_queue_full:'发现服务繁忙',discovery_stopped:'发现服务已停止',discovery_invalid_service:'发现信息无效',discovery_announcement_unconfirmed:'尚未确认发现广播',discovery_failed:'网络发现失败',listener_bind_failed:'监听地址或端口不可用',network_inventory_failed:'无法读取网卡状态'};
export function RouteEvidence({data}:{data:Snapshot}) {
 const rows=data.routes?.routeStatus||[];
 return <div aria-label="线路证据">
  <p role="status">手机业务会话：{data.connection?.authenticatedPhone===true?'设备认证已通过，会话已建立':'尚未确认建立'}</p>
  {rows.map(row=>{const discovery=row.kind==='discovery'||row.id==='mdns'||row.id.startsWith('mdns-');return <p key={row.id}>
   <strong>{discovery?'局域网发现':row.id==='gateway'?'本机隧道入口':row.kind==='lan'?'局域网直连':'服务器中转'}</strong> · {discovery?(row.phase==='advertising'?'发现广播已发出':'发现尚未确认'):row.innerHandshake?'TLS 握手完成':row.outerConnected?'已连接中转服务器':row.listenerReady?'监听就绪':'尚未就绪'}
   {row.failure&&<small className="error-stage">{reasons[row.failure.code]||'此阶段未完成'} · {row.failure.stage}{row.failure.osCode!==undefined?` · 系统码 ${row.failure.osCode}`:''}{discovery?'。可继续使用配对码内的地址；发现失败不代表直连不可用。':''}</small>}
  </p>})}
  <p className="subtle">监听、连接服务器和 TLS 握手均不等于手机业务会话建立。无法互访时，可能涉及地址变化、防火墙或路由器设备隔离。</p>
 </div>;
}
