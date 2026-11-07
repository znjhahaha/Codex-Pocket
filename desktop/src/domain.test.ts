import { describe,it,expect } from 'vitest';
import {remainingSeconds,pairingComplete,health,type Snapshot} from './domain';
describe('Pairing lifecycle',()=>{
it('expires and never shows a negative countdown',()=>{expect(remainingSeconds(100,101)).toBe(0);expect(remainingSeconds(100,99.1)).toBe(1)});
it('requires a new non-revoked device, not an existing device',()=>{const p={payload:'secret',expiresAt:100,baselineIds:['old']};expect(pairingComplete(p,[{id:'old',name:'old'}])).toBe(false);expect(pairingComplete(p,[{id:'new',name:'new',revoked:true}])).toBe(false);expect(pairingComplete(p,[{id:'new',name:'new'}])).toBe(true)});
it('never promotes stale running status into healthy',()=>{const s={desired:true,supervisor:{updatedAt:10,phase:'running'},relay:{state:'connected'}} as Snapshot;expect(health(s,30)).toBe('unavailable');expect(health({...s,desired:false},30)).toBe('stopped')});
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
