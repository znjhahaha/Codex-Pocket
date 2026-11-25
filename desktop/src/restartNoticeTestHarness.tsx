import {StrictMode} from 'react';
import {flushSync} from 'react-dom';
import {createRoot} from 'react-dom/client';
import {UpdatesPanel} from './ManagementPanels';
import {UpgradeRecoveryPanel} from './UpgradeRecoveryPanel';
import type {Snapshot} from './domain';

// This fixture mounts only the two panels; every native call stays in the browser.
const root=createRoot(document.getElementById('root')!);
const nativeFrame=window.requestAnimationFrame.bind(window);
const frames=new Map<number,FrameRequestCallback>();let sequence=0;
window.requestAnimationFrame=callback=>{frames.set(++sequence,callback);return sequence;};
window.cancelAnimationFrame=id=>{frames.delete(id);};
const noop=()=>{};
const fixture={
 calls:[] as {operationId:string;noticeId:string}[],visible:true,visibility:'visible',fail:false,
 holdVisibility:false,holdAck:false,visibilityChecks:0,
 resolveVisibility:undefined as undefined|((visible:boolean)=>void),resolveAck:undefined as undefined|(()=>void),
 render(snapshot:Snapshot,view:'updates'|'recovery'|'both'|'none'='updates',hidden=false){
  flushSync(()=>root.render(<StrictMode><div style={hidden?{display:'none'}:undefined}>
   {(view==='updates'||view==='both')&&<UpdatesPanel status={snapshot.updates} busy={false} onCheck={noop} onAutomatic={noop}/>}
   {(view==='recovery'||view==='both')&&<UpgradeRecoveryPanel snapshot={snapshot} now={100} busy={false} onAction={noop} onRefresh={noop} onDiagnostics={noop}/>}
  </div></StrictMode>));
 },
 async frame(){
  await new Promise<void>(resolve=>nativeFrame(time=>{
   const pending=[...frames.values()];frames.clear();pending.forEach(callback=>callback(time));resolve();
  }));
 },
};
Object.defineProperty(document,'visibilityState',{get:()=>fixture.visibility});
Object.assign(window,{noticeFixture:fixture,__TAURI_INTERNALS__:{
 metadata:{currentWindow:{label:'main'}},
 async invoke(command:string,args:{operationId:string;noticeId:string}){
  if(command==='plugin:window|is_visible'){
   fixture.visibilityChecks++;
   return fixture.holdVisibility?new Promise<boolean>(resolve=>{fixture.resolveVisibility=resolve;}):fixture.visible;
  }
  if(command==='acknowledge_restart_notice'){
   fixture.calls.push(args);
   if(fixture.fail)throw new Error('Receipt write unavailable');
   if(fixture.holdAck)return new Promise<void>(resolve=>{fixture.resolveAck=resolve;});
   return;
  }
  throw new Error(`Unexpected native call: ${command}`);
 },
}});
export type NoticeFixture=typeof fixture;
