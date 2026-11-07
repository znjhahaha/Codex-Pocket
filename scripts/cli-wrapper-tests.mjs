import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { classifyLaunch } from './cli-wrapper.mjs';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const tempRoot=path.join(root,'.local','cli-install-tests'); mkdirSync(tempRoot,{recursive:true});
const fixtures=mkdtempSync(path.join(tempRoot,'run-'));
const installer=path.join(root,'scripts','cli-install.ps1');
const official=path.join(process.env.APPDATA,'npm');
const pwsh=process.env.CLI_TEST_PWSH || 'pwsh';
const knownEntries=['codex','codex.cmd','codex.ps1'];
const cleanEnv={...process.env};
for (const key of ['CODEX_EXEC_SERVER_URL','OPENAI_FEDERATION_RULE_ID','OPENAI_IDENTITY_TOKEN_FILE','OPENAI_WORKLOAD_IDENTITY_CONTEXT']) delete cleanEnv[key];

test('ordinary and resume/fork are ensured, with native display and cwd options',()=>{
  for(const args of [[],['hello'],['--no-alt-screen','hi'],['resume','test-id'],['resume','--last'],['fork','--all'],['-C','a b','resume','id'],['--cd=a b','hello'],['--','--no-daemon']])
    assert.equal(classifyLaunch(args,{}).ensure,true,JSON.stringify(args));
});
test('explicit and potentially incompatible choices are not changed or ensured',()=>{
  for(const args of [['--no-daemon'],['-c','key=value'],['-cfoo=bar'],['--config=foo=bar'],['--profile','x'],['-px'],['--sandbox','read-only'],['--enable','feature'],['--search'],['--remote','unix://'],['--strict-config'],['--dangerously-bypass-approvals-and-sandbox'],['--new-unknown-option']])
    assert.equal(classifyLaunch(args,{}).ensure,false,JSON.stringify(args));
});
test('management commands bypass preflight and positional prompts are not scanned as command flags',()=>{
  for(const args of [['exec','--no-alt-screen'],['app-server'],['login'],['remote-control'],['--help'],['resume','--help'],['--version']])
    assert.equal(classifyLaunch(args,{}).ensure,false,JSON.stringify(args));
  assert.equal(classifyLaunch(['--cd','--no-daemon','hello'],{}).ensure,true);
  assert.equal(classifyLaunch(['say --no-daemon please'],{}).ensure,true);
});
test('executor and workload markers exclude even when present but empty',()=>{
  for(const key of ['CODEX_EXEC_SERVER_URL','OPENAI_FEDERATION_RULE_ID','OPENAI_IDENTITY_TOKEN_FILE','OPENAI_WORKLOAD_IDENTITY_CONTEXT'])
    assert.equal(classifyLaunch([],{[key]:''}).ensure,false,key);
});

const fakeModule = [
"import fs from 'node:fs';",
"const args=process.argv.slice(2);",
"fs.appendFileSync(process.env.CLI_FIXTURE_LOG,JSON.stringify(args)+'\\n');",
"if(args[0]==='app-server' && args[1]==='daemon'){",
" const started=fs.existsSync(process.env.CLI_FIXTURE_STARTED);",
" if(args[2]==='start'){fs.writeFileSync(process.env.CLI_FIXTURE_STARTED,'yes');console.log(JSON.stringify({status:'started'}));}",
" else if(process.env.CLI_FIXTURE_FAILURE==='1'){process.exitCode=9;}",
" else console.log(JSON.stringify({status:(started||process.env.CLI_FIXTURE_RUNNING==='1')?'running':'notRunning',cliVersion:'0.164.0',appServerVersion:'0.166.0'}));",
"} else {",
" let input='';for await(const chunk of process.stdin)input+=chunk;",
" console.log(JSON.stringify({args,cwd:process.cwd(),sentinel:process.env.CLI_FIXTURE_SENTINEL,input,entry:process.argv[1]}));",
" process.exitCode=Number(process.env.CLI_FIXTURE_EXIT||0);",
"}",
].join('\n');

function fixture(name) {
  const dir=path.join(fixtures,name+' with spaces');mkdirSync(dir);
  const moduleDir=path.join(dir,'node_modules','@openai','codex');mkdirSync(path.join(moduleDir,'bin'),{recursive:true});
  writeFileSync(path.join(moduleDir,'package.json'),JSON.stringify({name:'@openai/codex',version:'0.164.0',type:'module'}));
  writeFileSync(path.join(moduleDir,'bin','codex.js'),fakeModule);
  const originals={};
  const expectedHashes={codex:'6EE176B43F96B2294C8E2265D599F829E161B2C2AD34CB2B8FBF5F836FD34B8C','codex.cmd':'C54DB6755E710C39703F7C37512F9E35ED41042D8080558D2B84B8D2694323C3','codex.ps1':'0C149DB80ED0BF442C810146B0AD0163B74982FE4542D673F56C354D7B8229CB'};
  for(const name of knownEntries){
    const backup=path.join(official,'.codex2-cli',name+'.original');
    originals[name]=readFileSync(existsSync(backup)?backup:path.join(official,name));
    assert.equal(createHash('sha256').update(originals[name]).digest('hex').toUpperCase(),expectedHashes[name]);
    writeFileSync(path.join(dir,name),originals[name]);
  }
  const home=path.join(dir,'home');mkdirSync(home);
  return {dir,originals,env:{...cleanEnv,CODEX_HOME:home,CLI_FIXTURE_LOG:path.join(dir,'events.jsonl'),CLI_FIXTURE_STARTED:path.join(dir,'started'),CLI_FIXTURE_SENTINEL:'third-party-placeholder',CLI_FIXTURE_RUNNING:'1'},home};
}
function install(f,flags=[]){
  return spawnSync(pwsh,['-NoProfile','-File',installer,'-LauncherDirectory',f.dir,...flags],{encoding:'utf8',env:cleanEnv,timeout:30000});
}
function invoke(f,args=[],options={}){
  return spawnSync(process.execPath,[path.join(f.dir,'.codex2-cli','cli-wrapper.mjs'),...args],{encoding:'utf8',cwd:root,env:{...f.env,...options.env},input:options.input||'',timeout:30000});
}
function lines(f){return existsSync(f.env.CLI_FIXTURE_LOG)?readFileSync(f.env.CLI_FIXTURE_LOG,'utf8').trim().split('\n').filter(Boolean).map(JSON.parse):[];}
function assertExit(r,n=0){assert.equal(r.status,n,r.stderr+'\n'+r.stdout);}

test('installer dry-run, apply, idempotence and byte-exact restore',()=>{
  const f=fixture('lifecycle'); const dry=install(f);assertExit(dry);
  assert.equal(existsSync(path.join(f.dir,'.codex2-cli')),false);
  for(const n of knownEntries)assert.deepEqual(readFileSync(path.join(f.dir,n)),f.originals[n]);
  assertExit(install(f,['-Apply']));
  assert.equal(existsSync(path.join(f.dir,'.codex2-cli','cli-wrapper.mjs')),true);
  const installed=knownEntries.map(n=>readFileSync(path.join(f.dir,n)));
  assertExit(install(f,['-Apply']));
  knownEntries.forEach((n,i)=>assert.deepEqual(readFileSync(path.join(f.dir,n)),installed[i]));
  assertExit(install(f,['-Uninstall']));
  knownEntries.forEach((n,i)=>assert.deepEqual(readFileSync(path.join(f.dir,n)),installed[i]));
  assertExit(install(f,['-Uninstall','-Apply']));
  for(const n of knownEntries)assert.deepEqual(readFileSync(path.join(f.dir,n)),f.originals[n]);
});
test('installer refuses updated launchers and damaged backups without overwriting',()=>{
  const f=fixture('drift');assertExit(install(f,['-Apply']));
  writeFileSync(path.join(f.dir,'codex.cmd'),'new external launcher');
  assert.notEqual(install(f,['-Uninstall','-Apply']).status,0);
  assert.equal(readFileSync(path.join(f.dir,'codex.cmd'),'utf8'),'new external launcher');
  const damaged=fixture('damaged-backup');assertExit(install(damaged,['-Apply']));
  const installedBefore=knownEntries.map(n=>readFileSync(path.join(damaged.dir,n)));
  writeFileSync(path.join(damaged.dir,'.codex2-cli','codex.cmd.original'),'invalid backup');
  assert.notEqual(install(damaged,['-Uninstall','-Apply']).status,0);
  knownEntries.forEach((n,i)=>assert.deepEqual(readFileSync(path.join(damaged.dir,n)),installedBefore[i]));
});
test('partial install restores prior entries and keeps a usable recovery manifest',()=>{
  const f=fixture('locked-entry');
  const script=path.join(f.dir,'locked.ps1');
  writeFileSync(script,"param($Installer,$Dir)\n$held=[IO.File]::Open((Join-Path $Dir 'codex.cmd'),[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)\ntry { & $Installer -LauncherDirectory $Dir -Apply; exit 7 } catch { exit 0 } finally { $held.Dispose() }\n");
  assertExit(spawnSync(pwsh,['-NoProfile','-File',script,installer,f.dir],{encoding:'utf8',env:cleanEnv,timeout:30000}));
  for(const n of knownEntries)assert.deepEqual(readFileSync(path.join(f.dir,n)),f.originals[n]);
  assert.equal(existsSync(path.join(f.dir,'.codex2-cli','manifest.json')),true);
  assertExit(install(f,['-Apply']));
  assertExit(install(f,['-Uninstall','-Apply']));
  for(const n of knownEntries)assert.deepEqual(readFileSync(path.join(f.dir,n)),f.originals[n]);
});
test('wrapper reuses running daemon and preserves argv cwd environment stdin exit',()=>{
  const f=fixture('running');assertExit(install(f,['-Apply']));
  const args=['hello world','', 'quote"here','中文','a&b','%CLI_ARG_SENTINEL%','C:\\ends\\'];
  const r=invoke(f,args,{input:'stdin-data\n',env:{CLI_FIXTURE_EXIT:'23'}});assertExit(r,23);
  const result=JSON.parse(r.stdout.trim());
  assert.deepEqual(result.args,args);assert.equal(result.cwd,root);assert.equal(result.sentinel,'third-party-placeholder');assert.equal(result.input,'stdin-data\n');
  assert.equal(result.entry,path.join(f.dir,'node_modules','@openai','codex','bin','codex.js'));
  assert.deepEqual(lines(f),[['app-server','daemon','version'],args]);
  assert.match(r.stderr,/0\.164\.0.*0\.166\.0/);
});
test('wrapper cold-starts locally and verifies readiness, refusing saved remote preference',()=>{
  const f=fixture('cold');assertExit(install(f,['-Apply']));
  const r=invoke(f,['resume','disposable'],{env:{CLI_FIXTURE_RUNNING:'0'}});assertExit(r);
  assert.deepEqual(lines(f).slice(0,3),[['app-server','daemon','version'],['app-server','daemon','start'],['app-server','daemon','version']]);
  const blocked=fixture('remote-pref');assertExit(install(blocked,['-Apply']));
  mkdirSync(path.join(blocked.home,'app-server-daemon'));
  writeFileSync(path.join(blocked.home,'app-server-daemon','settings.json'),JSON.stringify({remoteControlEnabled:true}));
  assert.notEqual(invoke(blocked,[],{env:{CLI_FIXTURE_RUNNING:'0'}}).status,0);
  assert.deepEqual(lines(blocked),[['app-server','daemon','version']]);
});
test('ensure failure does not launch interactive CLI; excluded arguments pass unchanged',()=>{
  const f=fixture('failure');assertExit(install(f,['-Apply']));
  assert.notEqual(invoke(f,[],{env:{CLI_FIXTURE_FAILURE:'1'}}).status,0);
  assert.equal(lines(f).some(args=>args.length===0),false);
  const e=fixture('excluded');assertExit(install(e,['-Apply']));
  const args=['resume','id','--no-daemon','-c','secret-looking=not-a-secret'];
  assertExit(invoke(e,args));
  assert.deepEqual(lines(e),[args]);
});
test('PowerShell and CMD retain existing npm shim argument and pipeline behavior',()=>{
  const f=fixture('shells');
  const argFile=path.join(f.dir,'args.json');const psFile=path.join(f.dir,'invoke.ps1');
  const args=['--no-daemon','space value','','quote"here','中文','a&b','%CLI_ARG_SENTINEL%','C:\\ends\\'];
  writeFileSync(argFile,JSON.stringify(args));
  writeFileSync(psFile,"param($Entry,$ArgFile)\n$values = @(Get-Content -LiteralPath $ArgFile -Raw | ConvertFrom-Json)\n'pipeline-data' | & $Entry @values\nexit $LASTEXITCODE\n");
  const ps=()=>spawnSync(pwsh,['-NoProfile','-File',psFile,path.join(f.dir,'codex.ps1'),argFile],{encoding:'utf8',env:{...f.env,CLI_FIXTURE_EXIT:'23'},cwd:root,timeout:30000});
  const cmd=()=>spawnSync(process.env.COMSPEC||'cmd.exe',['/d','/s','/c','""'+path.join(f.dir,'codex.cmd')+'" --no-daemon "space value" "" "中文" "a&b" "%CLI_ARG_SENTINEL%" "C:\\ends\\""' ],{encoding:'utf8',env:{...f.env,CLI_FIXTURE_EXIT:'23',CLI_ARG_SENTINEL:'expanded'},input:'cmd-input',cwd:root,timeout:30000,windowsVerbatimArguments:true});
  const beforePs=ps();const beforeCmd=cmd();assertExit(beforePs,23);assertExit(beforeCmd,23);
  assertExit(install(f,['-Apply']));
  const afterPs=ps();const afterCmd=cmd();assertExit(afterPs,23);assertExit(afterCmd,23);
  assert.deepEqual(JSON.parse(afterPs.stdout.trim()),JSON.parse(beforePs.stdout.trim()));
  assert.deepEqual(JSON.parse(afterCmd.stdout.trim()),JSON.parse(beforeCmd.stdout.trim()));
});
