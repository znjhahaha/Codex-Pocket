#!/usr/bin/env node
// Native npm forwarding remains in the original module; this file only ensures
// the local shared daemon for conservative, ordinary interactive invocations.
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const management = new Set(['agents','exec','e','review','login','logout','mcp','plugin','app-server','remote-control','app','completion','update','doctor','sandbox','debug','apply','a','queue','archive','delete','migrate-rollouts','migrate-nfs-db','unarchive','cloud','exec-server','features','help']);
const excluded = new Set(['--no-daemon','--config','--enable','--disable','--search','--profile','--oss','--local-provider','--remote','--remote-auth-token-env','--strict-config','--dangerously-bypass-hook-trust','--ignore-user-config','--ignore-rules','--model','--sandbox','--ask-for-approval','--approve-for-me','--dangerously-bypass-approvals-and-sandbox','--full-auto','--worktree']);
const safeValues = new Set(['--cd','--image','--add-dir']);
const safeFlags = new Set(['--no-alt-screen','--last','--all','--include-non-interactive']);

export function classifyLaunch(args, env = process.env) {
  for (const marker of ['CODEX_EXEC_SERVER_URL','OPENAI_FEDERATION_RULE_ID','OPENAI_IDENTITY_TOKEN_FILE','OPENAI_WORKLOAD_IDENTITY_CONTEXT']) {
    if (Object.hasOwn(env, marker)) return { ensure: false, reason: 'explicit executor or workload identity environment' };
  }
  let firstPosition = true;
  for (let i=0; i<args.length; i++) {
    const arg=args[i];
    if (arg==='--') break;
    if (arg==='--help'||arg==='-h'||arg==='--version'||arg==='-V') return {ensure:false,reason:null};
    if (arg.startsWith('--')) {
      const name=arg.split('=',1)[0];
      if (excluded.has(name)) return {ensure:false,reason:name};
      if (safeValues.has(name)) {
        if (!arg.includes('=')) {
          if (++i>=args.length) return {ensure:false,reason:'missing option value'};
        }
        continue;
      }
      if (safeFlags.has(arg)) continue;
      return {ensure:false,reason:'unrecognized option'};
    }
    if (arg.startsWith('-') && arg!=='-') {
      if (/^-[cpmsa]/.test(arg)) return {ensure:false,reason:'explicit configuration, model, sandbox or approval option'};
      if (/^-[Ci]/.test(arg)) {
        if (arg.length===2 && ++i>=args.length) return {ensure:false,reason:'missing option value'};
        continue;
      }
      return {ensure:false,reason:'unrecognized short option'};
    }
    if (firstPosition) {
      firstPosition=false;
      if (management.has(arg)) return {ensure:false,reason:null};
    }
  }
  return {ensure:true,reason:null};
}

function lifecycle(originalModule, operation) {
  const result=spawnSync(process.execPath,[originalModule,'app-server','daemon',operation],{
    cwd:process.cwd(),env:process.env,stdio:['ignore','pipe','pipe'],
    encoding:'utf8',timeout:20000,maxBuffer:1024*1024,windowsHide:true
  });
  if (result.error || result.status!==0) return null;
  try { const data=JSON.parse(result.stdout); return data && typeof data==='object' ? data : null; }
  catch { return null; }
}

function assertLocalColdStart() {
  const home=process.env.CODEX_HOME || path.join(os.homedir(),'.codex');
  let settings;
  try { settings=JSON.parse(readFileSync(path.join(home,'app-server-daemon','settings.json'),'utf8')); }
  catch (error) {
    if (error.code==='ENOENT') return;
    throw new Error('Cannot verify saved daemon launch preference; native settings were not changed.');
  }
  if (!settings || Array.isArray(settings) || typeof settings!=='object' ||
      ('remoteControlEnabled' in settings && typeof settings.remoteControlEnabled!=='boolean')) {
    throw new Error('Invalid saved daemon launch preference; native settings were not changed.');
  }
  if (settings.remoteControlEnabled===true) {
    throw new Error('Saved daemon remote control is enabled. Refusing a cold start that could use official enrollment; native settings were not changed.');
  }
}

function safeVersion(value) {
  return typeof value==='string' && /^[0-9]+\.[0-9]+\.[0-9]+[A-Za-z0-9.+-]*$/.test(value) ? value : 'unknown';
}

async function main() {
  const directory=path.dirname(fileURLToPath(import.meta.url));
  const metadata=JSON.parse(readFileSync(path.join(directory,'runtime.json'),'utf8'));
  if (metadata.schema!==1 || !path.isAbsolute(metadata.originalModule)) throw new Error('Invalid CLI wrapper installation metadata.');
  const originalModule=metadata.originalModule;
  const selection=classifyLaunch(process.argv.slice(2));
  if (selection.ensure) {
    let state=lifecycle(originalModule,'version');
    if (state?.status!=='running') {
      assertLocalColdStart();
      const started=lifecycle(originalModule,'start');
      if (!started || !['started','alreadyRunning'].includes(started.status)) {
        throw new Error('Local daemon start failed. Native credentials and settings were not changed. Use --no-daemon for an explicit embedded launch.');
      }
      if (Number.isSafeInteger(started.pid) && started.pid>0) {
        process.stderr.write('[codex2] Started local daemon PID '+started.pid+'.\n');
      }
      state=lifecycle(originalModule,'version');
    }
    if (state?.status!=='running') throw new Error('Local daemon readiness check failed; interactive launch was not started.');
    const cli=safeVersion(state.cliVersion),server=safeVersion(state.appServerVersion);
    if (cli!==server) process.stderr.write('[codex2] CLI '+cli+'; existing daemon '+server+' (version mismatch; no replacement).\n');
  } else if (selection.reason) {
    process.stderr.write('[codex2] Local daemon ensure skipped: '+selection.reason+'. Forwarding unchanged to native CLI.\n');
  }
  // Preserve the original launcher's argv[1]-based package-manager discovery.
  process.argv[1]=originalModule;
  await import(pathToFileURL(originalModule).href);
}

if (process.argv[1] && path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  try { await main(); }
  catch (error) {
    // Only our fixed diagnostic messages are emitted; underlying exception text
    // and lifecycle stderr can contain native configuration details.
    const message = error instanceof Error && /^(Cannot verify saved|Invalid saved|Saved daemon remote|Local daemon|Invalid CLI wrapper)/.test(error.message)
      ? error.message : 'CLI wrapper could not load its installation or original launcher.';
    process.stderr.write('[codex2] '+message+'\n');
    process.exitCode=1;
  }
}

