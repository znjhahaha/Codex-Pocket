import {useEffect,useRef,useState} from 'react';
import {invoke} from '@tauri-apps/api/core';
import {RelaySetup} from './RelaySetup';
import {ConnectionSettings} from './ConnectionSettings';
import {Check,Computer,Info,LoaderCircle,ShieldCheck,Wifi} from './PocketIcons';
import type {OperationError,Snapshot} from './domain';
import {automaticLanBind,guideError,guideReadiness,methods,prepareAutomaticLan,prepareRoute,lanCandidate,validLanBind,type ConnectionMethod,type LanInterface} from './connectionGuide';

type Props={data:Snapshot;now:number;busy:boolean;action:(fn:()=>Promise<void>)=>Promise<void>;refresh:()=>Promise<Snapshot|null>;onExit:()=>void;onPair:(method:ConnectionMethod)=>Promise<void>};
export function ConnectionWizard({data,now,busy,action,refresh,onExit,onPair}:Props) {
 const [advanced,setAdvanced]=useState(false),[adjustLan,setAdjustLan]=useState(false);
 const [step,setStep]=useState<'choose'|'route'|'codex'>('choose'),[method,setMethod]=useState<ConnectionMethod>('cloudflare');
 const [status,setStatus]=useState<'idle'|'applying'|'waiting'|'error'>('idle'),[issue,setIssue]=useState<OperationError|null>(null),[activated,setActivated]=useState(false),[startedAt,setStartedAt]=useState(0);
 const [interfaces,setInterfaces]=useState<LanInterface[]>([]),[probeBusy,setProbeBusy]=useState(true),[probeError,setProbeError]=useState('');
 const [lanBind,setLanBind]=useState(''),[relayUrl,setRelayUrl]=useState(''),[relayMode,setRelayMode]=useState<'url'|'ssh'>('url'),[firewall,setFirewall]=useState('');
 const generation=useRef(0),alive=useRef(true),request=useRef(false),probeRequest=useRef<Promise<LanInterface[]>|null>(null),savedBind=useRef(data.routes?.routePreferences?.lan?.bind),adjusted=useRef(false);
 savedBind.current=data.routes?.routePreferences?.lan?.bind;
 const renderGeneration=generation.current,relayCurrent=()=>alive.current&&generation.current===renderGeneration;
 const probe=async()=>{
  setProbeBusy(true);setProbeError('');
  if(!probeRequest.current)probeRequest.current=invoke<LanInterface[]>('detect_lan');
  const pending=probeRequest.current;
  try{const rows=await pending;if(alive.current){setInterfaces(rows);if(!adjusted.current)setLanBind(automaticLanBind(savedBind.current,rows))}}
  catch{if(alive.current){setInterfaces([]);if(!adjusted.current)setLanBind('');setProbeError(guideError(Error('network_detection_failed')).message)}}
  finally{if(probeRequest.current===pending)probeRequest.current=null;if(alive.current)setProbeBusy(false)}
 };
 useEffect(()=>{alive.current=true;void probe();return()=>{alive.current=false;generation.current++}},[]);
 useEffect(()=>{if(!adjusted.current)setLanBind(automaticLanBind(savedBind.current,interfaces))},[data.routes?.routePreferences?.lan?.bind,interfaces]);
 // Screen entry detects networks read-only; only explicit actions configure routes.
 useEffect(()=>{if(!activated)return;void refresh();const timer=setInterval(()=>void refresh(),1000);return()=>clearInterval(timer)},[activated,refresh]);
 const run=async(fn:(current:()=>boolean)=>Promise<void>)=>{
  if(busy||request.current)return;
  request.current=true;const id=generation.current,current=()=>alive.current&&generation.current===id;
  setIssue(null);setStatus('applying');
  try{await action(async()=>{try{await fn(current)}catch(e){if(current()){setIssue(guideError(e));setStatus('error')}}})}
  finally{request.current=false;if(current())setStatus(previous=>previous==='applying'?'idle':previous)}
 };
 const markActivated=()=>{setActivated(true);setStartedAt(Date.now()/1000);setStatus('waiting')};
 const latestSnapshot=async()=>{const latest=await refresh();if(!latest)throw Error('route_preferences_unavailable');return latest};
 const select=(value:ConnectionMethod)=>{if(busy)return;generation.current++;setMethod(value);setStep('route');setStatus('idle');setIssue(null);setActivated(false);setFirewall('')};
 const start=()=>void run(async current=>{
  const latest=await latestSnapshot();if(!current())return;
  await prepareRoute(method,{lanBind,relayUrl,autoLan:!adjusted.current},latest,async(cmd,args)=>{
   const result=await invoke(cmd,args);
   if(current()&&(cmd==='set_remote'||cmd==='configure_routes'&&latest.desired))markActivated();
   return result;
  },current);
  if(current())markActivated();
 });
 const prepareLan=async(current:()=>boolean)=>{const latest=await latestSnapshot();if(current())await prepareAutomaticLan({lanBind,autoLan:!adjusted.current},latest,(cmd,args)=>invoke(cmd,args),current)};
 const activateSsh=async()=>{if(!relayCurrent())return;const latest=await latestSnapshot();if(!relayCurrent())return;if(!latest.desired)await invoke('set_remote',{enabled:true});if(relayCurrent())markActivated()};
 const useExisting=()=>void run(async current=>{const latest=await latestSnapshot();if(!current())return;await prepareAutomaticLan({lanBind,autoLan:!adjusted.current},latest,(cmd,args)=>invoke(cmd,args),current);if(!current())return;if(!latest.desired)await invoke('set_remote',{enabled:true});if(current())markActivated()});
 const readiness=guideReadiness(data,now,method),ready=activated&&readiness.canPair,selectedReady=activated&&readiness.selectedReady,lanOnly=activated&&readiness.lanOnly;
 const runtimeMode=data.sessionPreferences?.defaultMode==='cli'?'cli':'desktop',runtimeReady=data.processes.some(p=>p.name===runtimeMode&&p.state==='在线');
 const pendingFailure=activated&&status!=='applying'&&!selectedReady&&(method==='cloudflare'&&data.connectivity?.phase==='failed'?'tunnel_failed':data.connection?.phase==='failed'?'route_not_ready':now-startedAt>90?'guide_timeout':'');
 const shownIssue=issue||(pendingFailure?guideError(Error(pendingFailure)):null);
 const waitingText=method==='lan'?'正在等待局域网就绪':method==='cloudflare'?'正在等待 Cloudflare 隧道':'正在等待中转就绪';
 const readyText=lanOnly?'仅当前局域网可用':method==='lan'?'局域网已就绪':method==='cloudflare'?'Cloudflare 隧道已就绪':'中转已就绪';
 const edit=()=>{generation.current++;setActivated(false);setIssue(null);setStatus('idle')};
 const back=()=>{generation.current++;setIssue(null);setStatus('idle');if(step==='codex')setStep('route');else{setActivated(false);setStep('choose')}};
 const retryRead=()=>void run(async current=>{await refresh();if(current()){setIssue(null);setStartedAt(Date.now()/1000);setStatus('waiting')}});
 const lanAvailable=validLanBind(lanBind);
 const lanPanel=<div className="automatic-lan">
  <div className="automatic-lan-heading"><Wifi size={19}/><strong>局域网自动连接</strong></div>
  <p>{probeBusy?'正在检测电脑网络…':lanAvailable?`已自动匹配 ${lanBind}。同一网络优先使用局域网，离开后自动回退到已配置的公网。`:'局域网暂不可用。请连接 Wi-Fi 或网线后重新检测，也可以继续配置公网。'}</p>
  {probeError&&<p role="status">{probeError}</p>}
  <div className="route-actions"><button className="button" disabled={busy||probeBusy} onClick={()=>{adjusted.current=false;void probe()}}>重新检测网络</button><button className="text-link" disabled={busy||activated} onClick={()=>setAdjustLan(value=>!value)}>高级网络调整</button></div>
  {(adjustLan||(!probeBusy&&!lanAvailable))&&<div className="route-form"><label>电脑网络<select aria-label="电脑网络" value={lanBind} disabled={busy||activated} onChange={e=>{adjusted.current=true;setLanBind(e.target.value)}}><option value="">请选择检测到的网络</option>{interfaces.map(row=><option key={row.address} value={lanCandidate(row.address,savedBind.current||lanBind)}>{row.name} · {row.address}</option>)}{lanBind&&!interfaces.some(row=>lanCandidate(row.address,savedBind.current||lanBind)===lanBind)&&<option value={lanBind}>{lanBind}</option>}</select></label><label>电脑局域网地址<input value={lanBind} disabled={busy||activated} placeholder="192.168.1.20:43821" onChange={e=>{adjusted.current=true;setLanBind(e.target.value)}}/></label></div>}
  {activated&&data.routes?.routePreferences?.lan?.bind&&<div className="wizard-firewall"><button className="button" disabled={busy} onClick={()=>void run(async current=>{const result=await invoke<{configured:boolean}>('allow_lan_firewall');if(current()){setFirewall(result.configured?'已允许此局域网端口通过专用网络防火墙。':'授权未完成，请重试。');setStatus('waiting')}})}>允许局域网通过防火墙</button><p>点击后 Windows 将请求管理员授权（UAC），仅允许已保存端口、专用网络和本地子网。若手机在同一 Wi-Fi 仍无法连接，可完成此步骤。</p>{firewall&&<p role="status">{firewall}</p>}</div>}
 </div>;
 return <section className="connection-wizard" aria-label="连接引导">
  <div className="wizard-heading"><div><span className="eyebrow">连接引导 · {step==='choose'?'01':step==='route'?'02':'03'} / 04</span><h1>{advanced?'高级连接设置':step==='choose'?'选择公网连接（可选）':step==='route'?(method==='lan'?'自动连接局域网':methods.find(item=>item.id===method)!.title):'打开 Codex，准备扫码'}</h1><p>{step==='choose'?'局域网会自动检测并优先使用。选择外出时使用的公网，也可以暂不配置。':step==='route'?'自动切换局域网与已配置的公网，确认实际可用线路后再扫码。':'电脑保持开机，在 Codex 中完成登录，再用手机扫码。'}</p></div><button className="button" onClick={onExit}>取消引导</button></div>
  <ol className="wizard-steps" aria-label="连接步骤">{['公网可选','配置并验证','打开 Codex','扫码配对'].map((label,index)=><li key={label} aria-current={index===(step==='choose'?0:step==='route'?1:2)?'step':undefined}><span>0{index+1}</span>{label}</li>)}</ol>
  {shownIssue&&<div className="notice error" role="alert"><Info size={18}/><span>{shownIssue.message}{shownIssue.operationId&&<small className="error-stage">操作 {shownIssue.operationId}</small>}</span></div>}
  {step==='choose'?<><div className="method-cards">{methods.map(item=><button key={item.id} className="method-card" aria-label={item.title} disabled={busy} onClick={()=>select(item.id)}><span className="setting-icon">{item.id==='lan'?<Wifi size={26}/>:item.id==='cloudflare'?<ShieldCheck size={26}/>:<Computer size={26}/>}</span><strong>{item.title}</strong><p>{item.description}</p><small>{item.hint}</small></button>)}</div><p className="wizard-note">{probeBusy?'正在自动检测局域网…':lanAvailable?`局域网已自动匹配 ${lanBind}，保存时启用。`:'当前未检测到可用局域网，可继续配置公网。'} 已有公网设置会保留。</p>{probeError&&<p className="wizard-note" role="status">{probeError}</p>}</>:advanced?<div className="wizard-content"><p>完成后返回此连接引导，确认局域网或所选公网实际就绪再扫码。</p>{!data.desired&&<div className="route-actions"><button className="button" disabled={busy||probeBusy} onClick={useExisting}>启用电脑助手</button></div>}<ConnectionSettings data={data} busy={busy} action={fn=>run(async()=>{await fn()})}/><button className="button primary" disabled={busy} onClick={()=>{setAdvanced(false);setIssue(null)}}>返回连接引导</button></div>:<div className="wizard-content">
   {step==='route'&&<>
    {method==='lan'&&<><h2>自动连接局域网</h2><p>手机和电脑连接同一个路由器即可。暂不新增公网，已有公网设置继续保留。</p>{lanPanel}{!activated&&<div className="route-actions"><button className="button primary" disabled={busy||probeBusy||!lanAvailable} onClick={start}>保存并启用自动连接</button></div>}</>}
    {method==='cloudflare'&&<><h2>先使用临时公网链接</h2><p>同一网络优先使用局域网；离开后自动回退 Cloudflare。电脑需要能访问 Cloudflare，临时地址可能在重启后变化。</p>{lanPanel}{!activated&&<div className="route-actions"><button className="button primary" disabled={busy||probeBusy} onClick={start}>开启临时公网连接</button>{data.connectivity?.enabled&&<button className="button" disabled={busy||probeBusy} onClick={useExisting}>检查并使用已有公网连接</button>}</div>}<details className="wizard-advanced"><summary>固定域名或账户 Token（可选）</summary><p>需要固定地址时，可在高级设置中使用已有固定连接，或配置自己的 Cloudflare 账户和域名。</p><button className="button" disabled={busy} onClick={()=>setAdvanced(true)}>打开高级连接设置</button></details></>}
    {method==='relay'&&<><h2>使用自己的中转服务</h2><p>同一网络自动优先使用局域网，离开后使用自有中转。填写已有中转，或主动部署到自己的服务器。</p>{lanPanel}<div className="theme-picker relay-mode"><button aria-pressed={relayMode==='url'} disabled={busy||activated} className={relayMode==='url'?'selected':''} onClick={()=>setRelayMode('url')}>已有中转地址</button><button aria-pressed={relayMode==='ssh'} disabled={busy||activated} className={relayMode==='ssh'?'selected':''} onClick={()=>setRelayMode('ssh')}>通过 SSH 部署</button></div>{relayMode==='url'?<><div className="route-form"><label>中转地址<input value={relayUrl} disabled={busy||activated} autoComplete="off" placeholder="wss://relay.example.com" onChange={e=>setRelayUrl(e.target.value)}/></label></div><p>填写 wss://主机 或 wss://主机/pocket。房间和认证信息由电脑管理。</p>{!activated&&<div className="route-actions"><button className="button primary" disabled={busy||probeBusy||!relayUrl} onClick={start}>保存并启用中转</button></div>}</>:<RelaySetup busy={busy||activated||probeBusy} action={fn=>run(async()=>{await fn()})} guided beforeActivate={()=>prepareLan(relayCurrent)} onActivated={activateSsh} isCurrent={relayCurrent}/>}</>}
    {status==='applying'&&<div className="wizard-status" role="status"><LoaderCircle size={18} className="spin"/><div><strong>正在处理，请稍候</strong><p>返回或取消后不再执行后续步骤，已提交的操作会在后台完成。</p></div></div>}
    {activated&&status!=='applying'&&<div className={`wizard-status ${ready?'ready':''}`} role="status">{ready?<Check size={20}/>:<LoaderCircle size={18} className="spin"/>}<div><strong>{ready?readyText:waitingText}</strong><p>{lanOnly?'所选公网尚未就绪，现在只能在同一局域网扫码和连接。离开此网络前请完成公网配置。':ready?'线路已可用。局域网与已配置的公网保持自动切换。':'正在读取线路实际状态，暂时不能扫码。'}</p>{method==='cloudflare'&&data.connectivity?.publicUrl&&<code>{data.connectivity.publicUrl}</code>}<p>已配置 {data.connection?.configuredRoutes??'待确认'} 条 · 可用 {data.connection?.usableRoutes??'待确认'} 条</p></div></div>}
    {shownIssue&&<div className="route-actions">{shownIssue.code==='route_preferences_unavailable'?<button className="button" disabled={busy} onClick={retryRead}>重新检查</button>:!(method==='relay'&&relayMode==='ssh')&&<button className="button primary" disabled={busy||probeBusy||method==='lan'&&!lanAvailable} onClick={start}>重试连接</button>}{activated&&shownIssue.code!=='route_preferences_unavailable'&&<button className="button" disabled={busy} onClick={retryRead}>重新检查</button>}{method==='relay'&&relayMode==='ssh'&&<p>请使用 SSH 服务器检查、部署或使用此中转按钮重试。</p>}</div>}{activated&&<div className="route-actions"><button className="button" disabled={busy} onClick={edit}>编辑连接设置</button></div>}
   </>}
   {step==='codex'&&<><h2>{runtimeMode==='cli'?'当前使用 Codex CLI':'在电脑上打开 Codex 桌面'}</h2><p>{runtimeMode==='cli'?'高级设置已选择 CLI。请保持所选 Codex CLI 运行。':'打开 Codex 桌面并登录账号，手机会继续电脑上的会话。'}</p>{lanOnly&&<div className="notice" role="status"><Wifi size={18}/><span>仅当前局域网可用。请保持手机和电脑连接同一个路由器，离开前完成公网设置。</span></div>}<div className="route-actions"><button className="button" disabled={busy} onClick={()=>void run(async current=>{await invoke('open_codex');if(current()){setStartedAt(Date.now()/1000);setStatus('waiting')}})}>打开 Codex 桌面</button><button className="button" disabled={busy} onClick={retryRead}>重新检查</button></div><div className="wizard-status" role="status">{runtimeReady?<Check size={20}/>:<LoaderCircle size={18} className="spin"/>}<div><strong>{runtimeReady?'Codex 已就绪':'等待 Codex 就绪'}</strong><p>{runtimeReady?'在手机安装并打开 Codex Pocket，选择扫码配对。':'启动命令已提交不代表完成，请等待确认运行状态。'}</p>{!ready&&<p>当前线路暂不可用，请返回上一页检查连接。</p>}</div></div></>}
   <div className="wizard-navigation"><button className="button" onClick={back}>上一步</button>{step==='route'?<button className="button primary" disabled={busy||!ready} onClick={()=>{setStep('codex');setIssue(null)}}>{lanOnly?'下一步：仅局域网连接':'下一步：打开 Codex'}</button>:<button className="button primary" disabled={busy||!ready||!runtimeReady} onClick={()=>void onPair(readiness.pairMethod)}>生成配对二维码</button>}</div>
  </div>}
 </section>;
}
