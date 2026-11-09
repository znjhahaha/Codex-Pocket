import { describe,it,expect } from 'vitest';
import {remainingSeconds,pairingComplete,health,connectionReady,type Snapshot} from './domain';
describe('Pairing lifecycle',()=>{
it('expires and never shows a negative countdown',()=>{expect(remainingSeconds(100,101)).toBe(0);expect(remainingSeconds(100,99.1)).toBe(1)});
it('requires a new non-revoked device, not an existing device',()=>{const p={payload:'secret',expiresAt:100,baselineIds:['old']};expect(pairingComplete(p,[{id:'old',name:'old'}])).toBe(false);expect(pairingComplete(p,[{id:'new',name:'new',revoked:true}])).toBe(false);expect(pairingComplete(p,[{id:'new',name:'new'}])).toBe(true)});
it('never promotes stale running status into healthy',()=>{const s={desired:true,supervisor:{updatedAt:10,phase:'running'},relay:{state:'connected'}} as Snapshot;expect(health(s,30)).toBe('unavailable');expect(health({...s,desired:false},30)).toBe('stopped')});
});

describe('Guided connection readiness',()=>{
 const base:Snapshot={computerName:'Fixture',desired:true,supervisor:{phase:'running',updatedAt:100,failures:0},relay:{state:'connected',host:''},devices:[],processes:[],version:'fixture',startup:false,connection:{phase:'connecting',connected:false,available:true,helperUpdatedAt:100,configuredRoutes:1,usableRoutes:1},routes:{routes:[{id:'lan',kind:'lan',url:'ws://192.168.1.20:17891',priority:0}]}};
 it('asks for setup before blaming an absent helper',()=>{expect(health({...base,supervisor:{...base.supervisor,updatedAt:0},connection:{...base.connection!,phase:'setup_required',configuredRoutes:0,usableRoutes:0}},100)).toBe('setup_required')});
 it('allows pairing on a ready LAN without claiming a phone is online',()=>{expect(connectionReady(base,100,'lan')).toBe(true);expect(health(base,100)).not.toBe('connected')});
 it('never treats configured URLs as readiness',()=>{expect(connectionReady({...base,connection:{...base.connection!,available:false,usableRoutes:0}},100,'lan')).toBe(false)});
 it.each([0,80,103])('rejects stale or future helper heartbeat %s',helperUpdatedAt=>{expect(connectionReady({...base,connection:{...base.connection!,helperUpdatedAt}},100,'lan')).toBe(false)});
 it('does not use an unrelated ready route for Cloudflare',()=>{expect(connectionReady({...base,connectivity:{phase:'starting',mode:'quick',enabled:true}},100,'cloudflare')).toBe(false)});
 it('requires a live tunnel and its public URL',()=>{
  const cf={...base,routes:{routes:[{id:'cf',kind:'cloudflare' as const,url:'wss://fixture.trycloudflare.com/pocket',priority:1}]},connectivity:{phase:'tunnel_connected',mode:'quick' as const,enabled:true,publicUrl:'https://fixture.trycloudflare.com'}};
  expect(connectionReady(cf,100,'cloudflare')).toBe(true);
  expect(connectionReady({...cf,connectivity:{...cf.connectivity,phase:'failed'}},100,'cloudflare')).toBe(false);
 });
 it('explicit zero usable routes overrides older connected flags',()=>{expect(connectionReady({...base,connection:{...base.connection!,connected:true,usableRoutes:0}},100)).toBe(false)});
 it('keeps existing connections compatible with snapshots without optional counts',()=>{expect(connectionReady({...base,connection:{phase:'connected',connected:true,helperUpdatedAt:100}},100)).toBe(true)});
 it('rejects expired selected routes',()=>{expect(connectionReady({...base,routes:{routes:[{...base.routes!.routes[0],expiresAt:99}]}},100,'lan')).toBe(false)});
 it('matches the selected method against authoritative ready IDs',()=>{
  const mixed={...base,connection:{...base.connection!,readyRouteIds:['lan']},routes:{routes:[...base.routes!.routes,{id:'relay',kind:'relay' as const,url:'wss://relay.invalid',priority:30}]}};
  expect(connectionReady(mixed,100,'relay')).toBe(false);
  expect(connectionReady(mixed,100,'lan')).toBe(true);
  expect(connectionReady({...mixed,connection:{...mixed.connection,readyRouteIds:[]}},100,'lan')).toBe(false);
 });
 it('accepts unknown configured counts but requires usable evidence',()=>{expect(connectionReady({...base,connection:{...base.connection!,configuredRoutes:null,readyRouteIds:['lan']}},100,'lan')).toBe(true)});
 it('labels ready as distinct from a connected phone',()=>{expect(health({...base,connection:{...base.connection!,phase:'ready'}},100)).toBe('ready')});
 it('cannot report online when actual ready route evidence is explicitly empty',()=>{expect(health({...base,connection:{...base.connection!,connected:true,phase:'connected',usableRoutes:0,readyRouteIds:[]}},100)).not.toBe('connected')});
 it('an outer relay connection without phone authentication is only ready',()=>{expect(health({...base,connection:{...base.connection!,connected:true,phase:'connected',authenticatedPhone:false}},100)).toBe('ready')});
});
it('does not trust future supervisor timestamps or missing heartbeat',()=>{const s={desired:true,supervisor:{updatedAt:100,phase:'running'},relay:{state:'connected'}} as Snapshot;expect(health(s,10)).toBe('unavailable');expect(health({...s,supervisor:{...s.supervisor,updatedAt:0}},1)).toBe('unavailable')});
it('does not show connected when helper heartbeat is stale even if supervisor is alive',()=>{
 const s={desired:true,supervisor:{updatedAt:100,phase:'running'},relay:{state:'connected'},connection:{phase:'connected',helperUpdatedAt:20,connected:true},installation:{consistent:true}} as unknown as Snapshot;
 expect(health(s,100)).not.toBe('connected');
});
it('keeps an inconsistent installation distinct from an offline relay',()=>{
 const s={desired:true,supervisor:{updatedAt:100,phase:'running'},relay:{state:'connected'},connection:{phase:'connected',helperUpdatedAt:100,connected:true},installation:{consistent:false}} as unknown as Snapshot;
 expect(health(s,100)).toBe('failed');
});

describe('Authoritative helper evidence',()=>{
 const snapshot:Snapshot={computerName:'Fixture',desired:true,supervisor:{updatedAt:100,phase:'running',failures:0},relay:{state:'connected',host:'fixture.invalid'},devices:[],processes:[],version:'0.3.0',startup:true,installation:{consistent:true}};
 it('does not report connected when the helper evidence is absent',()=>{
  expect(health(snapshot,100)).toBe('reconnecting');
 });
 it.each([0,20,103])('rejects missing, stale or future helper timestamp %s',(helperUpdatedAt)=>{
  expect(health({...snapshot,connection:{phase:'connected',connected:true,helperUpdatedAt}},100)).toBe('reconnecting');
 });
 it('requires the authoritative helper connected flag',()=>{
  expect(health({...snapshot,connection:{phase:'recovering',connected:false,helperUpdatedAt:100}},100)).toBe('reconnecting');
 });
 it('reports connected with fresh supervisor and helper evidence',()=>{
  expect(health({...snapshot,connection:{phase:'connected',connected:true,helperUpdatedAt:100}},100)).toBe('connected');
 });
 it('surfaces a startup timeout even when no supervisor heartbeat arrived',()=>{
  const waiting={...snapshot,supervisor:{...snapshot.supervisor,updatedAt:0},connection:{phase:'starting',connected:false,helperUpdatedAt:0,desiredChangedAt:100}};
  expect(health(waiting,129)).toBe('starting');
  expect(health(waiting,130)).toBe('failed');
  expect(health({...waiting,connection:{...waiting.connection,phase:'failed'}},130)).toBe('failed');
 });
 it('keeps a lost supervisor heartbeat in recovery rather than startup',()=>{
  expect(health({...snapshot,connection:{phase:'recovering',connected:false,helperUpdatedAt:111}},111)).toBe('reconnecting');
 });
});
