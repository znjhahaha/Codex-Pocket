import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import {createInterface} from 'node:readline';
import assert from 'node:assert/strict';
const root=path.resolve(import.meta.dirname,'..');
const dir=path.join(root,'.local/desktop-ui-probe/runtimes');
const names=fs.readdirSync(dir).filter(n=>n.endsWith('.json'));
assert.equal(names.length,1,'Expected one live test desktop bridge');
const desc=JSON.parse(fs.readFileSync(path.join(dir,names[0])));
const socket=net.connect(desc.port,'127.0.0.1');const pending=new Map();const events=[];let seq=0;
const send=v=>socket.write(JSON.stringify(v)+'\n');
send({token:desc.token});
createInterface({input:socket}).on('line',s=>{let v;try{v=JSON.parse(s)}catch{return}if(v.method){events.push(v);}else if(pending.has(v.id)){const p=pending.get(v.id);pending.delete(v.id);clearTimeout(p.timer);v.error?p.reject(new Error(JSON.stringify(v.error))):p.resolve(v.result);}});
const call=(method,params={})=>new Promise((resolve,reject)=>{const id=++seq;const timer=setTimeout(()=>{pending.delete(id);reject(new Error('timeout '+method))},120000);pending.set(id,{resolve,reject,timer});send({id,method,params});});
async function until(f,ms=180000){const end=Date.now()+ms;while(Date.now()<end){const r=f();if(r)return r;await new Promise(r=>setTimeout(r,100));}throw new Error('event timeout; methods='+[...new Set(events.map(e=>e.method))].join(','));}
const checks=[];
try{
 const status=await call('pocket/status');assert.equal(status.initialized,true);checks.push({name:'Original desktop performed native initialization',ok:true});
 const workspace=path.join(root,'.local/desktop-ui-probe/workspace');fs.mkdirSync(workspace,{recursive:true});
 const start=await call('thread/start',{cwd:workspace,approvalPolicy:'on-request',sandbox:'workspace-write'});const threadId=start.thread.id;
 console.log(JSON.stringify({threadId,model:start.model,provider:start.modelProvider,stage:'desktop native thread created'}));
 await call('thread/name/set',{threadId,name:'Pocket 桌面兼容性测试（可删除）'});
 const turn=await call('turn/start',{threadId,input:[{type:'text',text:'This is a local transport and tool compatibility test. Use the shell tool to write a new file proof.txt in the current working directory containing exactly POCKET_DESKTOP_TOOL_OK, then read it to verify. Do not touch any other files or use network. Reply DONE after verification.'}]});
 const done=await until(()=>events.find(e=>e.method==='turn/completed'&&e.params?.turn?.id===turn.turn.id));
 assert.equal(done.params.turn.status,'completed',JSON.stringify(done.params.turn.error));
 assert.equal(fs.readFileSync(path.join(workspace,'proof.txt'),'utf8').trim(),'POCKET_DESKTOP_TOOL_OK');
 checks.push({name:'Original desktop runtime executes sandboxed shell tool through bridge',ok:true});
 const read=await call('thread/read',{threadId,includeTurns:true});assert.equal(read.thread.id,threadId);
 checks.push({name:'Same native desktop thread readable remotely',ok:true});
 if(process.argv.includes('--desktop-create')) {
  const before=new Set((await call('thread/loaded/list',{})).data??[]);
  const spawned=await call('turn/start',{threadId,input:[{type:'text',text:'Desktop API compatibility test. Use the Codex app create_thread tool (discover it with functions if needed) to create ONE separate local projectless test task. Title: Pocket desktop origin acceptance. Prompt: Reply exactly DESKTOP_CREATED_OK; do not use any tools. Set directoryName to pocket-acceptance. Keep the default model. Do not use shell, direct network, or CLI to create it. If the desktop create_thread tool is unavailable, explicitly say UNAVAILABLE. Return the new native threadId.'}]});
  const parentDone=await until(()=>events.find(e=>e.method==='turn/completed'&&e.params?.turn?.id===spawned.turn.id));
  assert.equal(parentDone.params.turn.status,'completed');
  const loaded=await call('thread/loaded/list',{});
  const newIds=(loaded.data??[]).filter(id=>!before.has(id)&&id!==threadId);
  const children=[];
  for(const id of newIds){const data=await call('thread/read',{threadId:id,includeTurns:true});if(JSON.stringify(data).includes('DESKTOP_CREATED_OK'))children.push(data.thread);}
  assert.equal(children.length,1,'Expected one original-desktop-created test chat; new IDs='+newIds.join(','));
  const child=children[0];
  const next=await call('turn/start',{threadId:child.id,input:[{type:'text',text:'Reply exactly PHONE_CONTINUED_DESKTOP_OK. Do not use tools.'}]});
  const continued=await until(()=>events.find(e=>e.method==='turn/completed'&&e.params?.turn?.id===next.turn.id));
  assert.equal(continued.params.turn.status,'completed');
  checks.push({name:'Original desktop API creates chat; phone continues same native identity',ok:true,threadId:child.id});
 }
 const nativeMethods=[...new Set(events.map(e=>e.method))];
 const report={timestamp:new Date().toISOString(),ok:true,checks,threadId,model:start.model,provider:start.modelProvider,nativeMethods,scope:'Original running desktop process initiated runtime; remote client created disposable thread. Desktop-origin UI initiation still requires its own check.'};
 fs.writeFileSync(path.join(root,'docs/desktop-app-probe.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
}catch(e){const report={timestamp:new Date().toISOString(),ok:false,error:String(e),checks};fs.writeFileSync(path.join(root,'docs/desktop-app-probe.json'),JSON.stringify(report,null,2));console.error(JSON.stringify(report));process.exitCode=1;}
finally{for(const p of pending.values())clearTimeout(p.timer);socket.destroy();}
