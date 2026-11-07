param([int]$TimeoutSeconds = 120, [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$manifest = Join-Path $repo 'crates\cli-adapter\Cargo.toml'
$target = Join-Path $repo '.local\cli-target'
if (-not $SkipBuild) {
    & cargo build --manifest-path $manifest --example cli_probe_transport --target-dir $target
    if ($LASTEXITCODE -ne 0) { throw 'CLI probe transport build failed.' }
}
$helper = Join-Path $target 'debug\examples\cli_probe_transport.exe'
if (-not (Test-Path -LiteralPath $helper)) { throw 'Build the CLI probe transport first.' }
$probeCode = @'
import os, sys, json, time, uuid, re, queue, threading, subprocess, hashlib
from pathlib import Path
from winpty import PtyProcess

ROOT=Path(sys.argv[1]).resolve()
HELPER=sys.argv[2]
LIMIT=int(sys.argv[3])
CWD=ROOT/'.local'/'probe-workspace'
CWD.mkdir(parents=True,exist_ok=True)
HOME=Path(os.environ.get('CODEX_HOME') or Path.home()/'.codex')
CONFIG=HOME/'config.toml'
def fingerprint():
    return hashlib.sha256(CONFIG.read_bytes()).hexdigest() if CONFIG.exists() else None
BEFORE=fingerprint()
RESULT={'cliVersion':None,'nativeConfigUnchanged':False,'transport':'WebSocket over AF_UNIX','workspace':str(CWD),'checks':{},'ownedProcesses':[]}
# Only fixed argv and generated UUID markers reach cmd.exe.
def native(args):
    return subprocess.run(['cmd.exe','/d','/c','codex',*args],capture_output=True,text=True,timeout=20)
version=native(['--version'])
RESULT['cliVersion']=version.stdout.strip() if version.returncode==0 else None
status=native(['app-server','daemon','version'])
if status.returncode==0:
    data=json.loads(status.stdout)
    RESULT['daemonBefore']={k:data.get(k) for k in ('status','backend','cliVersion','managedCodexVersion','appServerVersion','socketPath')}
else:
    RESULT['daemonBefore']={'status':'unavailable','exitCode':status.returncode}

class Tui:
    def __init__(self,args):
        self.p=PtyProcess.spawn('cmd.exe /d /c codex --no-alt-screen '+args,cwd=str(CWD),env=dict(os.environ,TERM='xterm-256color'),dimensions=(38,130))
        RESULT['ownedProcesses'].append({'kind':'tui-launcher','pid':self.p.pid})
        self.text='';self.lock=threading.Lock();self.update_skipped=False;self.update_timer=None
        def read():
            while True:
                try:
                    s=self.p.read(8192)
                    with self.lock:
                        self.text=(self.text+s)[-2_000_000:]
                        plain=re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]','',self.text)
                        skip_update=not self.update_skipped and 'Update available' in plain and 'Update now' in plain
                        if skip_update:self.update_skipped=True
                    if skip_update:
                        # Native update_prompt draws, then discards pending input.
                        # Wait until that drain is over; Escape is one-time Skip.
                        def skip():
                            try:self.p.write('\x1b')
                            except Exception:pass
                        self.update_timer=threading.Timer(1.0,skip)
                        self.update_timer.daemon=True;self.update_timer.start()
                    if '\x1b[6n' in s:self.p.write('\x1b[1;1R')
                    if '\x1b[c' in s:self.p.write('\x1b[?1;2c')
                except Exception:break
        threading.Thread(target=read,daemon=True).start()
    def contains(self,marker):
        with self.lock:
            plain=re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]','',self.text)
            return marker in plain or ''.join(marker.split()) in ''.join(plain.split())
    def close(self):
        # Only this process, created above, is eligible for cleanup.
        if self.update_timer is not None:self.update_timer.cancel()
        try:
            self.p.write('\x03');time.sleep(.25);self.p.write('\x03');time.sleep(.25)
        except Exception:pass
        if self.p.isalive():self.p.close(force=True)

class Rpc:
    def __init__(self):
        self.p=subprocess.Popen([HELPER],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,encoding='utf-8',bufsize=1)
        RESULT['ownedProcesses'].append({'kind':'rust-transport','pid':self.p.pid})
        self.q=queue.Queue();self.notes=[];self.counter=0
        def read():
            try:
                for line in self.p.stdout:
                    try:self.q.put(json.loads(line))
                    except ValueError:self.q.put({'probeError':'invalid transport output'})
            finally:self.q.put({'probeError':'transport closed'})
        threading.Thread(target=read,daemon=True).start()
        try:
            m=self.receive(10)
            if 'probeMetadata' not in m:raise RuntimeError('Native daemon attachment unavailable')
            self.metadata=m['probeMetadata']
        except Exception:
            self.close();raise
    def receive(self,seconds):
        try:m=self.q.get(timeout=max(.01,seconds))
        except queue.Empty:raise TimeoutError('Native daemon response timed out')
        if 'probeError' in m:raise RuntimeError('Native daemon transport closed')
        return m
    def send(self,m):
        self.p.stdin.write(json.dumps(m,ensure_ascii=False)+'\n');self.p.stdin.flush()
    def call(self,method,params):
        self.counter+=1;rid='cli-probe-'+str(self.counter)
        self.send({'id':rid,'method':method,'params':params});deadline=time.monotonic()+20
        while time.monotonic()<deadline:
            m=self.receive(deadline-time.monotonic())
            if m.get('id')==rid:
                if 'error' in m:raise RuntimeError('RPC '+method+' failed with code '+str(m['error'].get('code')))
                return m.get('result')
            self.notes.append(m)
        raise TimeoutError('RPC '+method+' timed out')
    def close(self):
        try:self.p.stdin.close();self.p.wait(timeout=3)
        except Exception:
            self.p.terminate();self.p.wait(timeout=3)

def wait_until(fn,seconds=LIMIT):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        value=fn()
        if value:return value
        time.sleep(.3)
    raise TimeoutError('Probe condition timed out')

def require_live_thread(tid,loaded_ids,tui_alive):
    if tid not in loaded_ids or not tui_alive:
        raise RuntimeError('Refusing thread/resume: disposable thread must be loaded and original TUI alive')
    return True

def followup_prompt(nonce):
    first='CLI_FOLLOWUP_';second=nonce
    expected=first+second
    prompt='Reply only with the concatenation of these two components, with no separator: first "'+first+'", second "'+second+'". Do not call tools or read files.'
    return expected,prompt

rpc=None;tui=None;resume=None
try:
    marker='CLI_PROBE_'+uuid.uuid4().hex
    tui=Tui('"Reply with '+marker+' only. Do not call tools or read files."')
    # Ordinary launch may start the native daemon according to native policy.
    # This probe issues no start/restart/bootstrap/standalone app-server command.
    attach_deadline=time.monotonic()+20
    while rpc is None:
        try:rpc=Rpc()
        except Exception:
            if time.monotonic()>=attach_deadline:raise RuntimeError('Ordinary CLI did not expose an attachable native daemon; check daemon_auto_start and Windows launch restrictions')
            time.sleep(1)
    RESULT['discovery']=rpc.metadata
    init=rpc.metadata['initializeResult']
    RESULT['server']={k:init.get(k) for k in ('userAgent','codexHome','platformFamily','platformOs')}
    def locate():
        rows=rpc.call('thread/list',{'cwd':str(CWD),'limit':10,'searchTerm':marker,'useStateDbOnly':True}).get('data',[])
        return next((t for t in rows if os.path.normcase(t.get('cwd',''))==os.path.normcase(str(CWD))),None)
    thread=wait_until(locate);tid=thread['id'];RESULT['threadId']=tid
    ids=rpc.call('thread/loaded/list',{}).get('data',[])
    RESULT['checks']['nativeThreadLoadedWhileTuiAlive']=require_live_thread(tid,ids,tui.p.isalive())
    joined=rpc.call('thread/resume',{'threadId':tid,'excludeTurns':True})
    RESULT['checks']['liveJoinPreservedNativeId']=joined.get('thread',{}).get('id')==tid
    RESULT['nativeModel']=joined.get('model')
    RESULT['nativeProviderMatchesCliThread']=bool(thread.get('modelProvider')) and joined.get('modelProvider')==thread.get('modelProvider')
    def idle():
        t=rpc.call('thread/read',{'threadId':tid,'includeTurns':False}).get('thread',{})
        return t.get('status',{}).get('type')=='idle'
    wait_until(idle)
    def followup(active_tui,label):
        text,prompt=followup_prompt(uuid.uuid4().hex)
        turn=rpc.call('turn/start',{'threadId':tid,'input':[{'type':'text','text':prompt}]})['turn']
        turn_id=turn['id'];deltas=[];complete=None;end=time.monotonic()+LIMIT
        while time.monotonic()<end and complete is None:
            m=rpc.notes.pop(0) if rpc.notes else rpc.receive(end-time.monotonic())
            params=m.get('params') or {}
            if params.get('threadId')!=tid:continue
            if params.get('turnId') not in (None,turn_id):continue
            if m.get('method')=='item/agentMessage/delta':deltas.append(params.get('delta',''))
            if m.get('method')=='turn/completed' and params.get('turn',{}).get('id')==turn_id:complete=params['turn']
        if complete is None:raise TimeoutError('Follow-up completion missing')
        RESULT['checks'][label+'Streamed']=bool(deltas) and text in ''.join(deltas)
        RESULT['checks'][label+'Completed']=complete.get('status')=='completed'
        RESULT['checks'][label+'VisibleInTui']=bool(wait_until(lambda:active_tui.p.isalive() and active_tui.contains(text),15))
    followup(tui,'liveFollowup')
    interrupted=rpc.call('turn/start',{'threadId':tid,'input':[{'type':'text','text':'Count from 1 to 10000 with one number per line. Do not call tools or read files.'}]})['turn']['id']
    end=time.monotonic()+LIMIT
    while time.monotonic()<end:
        m=rpc.notes.pop(0) if rpc.notes else rpc.receive(end-time.monotonic())
        params=m.get('params') or {}
        if params.get('threadId')!=tid:continue
        if m.get('method')=='item/agentMessage/delta' and params.get('turnId')==interrupted:break
        if m.get('method')=='turn/completed' and params.get('turn',{}).get('id')==interrupted:
            raise RuntimeError('Interrupt target completed before live output was observed')
    else:raise TimeoutError('Interrupt target did not begin live output')
    rpc.call('turn/interrupt',{'threadId':tid,'turnId':interrupted})
    end=time.monotonic()+30;interrupted_status=None
    while time.monotonic()<end:
        m=rpc.notes.pop(0) if rpc.notes else rpc.receive(end-time.monotonic())
        params=m.get('params') or {}
        if m.get('method')=='turn/completed' and params.get('threadId')==tid and params.get('turn',{}).get('id')==interrupted:
            interrupted_status=params['turn'].get('status');break
    RESULT['checks']['interruptWhileOriginalTuiAlive']=interrupted_status=='interrupted' and tui.p.isalive()
    wait_until(idle)
    approval_marker='CLI_APPROVAL_'+uuid.uuid4().hex
    approval_path=CWD/(approval_marker+'.txt')
    approval_text="Approval-routing test. Use the shell tool once to run exactly: Set-Content -LiteralPath '"+str(approval_path)+"' -Value '"+approval_marker+"'. If approval is denied, do not retry or use another tool; reply DENIED. Do not read any files."
    approval_turn=rpc.call('turn/start',{'threadId':tid,'input':[{'type':'text','text':approval_text}],'approvalPolicy':'untrusted','sandboxPolicy':{'type':'dangerFullAccess'}})['turn']['id']
    end=time.monotonic()+LIMIT;approval_seen=False;approval_done=False
    while time.monotonic()<end and not approval_done:
        m=rpc.notes.pop(0) if rpc.notes else rpc.receive(end-time.monotonic())
        params=m.get('params') or {}
        if params.get('threadId')!=tid:continue
        if m.get('method')=='item/commandExecution/requestApproval' and 'id' in m:
            approval_seen=True
            RESULT['approval']={'decision':'decline','requestIdType':type(m['id']).__name__,'originalTuiAlive':tui.p.isalive(),'probeMarkerInCommand':approval_marker in (params.get('command') or ''),'cwdMatchesProbe':os.path.normcase(params.get('cwd') or '')==os.path.normcase(str(CWD))}
            # Decline once: no persistent permission/rule and no file mutation.
            rpc.send({'id':m['id'],'result':{'decision':'decline'}})
        if m.get('method')=='turn/completed' and params.get('turn',{}).get('id')==approval_turn:approval_done=True
    RESULT['checks']['approvalRoundtripWhileOriginalTuiAlive']=approval_seen and approval_done and tui.p.isalive() and not approval_path.exists()
    wait_until(idle)
    tui.close();tui=None
    resume=Tui('resume '+tid)
    # A new CLI resume must display a fresh server-side turn on this exact ID.
    wait_until(lambda:resume.contains(approval_marker) or resume.contains('DENIED'),60)
    followup(resume,'ordinaryResumeFollowup')
    ids=rpc.call('thread/loaded/list',{}).get('data',[])
    RESULT['checks']['ordinaryResumePreservedNativeId']=tid in ids and resume.p.isalive()
except Exception as e:
    # No raw RPC bodies, config values, or TUI text are logged.
    RESULT['error']=str(e)
finally:
    for instance in (resume,tui):
        if instance is not None:
            RESULT.setdefault('updatePromptsSkipped',[]).append({'pid':instance.p.pid,'skipped':instance.update_skipped})
            try:instance.close()
            except Exception:pass
    if rpc is not None:
        try:rpc.close()
        except Exception:pass
    RESULT['nativeConfigUnchanged']=fingerprint()==BEFORE
    RESULT['passed']=bool(RESULT['checks']) and all(RESULT['checks'].values()) and RESULT['nativeConfigUnchanged'] and 'error' not in RESULT
    output=json.dumps(RESULT,ensure_ascii=True,indent=2)
    (ROOT/'docs'/'cli-native-probe-result.md').write_text('# Native CLI integration probe\n\n```json\n'+output+'\n```\n',encoding='utf-8')
    print(output)
    sys.exit(0 if RESULT['passed'] else 1)
'@
$probeCode | & python - $repo $helper $TimeoutSeconds
exit $LASTEXITCODE
