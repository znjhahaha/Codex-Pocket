import {describe,it,expect} from 'vitest';
import {prepareRoute,relayBaseUrl,guideError,lanCandidate,automaticLanBind,guideReadiness} from './connectionGuide';
import type {Snapshot} from './domain';

const snapshot:Snapshot={computerName:'Fixture',desired:false,supervisor:{phase:'stopped',updatedAt:0,failures:0},relay:{state:'stopped',host:''},devices:[],processes:[],version:'fixture',startup:false,routes:{routes:[],routePreferences:{policy:'automatic',lan:{bind:'192.168.1.2:17891',mdns:false},gateway:{bind:'127.0.0.1:17892'},routes:[{id:'existing',kind:'relay',url:'wss://existing.invalid/pocket',priority:30}]}}};
describe('Guided route activation',()=>{
 it('preserves the configured port when changing network',()=>expect(lanCandidate('10.0.0.2','192.168.1.20:43821')).toBe('10.0.0.2:43821'));
 it('saves LAN preferences before enabling, preserving other routes and mDNS',async()=>{
  const calls:{command:string;args:unknown}[]=[];
  await prepareRoute('lan',{lanBind:'192.168.1.20:17891'},snapshot,async(command,args)=>{calls.push({command,args})},()=>true);
  expect(calls.map(c=>c.command)).toEqual(['configure_routes','set_remote']);
  expect(calls[0].args).toEqual({preferences:{...snapshot.routes!.routePreferences,policy:'automatic',autoLan:true,lan:{bind:'192.168.1.20:17891',mdns:false}}});
  expect(calls[1].args).toEqual({enabled:true});
 });
 it('stops the sequence when saving fails',async()=>{
  const calls:string[]=[];
  await expect(prepareRoute('lan',{lanBind:'192.168.1.20:17891'},snapshot,async cmd=>{calls.push(cmd);throw Error('fixture')},()=>true)).rejects.toThrow('fixture');
  expect(calls).toEqual(['configure_routes']);
 });
 it('does not continue a cancelled in-flight save',async()=>{
  let current=true;const calls:string[]=[];
  await prepareRoute('lan',{lanBind:'192.168.1.20:17891'},snapshot,async cmd=>{calls.push(cmd);current=false},()=>current);
  expect(calls).toEqual(['configure_routes']);
 });
 it('starts quick Cloudflare only after configuring the gateway and enabling the helper',async()=>{
  const calls:{cmd:string;args:any}[]=[];
  await prepareRoute('cloudflare',{},snapshot,async(cmd,args)=>{calls.push({cmd,args})},()=>true);
  expect(calls.map(c=>c.cmd)).toEqual(['configure_routes','set_remote','configure_connectivity']);
  expect(calls[0].args.preferences.policy).toBe('automatic');
  expect(calls[2].args).toEqual({request:{mode:'quick',enabled:true,gatewayUrl:'http://127.0.0.1:17892'}});
 });
 it('does not restart an existing running helper',async()=>{
  const calls:string[]=[];
  await prepareRoute('lan',{lanBind:'192.168.1.20:17891'},{...snapshot,desired:true},async cmd=>{calls.push(cmd)},()=>true);
  expect(calls).toEqual(['configure_routes']);
 });
 it('hands the relay base URL to the backend without fabricating credentials',async()=>{
  const calls:{cmd:string;args:any}[]=[];
  await prepareRoute('relay',{relayUrl:'wss://relay.invalid/pocket'},snapshot,async(cmd,args)=>{calls.push({cmd,args})},()=>true);
  expect(calls.map(c=>c.cmd)).toEqual(['configure_routes','configure_relay','set_remote']);
  expect(calls[0].args.preferences.policy).toBe('automatic');
  expect(calls[0].args.preferences.routes).toEqual(snapshot.routes!.routePreferences!.routes);
  expect(calls[1].args).toEqual({url:'wss://relay.invalid/pocket'});
 });
 it.each(['lan','cloudflare','relay'] as const)('requires authoritative preferences before any %s mutation',async method=>{
  const calls:string[]=[];
  await expect(prepareRoute(method,{lanBind:'192.168.1.20:43821',relayUrl:'wss://relay.invalid'},{...snapshot,routes:undefined},async cmd=>{calls.push(cmd)},()=>true)).rejects.toThrow('route_preferences_unavailable');
  expect(calls).toEqual([]);
 });
 it.each(['cloudflare','relay'] as const)('prepares automatic LAN alongside %s without dropping prior public routes',async method=>{
  const calls:{cmd:string;args:any}[]=[];
  await prepareRoute(method,{lanBind:'10.0.0.2:43821',relayUrl:'wss://relay.invalid'},snapshot,async(cmd,args)=>{calls.push({cmd,args})},()=>true);
  expect(calls[0]).toEqual({cmd:'configure_routes',args:{preferences:{...snapshot.routes!.routePreferences,policy:'automatic',autoLan:true,lan:{bind:'10.0.0.2:43821',mdns:false}}}});
 });
 it('skipping public preserves all existing public config and only saves automatic LAN',async()=>{
  const calls:{cmd:string;args:any}[]=[];
  await prepareRoute('lan',{lanBind:'192.168.1.20:43821'},snapshot,async(cmd,args)=>{calls.push({cmd,args})},()=>true);
  expect(calls.map(c=>c.cmd)).toEqual(['configure_routes','set_remote']);
  expect(calls[0].args.preferences.routes).toEqual(snapshot.routes!.routePreferences!.routes);
 });
 it.each(['cloudflare','relay'] as const)('allows %s with no detected LAN',async method=>{
  const calls:{cmd:string;args:any}[]=[];
  await prepareRoute(method,{relayUrl:'wss://relay.invalid'},snapshot,async(cmd,args)=>{calls.push({cmd,args})},()=>true);
  expect(calls[0].args.preferences.policy).toBe('automatic');
  expect(calls[0].args.preferences.lan).toEqual(snapshot.routes!.routePreferences!.lan);
 });
 it('skipping public without a LAN candidate does not mutate',async()=>{
  const calls:string[]=[];
  await expect(prepareRoute('lan',{},snapshot,async cmd=>{calls.push(cmd)},()=>true)).rejects.toThrow('auto_lan_unavailable');
  expect(calls).toEqual([]);
 });
 it('does not save automatic LAN if the relay URL is invalid',async()=>{
  const calls:string[]=[];
  await expect(prepareRoute('relay',{lanBind:'10.0.0.2:43821',relayUrl:'wss://host?token=secret'},snapshot,async cmd=>{calls.push(cmd)},()=>true)).rejects.toThrow('invalid_relay_url');
  expect(calls).toEqual([]);
 });
 it('reports unread preferences before validating a relay address',async()=>{await expect(prepareRoute('relay',{}, {...snapshot,routes:undefined},async()=>{},()=>true)).rejects.toThrow('route_preferences_unavailable')});
 it('enables ongoing LAN detection even when initially offline',async()=>{
  const calls:{cmd:string;args:any}[]=[];
  await prepareRoute('cloudflare',{}, {...snapshot,routes:{routes:[],routePreferences:{...snapshot.routes!.routePreferences!,lan:undefined}}},async(cmd,args)=>{calls.push({cmd,args})},()=>true);
  expect(calls[0].args.preferences.autoLan).toBe(true);
  expect(calls[0].args.preferences.lan).toBeNull();
 });
 it('explicit advanced network adjustment disables automatic rebinding',async()=>{
  const calls:{cmd:string;args:any}[]=[];
  await prepareRoute('lan',{lanBind:'10.0.0.2:43821',autoLan:false},snapshot,async(cmd,args)=>{calls.push({cmd,args})},()=>true);
  expect(calls[0].args.preferences.autoLan).toBe(false);
  expect(calls[0].args.preferences.policy).toBe('automatic');
 });
});
describe('Automatic LAN detection',()=>{
 const rows=[{name:'Wi-Fi',address:'192.168.1.20'},{name:'Ethernet',address:'10.0.0.2'}];
 it('keeps a saved active address and port',()=>expect(automaticLanBind('10.0.0.2:43821',rows)).toBe('10.0.0.2:43821'));
 it('replaces a stale address with a current candidate while retaining its port',()=>expect(automaticLanBind('192.168.0.50:43821',rows)).toBe('192.168.1.20:43821'));
 it('does not trust a stale bind without any current network',()=>expect(automaticLanBind('192.168.0.50:43821',[])).toBe(''));
 it('ignores non-private or malformed detected addresses',()=>expect(automaticLanBind(undefined,[{name:'loopback',address:'127.0.0.1'},{name:'public',address:'203.0.113.1'},...rows])).toBe('192.168.1.20:43821'));
 it('uses the agent default port when the stored port is invalid',()=>expect(automaticLanBind('192.168.0.50:999999',rows)).toBe('192.168.1.20:43821'));
});
describe('Automatic route fallback guidance',()=>{
 const now=100;
 const mixed:Snapshot={...snapshot,desired:true,supervisor:{phase:'running',updatedAt:now,failures:0},connection:{phase:'ready',available:true,connected:false,helperUpdatedAt:now,usableRoutes:1,readyRouteIds:['lan']},routes:{routes:[{id:'lan',kind:'lan',url:'ws://192.168.1.20:43821',priority:0},{id:'cf',kind:'cloudflare',url:'wss://cf.invalid/pocket',priority:10},{id:'relay',kind:'relay',url:'wss://relay.invalid',priority:30}]},connectivity:{mode:'quick',enabled:true,phase:'failed'}};
 it.each(['cloudflare','relay'] as const)('%s failure can pair using only actual ready LAN evidence',method=>{expect(guideReadiness(mixed,now,method)).toEqual({selectedReady:false,lanReady:true,canPair:true,lanOnly:true,pairMethod:'lan'})});
 it('no real LAN or public evidence cannot unlock pairing',()=>expect(guideReadiness({...mixed,connection:{...mixed.connection!,readyRouteIds:[],usableRoutes:0}},now,'cloudflare').canPair).toBe(false));
 it('a ready public route stays independently verified when LAN is lost',()=>{expect(guideReadiness({...mixed,connection:{...mixed.connection!,readyRouteIds:['relay']}},now,'relay')).toEqual({selectedReady:true,lanReady:false,canPair:true,lanOnly:false,pairMethod:'relay'})});
});
describe('Plain Chinese guidance',()=>{
 it.each(['ws://host/pocket','wss://user:secret@host/pocket','wss://host/pocket?token=secret','wss://host/room/id','wss://host/pocket#secret'])('rejects unsafe or non-base relay input %s',url=>expect(()=>relayBaseUrl(url)).toThrow());
 it('accepts a base endpoint and trims whitespace',()=>expect(relayBaseUrl(' wss://relay.invalid/pocket ')).toBe('wss://relay.invalid/pocket'));
 it.each(['wss://relay.invalid','wss://relay.invalid/','wss://relay.invalid/pocket/'])('accepts root and pocket base endpoint %s',url=>expect(relayBaseUrl(url)).toMatch(/^wss:\/\/relay.invalid/));
 it('rejects an address already containing a tunnel room',()=>expect(()=>relayBaseUrl('wss://relay.invalid/v1/tunnel/room/phone')).toThrow());
 it.each(['wss://relay.invalid?','wss://relay.invalid#','wss://relay.invalid/\npocket','wss://relay.invalid/v1/tunnel/../..'])('rejects empty metadata and normalized malformed endpoint %s',url=>expect(()=>relayBaseUrl(url)).toThrow());
 it('explains a missing route without blaming the helper',()=>expect(guideError({code:'relay_unconfigured',stage:'routes',message:'helper_unavailable',operationId:'fixture'}).message).toContain('中转地址'));
 it('never echoes arbitrary exception text that could contain a password',()=>expect(guideError(Error('password=secret')).message).not.toContain('secret'));
});
