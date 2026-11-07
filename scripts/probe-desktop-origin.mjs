import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import {createInterface} from 'node:readline';
import assert from 'node:assert/strict';
const root=path.resolve(import.meta.dirname,'..');
const dir=path.join(root,'.local/desktop-ui-probe/runtimes');
const descriptors=fs.readdirSync(dir).filter(n=>n.endsWith('.json'));
assert.equal(descriptors.length,1,'Expected one isolated desktop runtime');
const desc=JSON.parse(fs.readFileSync(path.join(dir,descriptors[0])));
const socket=net.connect(desc.port,'127.0.0.1');const pending=new Map();const events=[];let seq=0;
const send=v=>socket.write(JSON.stringify(v)+'\n');send({token:desc.token});
createInterface({input:socket}).on('line',s=>{const v=JSON.parse(s);if(v.method)events.push(v);else if(pending.has(v.id)){const p=pending.get(v.id);pending.delete(v.id);clearTimeout(p.timer);v.error?p.reject(new Error(JSON.stringify(v.error))):p.resolve(v.result);}});
const call=(method,params={})=>new Promise((resolve,reject)=>{const id=++seq;const timer=setTimeout(()=>{pending.delete(id);reject(new Error('timeout '+method))},90000);pending.set(id,{resolve,reject,timer});send({id,method,params});});
let report={timestamp:new Date().toISOString(),ok:false,checks:[]};
try {
 const loaded=await call('thread/loaded/list'); const matches=[];
 for(const id of loaded.data??[]){let v;try{v=await call('thread/read',{threadId:id,includeTurns:true});}catch{continue;}
  const turns=v.thread?.turns??[];
  if(turns.some(t=>(t.items??[]).some(i=>i.type==='userMessage'&&JSON.stringify(i.content).includes('DESKTOP_PHONE_TEST'))))matches.push(v.thread);
 }
 assert.equal(matches.length,1,'Expected precisely one user-created DESKTOP_PHONE_TEST chat');
 const thread=matches[0];report.threadId=thread.id;
 assert.ok(JSON.stringify(thread).includes('DESKTOP_PHONE_TEST'));
 report.checks.push({name:'Read user-created original desktop chat with original native ID',ok:true});
 const turn=await call('turn/start',{threadId:thread.id,input:[{type:'text',text:'This is a remote phone continuation of the same desktop conversation. Reply exactly PHONE_CONTINUED_DESKTOP_OK. Do not use tools.'}]});
 report.turnId=turn.turn.id;
 const deadline=Date.now()+180000;let done;
 while(Date.now()<deadline){done=events.find(e=>e.method==='turn/completed'&&e.params?.turn?.id===turn.turn.id);if(done)break;await new Promise(r=>setTimeout(r,100));}
 assert.equal(done?.params.turn.status,'completed','Remote follow-up must finish');
 const read=await call('thread/read',{threadId:thread.id,includeTurns:true});assert.equal(read.thread.id,thread.id);
 assert.ok(JSON.stringify(read.thread).includes('PHONE_CONTINUED_DESKTOP_OK'));
 assert.ok(events.some(e=>e.method==='item/agentMessage/delta'&&e.params?.threadId===thread.id));
 report.checks.push({name:'Remote follow-up streamed and persisted on same desktop-created thread',ok:true});
 report.ok=true;report.scope='Actual original desktop UI created initial turn. Remote bridge continued original ID without account login. Desktop visual observation recorded separately.';
} catch(e){report.error=String(e);process.exitCode=1;}
finally{fs.writeFileSync(path.join(root,'docs/desktop-origin-probe.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));for(const p of pending.values())clearTimeout(p.timer);socket.destroy();}
