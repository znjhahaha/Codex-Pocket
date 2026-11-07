import {test} from 'node:test';
import assert from 'node:assert/strict';
import {execFileSync, spawn} from 'node:child_process';
import net from 'node:net';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
const root=path.resolve(import.meta.dirname,'..');
const bridge=process.env.POCKET_BRIDGE_TEST_BIN || path.join(root,'target/debug/pocket-bridge.exe');
test('app-server startup configuration is a one-shot command, not an RPC stream',()=>{
 const directory=fs.mkdtempSync(path.join(os.tmpdir(),'pocket-startup-'));
 const fake=path.join(directory,'native.cjs');
 fs.writeFileSync(fake,'console.log(JSON.stringify({appServerVersion:"test",configRequirements:{}}));');
 const result=execFileSync(bridge,['--original',process.execPath,'--',fake,'app-server','--print-startup-config'],{input:'',timeout:5000,env:{...process.env,POCKET_RUNTIME_DIR:directory}}).toString();
 assert.deepEqual(JSON.parse(result),{appServerVersion:'test',configRequirements:{}});
 fs.rmSync(directory,{recursive:true,force:true});
});

const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function until(predicate, ms=3000) {
 const deadline=Date.now()+ms;
 while(Date.now()<deadline) { const value=predicate(); if(value)return value; await delay(10); }
 assert.fail('Timed out waiting for bridge condition');
}
function collect(stream) {
 const messages=[]; let rest='';
 stream.setEncoding('utf8');
 stream.on('data',text=>{rest+=text;let i;while((i=rest.indexOf('\n'))>=0){messages.push(JSON.parse(rest.slice(0,i)));rest=rest.slice(i+1);}});
 return messages;
}
async function launch(t, body) {
 const directory=fs.mkdtempSync(path.join(os.tmpdir(),'pocket-bridge-test-'));
 const fake=path.join(directory,'native.cjs');
 fs.writeFileSync(fake,`const readline=require('node:readline'); const send=v=>process.stdout.write(JSON.stringify(v)+'\\n'); send({method:'test/pid',params:{pid:process.pid}}); setTimeout(()=>process.exit(99),15000); ${body}`);
 const child=spawn(bridge,['--original',process.execPath,'--',fake,'app-server'],{env:{...process.env,POCKET_RUNTIME_DIR:directory},stdio:['pipe','pipe','pipe']});
 child.stderr.resume();child.stdin.on('error',()=>{});
 const messages=collect(child.stdout);let ended=false;child.stdout.on('end',()=>{ended=true;});
 const peers=[];
 t.after(async()=>{
  for(const peer of peers)peer.destroy();
  child.stdin.end();
  await delay(100);
  const nativePid=messages.find(v=>v.method==='test/pid')?.params.pid;
  if(nativePid){try{process.kill(nativePid);}catch{}}
  if(child.exitCode===null)child.kill();
  await delay(100);
  fs.rmSync(directory,{recursive:true,force:true});
 });
 const descriptorPath=await until(()=>{const name=fs.readdirSync(directory).find(n=>n.endsWith('.json'));return name&&path.join(directory,name);});
 const descriptor=JSON.parse(fs.readFileSync(descriptorPath,'utf8'));
 async function peer() {
  const socket=net.connect(descriptor.port,'127.0.0.1');peers.push(socket);socket.on('error',()=>{});
  await new Promise(resolve=>socket.once('connect',resolve));
  socket.write(JSON.stringify({token:descriptor.token})+'\n');
  return socket;
 }
 return {child,messages,directory,descriptorPath,peer,stdoutEnded:()=>ended};
}

test('native exit closes bridge stdout while desktop stdin stays open',async t=>{
 const p=await launch(t,'setTimeout(()=>process.exit(23),250);');
 await until(()=>p.child.exitCode!==null,2000);
 assert.equal(p.child.exitCode,23);
 await until(p.stdoutEnded);
 assert.equal(fs.existsSync(p.descriptorPath),false);
});

test('pocket/status reports native version from initialization',async t=>{
 const p=await launch(t,`readline.createInterface({input:process.stdin}).on('line',line=>{const v=JSON.parse(line);send({id:v.id,result:{userAgent:'codex-test/0.164.0',version:'0.164.0'}});});`);
 p.child.stdin.write(JSON.stringify({id:1,method:'initialize',params:{clientInfo:{name:'test',version:'1'}}})+'\n');
 await until(()=>p.messages.find(v=>v.id===1));
 const peer=await p.peer(), replies=collect(peer);
 peer.write(JSON.stringify({id:'status',method:'pocket/status'})+'\n');
 const response=await until(()=>replies.find(v=>v.id==='status'));
 assert.equal(response.result.nativeVersion,'0.164.0');
});

test('descriptor and directory DACLs exclude inherited and other users', {skip:process.platform!=='win32'}, async t=>{
 const p=await launch(t,'process.stdin.resume();');
 const check=execFileSync('powershell.exe',['-NoProfile','-NonInteractive','-Command',`$ErrorActionPreference='Stop'; $sid=[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value; $ok=$true; foreach($p in @($env:POCKET_ACL_FILE,$env:POCKET_ACL_DIR)){$acl=if([System.IO.Directory]::Exists($p)){[System.IO.Directory]::GetAccessControl($p)}else{[System.IO.File]::GetAccessControl($p)}; if(-not $acl.AreAccessRulesProtected){$ok=$false}; foreach($rule in $acl.Access){$r=$rule.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value; if($rule.IsInherited -or $rule.AccessControlType -ne 'Allow' -or $r -notin @($sid,'S-1-5-18')){$ok=$false}}}; [Console]::Write($ok.ToString().ToLower())`],{env:{...process.env,POCKET_ACL_FILE:p.descriptorPath,POCKET_ACL_DIR:p.directory},timeout:5000,windowsHide:true}).toString();
 assert.equal(check,'true');
});

test('slow peer is disconnected and cannot continue native writes',async t=>{
 const p=await launch(t,`readline.createInterface({input:process.stdin}).on('line',line=>{const v=JSON.parse(line);if(v.method==='thread/list'){send({method:'thread/started',params:{thread:{id:'thread'}}});for(let i=0;i<600;i++)send({method:'test/burst',params:{data:'x'.repeat(65536)}});send({method:'test/floodDone'});}if(v.method==='turn/start')send({method:'test/writeReceived'});});`);
 const slow=await p.peer();slow.pause();
 p.child.stdin.write(JSON.stringify({id:2,method:'thread/list'})+'\n');
 await until(()=>p.messages.find(v=>v.method==='test/floodDone'),7000);
 slow.write(JSON.stringify({id:3,method:'turn/start',params:{threadId:'thread',input:[]}})+'\n');
 await delay(250);
 assert.equal(p.messages.some(v=>v.method==='test/writeReceived'),false,'evicted peer must not execute a native write');
 slow.resume();
 await until(()=>slow.destroyed,2000);
 const fresh=await p.peer(), replies=collect(fresh);
 fresh.write(JSON.stringify({id:4,method:'pocket/status'})+'\n');
 assert.equal((await until(()=>replies.find(v=>v.id===4))).result.loadedThreadIds.includes('thread'),true);
});
