// Real bundled App Server compatibility test. This is NOT a desktop UI acceptance test.
import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import assert from 'node:assert/strict';
const root=path.resolve(import.meta.dirname,'..');
const run=path.join(root,'.local','desktop-probe-'+Date.now());
fs.mkdirSync(run,{recursive:true});
const workspace=path.join(run,'workspace');fs.mkdirSync(workspace);
const child=spawn(path.join(root,'target/debug/pocket-bridge.exe'),['--original',process.argv[2],'--','app-server'],{cwd:workspace,env:{...process.env,POCKET_RUNTIME_DIR:run},windowsHide:true,stdio:['pipe','pipe','pipe']});
let stderr='';child.stderr.on('data',b=>{stderr=(stderr+b.toString()).slice(-4000)});
function rpc(read,write,label){
 const pending=new Map(),events=[];let next=0;
 createInterface({input:read}).on('line',line=>{let v;try{v=JSON.parse(line)}catch{return}if(v.method){events.push(v);if(events.length>5000)events.shift();}else if(pending.has(v.id)){const p=pending.get(v.id);pending.delete(v.id);clearTimeout(p.timer);v.error?p.reject(new Error(label+': '+JSON.stringify(v.error))):p.resolve(v.result);}});
 return {events,send:v=>write.write(JSON.stringify(v)+'\n'),call:(method,params={})=>new Promise((resolve,reject)=>{const id=++next;const timer=setTimeout(()=>{pending.delete(id);reject(new Error(label+': timeout '+method))},120000);pending.set(id,{resolve,reject,timer});write.write(JSON.stringify({id,method,params})+'\n');}),close:()=>{for(const p of pending.values())clearTimeout(p.timer);pending.clear();}};
}
async function until(f,ms=120000){const end=Date.now()+ms;while(Date.now()<end){const v=await f();if(v)return v;await new Promise(r=>setTimeout(r,100));}throw new Error('condition timed out');}
const desktop=rpc(child.stdout,child.stdin,'desktop-protocol-harness');
let socket,phone;
const checks=[];let threadId;
try {
 const init=await desktop.call('initialize',{clientInfo:{name:'codex_pocket_probe',title:'Codex Pocket probe',version:'0.1.0'},capabilities:{experimentalApi:true}});
 desktop.send({method:'initialized'});checks.push({name:'original initialize',ok:true,userAgent:init.userAgent});
 const descriptor=await until(()=>{const f=fs.readdirSync(run).find(n=>n.endsWith('.json'));return f&&JSON.parse(fs.readFileSync(path.join(run,f)))},15000);
 socket=net.connect(descriptor.port,'127.0.0.1');phone=rpc(socket,socket,'phone-protocol-harness');phone.send({token:descriptor.token});
 const status=await phone.call('pocket/status');assert.equal(status.initialized,true);
 const model=await phone.call('model/list',{limit:2});checks.push({name:'native model catalog',ok:true,count:model.data?.length??0});
 const started=await desktop.call('thread/start',{cwd:workspace,approvalPolicy:'on-request',sandbox:'workspace-write'});threadId=started.thread.id;
 checks.push({name:'original thread identity',ok:true,threadId,model:started.model,modelProvider:started.modelProvider,sandbox:started.sandbox});
 const turn=await desktop.call('turn/start',{threadId,input:[{type:'text',text:'This is a transport smoke test. Reply exactly POCKET_BRIDGE_OK. Do not use tools.'}]});
 const completed=await until(()=>phone.events.find(e=>e.method==='turn/completed'&&e.params?.turn?.id===turn.turn.id));
 assert.equal(completed.params.turn.status,'completed',JSON.stringify(completed.params.turn.error));
 assert(phone.events.some(e=>e.method==='item/agentMessage/delta'&&e.params?.threadId===threadId));checks.push({name:'phone receives live native stream',ok:true});
 const read=await phone.call('thread/read',{threadId,includeTurns:true});assert.equal(read.thread.id,threadId);checks.push({name:'phone reads same thread',ok:true});
 const follow=await phone.call('turn/start',{threadId,input:[{type:'text',text:'Reply exactly PHONE_FOLLOWUP_OK. Do not use tools.'}]});
 const done=await until(()=>desktop.events.find(e=>e.method==='turn/completed'&&e.params?.turn?.id===follow.turn.id));assert.equal(done.params.turn.status,'completed');checks.push({name:'phone follow-up visible to original client transport',ok:true});
 const stop=await phone.call('turn/start',{threadId,input:[{type:'text',text:'Write a very detailed long essay explaining sorting algorithms.'}]});
 await until(()=>phone.events.find(e=>e.method==='item/agentMessage/delta'&&e.params?.turnId===stop.turn.id));
 await phone.call('turn/interrupt',{threadId,turnId:stop.turn.id});
 const interrupted=await until(()=>desktop.events.find(e=>e.method==='turn/completed'&&e.params?.turn?.id===stop.turn.id));assert.equal(interrupted.params.turn.status,'interrupted');checks.push({name:'phone interrupt visible to original client transport',ok:true});
 if(process.argv.includes('--approval')) {
  const a=await desktop.call('turn/start',{threadId,approvalsReviewer:'user',input:[{type:'text',text:'This is a mobile approval transport test. Use the shell tool exactly once to print POCKET_APPROVAL_OK. Explicitly request elevated execution using sandbox_permissions require_escalated and justification Mobile approval transport test. Wait for that approval. Do not run any other command or access any file. After the command, reply DONE.'}]});
  const approval=await until(()=>phone.events.find(e=>e.id&&e.method==='item/commandExecution/requestApproval'&&e.params?.turnId===a.turn.id));
  phone.send({id:approval.id,result:{decision:'decline'}});
  await until(()=>desktop.events.find(e=>e.method==='serverRequest/resolved'&&e.params?.threadId===threadId));
  await until(()=>phone.events.find(e=>e.method==='turn/completed'&&e.params?.turn?.id===a.turn.id));
  checks.push({name:'native command approval declined once from phone',ok:true});
 }
 console.log(JSON.stringify({ok:true,checks},null,2));
 fs.writeFileSync(path.join(root,'docs','desktop-runtime-probe.json'),JSON.stringify({timestamp:new Date().toISOString(),ok:true,scope:'Bundled App Server through desktop-protocol harness, not Electron UI',checks},null,2));
} catch(error) {
 const report={timestamp:new Date().toISOString(),ok:false,error:String(error),checks};
 fs.writeFileSync(path.join(root,'docs','desktop-runtime-probe.json'),JSON.stringify(report,null,2));console.error(JSON.stringify(report,null,2));process.exitCode=1;
 // stderr is kept local: native logs can contain provider details.
 fs.writeFileSync(path.join(run,'native-stderr.log'),stderr);
} finally {phone?.close();desktop.close();socket?.destroy();child.stdin.end();setTimeout(()=>child.kill(),2000).unref();}
