#requires -Version 7.0
<#
Actual old payload, copied to a private workspace root. No installed paths,
registry, shell integration, paired data, real Codex or model requests.
Native protocol and desktop owner are explicitly synthetic. The old bridge,
supervisor, helper and current coordinator are real executables.
Evidence is retained; cleanup stops only captured fixture process identities.
#>
[CmdletBinding()]
param(
    [string]$LegacyDirectory='D:/codex2/dist/codex-pocket-0.6.6-host-recovery-ready-20261112/release/windows',
    [string]$CandidateDirectory,
    [string]$DebugDirectory='D:/codex2/target/debug',
    [switch]$PrepareOnly,
    [switch]$LegacyOnly
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if($env:OS -ne 'Windows_NT'){throw 'Windows only'}
if($MyInvocation.InvocationName -eq '.'){throw 'Run in a dedicated pwsh process'}
. (Join-Path $PSScriptRoot 'windows-install.ps1')
. (Join-Path $PSScriptRoot 'windows-upgrade-fixture-processes.ps1')
$script:toolsRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../.local/v070/windows-upgrade-harness'))
$script:root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot ('../.local/v070/upgrade-'+[guid]::NewGuid().ToString('N'))))
$script:install=Join-Path $script:root 'install'
$script:state=Join-Path $script:install 'isolated state'
$script:runtimes=Join-Path $script:install 'runtimes'
$script:package=Join-Path $script:root 'fixture-package'
$script:captured=@{}
$script:cases=[Collections.Generic.List[object]]::new()
$script:success=$false
$script:rev=0
$script:errors=[Collections.Generic.List[string]]::new()
function Assert-Test($Condition,[string]$Message){if(-not $Condition){throw $Message}}
function Scope-Test([string]$Path){
    $full=[IO.Path]::GetFullPath($Path)
    Assert-Test ($full -eq $script:root -or $full.StartsWith($script:root+'\',[StringComparison]::OrdinalIgnoreCase)) ('fixture path boundary: '+$full)
    Assert-PocketUnredirectedPath $full
    return $full
}
function Directory-Test([string]$Path){
    $full=Scope-Test $Path;[void][IO.Directory]::CreateDirectory($full)
    $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl=Get-Acl -LiteralPath $full
    $acl.SetAccessRuleProtection($true,$false)
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
    [IO.FileSystemAclExtensions]::SetAccessControl([IO.DirectoryInfo]::new($full),$acl)
}
function Json-Test([string]$Path,$Value){Scope-Test $Path|Out-Null;Write-PocketJson $Path $Value}
function Record-Test([string]$Name,$Evidence){
    $script:cases.Add(@{name=$Name;evidence=$Evidence})
    Json-Test (Join-Path $script:root 'acceptance-progress.json') @{cases=$script:cases.ToArray()}
    Write-Output ('PASS '+$Name)
}
function Capture-Test($Identity){
    if($null -eq $Identity){return}
    Scope-Test $Identity.executable|Out-Null
    Assert-Test ($Identity.pid -ne $PID) 'Cannot capture coordinator shell'
    $script:captured[([string]$Identity.pid+':'+[string]$Identity.createdAt)]=@{pid=$Identity.pid;createdAt=$Identity.createdAt;executable=$Identity.executable;sha256=(Get-PocketFileHash $Identity.executable);name=[IO.Path]::GetFileName($Identity.executable)}
}
function Group-Test {
    $g=Get-PocketInstallGroup $script:install $script:state
    foreach($p in $g){Capture-Test $p}
    return ,$g
}
# Query real kernel rows only in this fixture. WMI on this machine cancels even
# constrained queries; Toolhelp plus NtQueryInformationProcess avoids that service.
function Get-PocketInstallProcesses {
    Initialize-PocketFixtureProcessSnapshot
    return @([PocketFixtureSnapshot]::Read($script:root))
}
function Alive-Test($Identity){
    $p=$null
    for($attempt=0;$attempt -lt 3;$attempt++){
        try{$p=Get-PocketInstallProcessIdentity $Identity.pid;break}
        catch{if(-not (Get-Process -Id $Identity.pid -ErrorAction SilentlyContinue)){return $false};if($attempt -eq 2){throw};Start-Sleep -Milliseconds 50}
    }
    return $null -ne $p -and $p.createdAt -eq $Identity.createdAt -and $p.executable -eq $Identity.executable
}
function Spawn-Test([string]$Executable,[string[]]$Arguments,[switch]$Wait){
    Scope-Test $Executable|Out-Null
    $info=[Diagnostics.ProcessStartInfo]::new()
    $info.FileName=$Executable;$info.Arguments=($Arguments|ForEach-Object{ConvertTo-PocketArgument $_})-join ' '
    $info.WorkingDirectory=$script:root;$info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.WindowStyle='Hidden'
    foreach($k in @($info.Environment.Keys)){if([string]$k -match '^(POCKET_|CODEX_|OPENAI_|ANTHROPIC_|AZURE_OPENAI_)'){$info.Environment.Remove([string]$k)|Out-Null}}
    foreach($name in @('USERPROFILE','HOME','APPDATA','LOCALAPPDATA','CODEX_HOME','TEMP','TMP')){$info.Environment[$name]=Join-Path $script:root ('empty-'+$name)}
    $info.Environment['USERPROFILE']=Join-Path $script:root 'profile'
    $info.Environment['HOME']=Join-Path $script:root 'profile'
    $info.Environment['APPDATA']=Join-Path $script:root 'profile/AppData/Roaming'
    $info.Environment['LOCALAPPDATA']=Join-Path $script:root 'profile/AppData/Local'
    $info.Environment['POCKET_RUNTIME_DIR']=$script:runtimes
    $info.Environment['POCKET_FIXTURE_SCHEMA']=Join-Path $script:root 'ClientRequest.json'
    $info.Environment['POCKET_FIXTURE_ROOT']=$script:root
    $info.Environment['PATH']=Join-Path $env:SystemRoot 'System32'
    $process=[Diagnostics.Process]::Start($info)
    Capture-Test (Get-PocketInstallProcessIdentity $process.Id)
    if($Wait){Assert-Test ($process.WaitForExit(120000)) 'fixture command timeout';$code=$process.ExitCode;$process.Dispose();return $code}
    $id=$process.Id;$process.Dispose();return $id
}
function Wait-Test([scriptblock]$Probe,[int]$Seconds=30){
    $deadline=[DateTime]::UtcNow.AddSeconds($Seconds);$last='no evidence'
    do{try{$v=& $Probe;if($v){return $v}}catch{$last=$_.Exception.Message};Start-Sleep -Milliseconds 100}while([DateTime]::UtcNow -lt $deadline)
    throw ('fixture condition timed out: '+$last)
}
function Copy-Test([string]$Source,[string]$Destination){
    $manifest=Read-PocketRepairJson (Join-Path $Source 'windows-manifest.json')
    Directory-Test $Destination
    foreach($name in @($manifest.files.path)+@('windows-manifest.json')){
        Assert-Test ($name -cmatch '^[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*$' -and -not $name.Contains('..')) 'Unsafe inventory path'
        $target=Scope-Test (Join-Path $Destination $name);Directory-Test ([IO.Path]::GetDirectoryName($target))
        [IO.File]::Copy((Join-Path $Source $name),$target,$false)
    }
    Test-PocketWindowsDirectory $Destination|Out-Null
}
function Healthy-Test([string]$Generation){
    return Wait-Test {
        $g=Group-Test;$h=Invoke-PocketInstallControl $script:state 'status'
        $a=@($g|Where-Object{$_.name -eq 'pocket-agent.exe'});$s=@($g|Where-Object{$_.name -eq 'pocket-supervisor.exe'})
        Assert-Test ($a.Count -eq 1 -and $s.Count -eq 1) 'Awaiting exact owners'
        Get-PocketAuthenticatedInstallerAgent $h $a $script:state|Out-Null
        Assert-Test ($h.acceptingWrites -eq $true -and $h.helperRuntime.generation -eq $Generation -and $a[0].parentPid -eq $s[0].pid) 'Awaiting healthy generation'
        Assert-Test ($h.remoteEnabled -eq $false) 'Fixture enabled ingress'
        $cfg=Read-PocketRepairJson (Join-Path $script:state 'config.json')
        Assert-Test (@($cfg.codexHomes).Count -eq 0 -and @($cfg.runtimeDirectories).Count -le 1 -and $cfg.relayUrl -eq '') 'Fixture escaped discovery/config boundary'
        foreach($d in @($cfg.runtimeDirectories)){Assert-Test ($d -eq $script:runtimes) 'Unexpected runtime directory'}
        Invoke-PocketInstallControl $script:state 'routes/read'|Out-Null
        return $h
    }
}
function Start-SupervisorTest([string]$Generation){
    $b=Join-Path $script:install ('bin/'+$Generation)
    Spawn-Test (Join-Path $b 'pocket-supervisor.exe') @('--state',$script:state,'--agent',(Join-Path $b 'pocket-agent.exe'),'--owner-directory',(Join-Path $script:install 'control'))|Out-Null
    Healthy-Test $Generation|Out-Null
}
function Descriptor-Test {
    $files=@(Get-ChildItem -LiteralPath $script:runtimes -Filter '*.json')
    foreach($f in $files){
        Scope-Test $f.FullName|Out-Null;$d=Read-PocketRepairJson $f.FullName 16384
        $p=Get-PocketInstallProcessIdentity $d.pid
        if($p){Scope-Test $p.executable|Out-Null;Capture-Test $p;return $d}
    }
    throw 'No live fixture bridge descriptor'
}
function Bridge-Test([string]$Method,$Params=@{}){
    $d=Descriptor-Test;$id=[guid]::NewGuid().ToString();$c=[Net.Sockets.TcpClient]::new()
    try{
        $c.Connect('127.0.0.1',[int]$d.port);$s=$c.GetStream();$s.ReadTimeout=5000;$s.WriteTimeout=5000
        $w=[IO.StreamWriter]::new($s,[Text.UTF8Encoding]::new($false),4096,$true);$w.AutoFlush=$true
        $r=[IO.StreamReader]::new($s,[Text.UTF8Encoding]::new($false),$false,4096,$true)
        $w.WriteLine((@{token=$d.token}|ConvertTo-Json -Compress))
        $w.WriteLine((@{id=$id;method=$Method;params=$Params}|ConvertTo-Json -Depth 12 -Compress))
        do{$line=$r.ReadLine();if($null -eq $line){throw 'Bridge disconnected'};$v=$line|ConvertFrom-Json}while((Get-PocketProperty $v 'id' '') -ne $id)
        if($v.PSObject.Properties['error']){return @{ok=$false;code=$v.error.code}}
        return @{ok=$true;result=$v.result}
    }finally{$c.Dispose()}
}
function Mode-Test([string]$Mode){
    $script:rev++;Json-Test (Join-Path $script:root 'desktop-command.json') @{mode=$Mode;revision=$script:rev}
    Wait-Test {$p=Join-Path $script:root 'native-mode.json';if(Test-Path -LiteralPath $p){$m=Read-PocketRepairJson $p;if($m.mode -eq $Mode){return $true}}}|Out-Null
    Start-Sleep -Milliseconds 150
}
function Start-DesktopTest([string]$Generation,[switch]$Withhold){
    $args=@('--desktop','--fixture-root',$script:root,'--bridge',(Join-Path $script:install ('bin/'+$Generation+'/pocket-bridge.exe')),'--native',(Join-Path $script:package 'app/resources/codex.exe'))
    if($Withhold){$args+='--withhold-initialized'}
    Spawn-Test (Join-Path $script:package 'app/Codex.exe') $args|Out-Null
    Wait-Test {Descriptor-Test}|Out-Null
    Wait-Test {if(Test-Path -LiteralPath (Join-Path $script:root 'desktop-wire.jsonl')){if((Get-Content (Join-Path $script:root 'desktop-wire.jsonl') -Raw).Contains('desktop-resume')){return $true}}}|Out-Null
    Group-Test|Out-Null
}
function Stop-DesktopTest {
    $g=@((Group-Test)|Where-Object{$_.name -in @('associated-desktop','associated-native','pocket-bridge.exe')})
    Json-Test (Join-Path $script:root 'desktop-command.json') @{mode='exit';revision=++$script:rev}
    Wait-Test {foreach($p in $g){if(Alive-Test $p){return $false}};return $true}|Out-Null
    foreach($f in @('desktop-command.json','desktop-wire.jsonl','native-mode.json')){$p=Scope-Test (Join-Path $script:root $f);if(Test-Path -LiteralPath $p){Remove-Item -LiteralPath $p}}
}
function Queue-Test([string]$From){
    $exe=Join-Path $script:root 'coordinator_driver.exe'
    # Driver stdout contains only the operation UUID; no capability/config output.
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=$exe;$info.ArgumentList.Add('queue');$info.ArgumentList.Add($script:install);$info.ArgumentList.Add($script:state);$info.ArgumentList.Add($From)
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    $p=[Diagnostics.Process]::Start($info);$out=$p.StandardOutput.ReadToEnd();$err=$p.StandardError.ReadToEnd();$p.WaitForExit()
    Assert-Test ($p.ExitCode -eq 0) ('Queue failed: '+$err);$p.Dispose();return $out.Trim()
}
function Run-CoordinatorTest([string]$Operation){
    $exe=Join-Path $script:install ('bin/'+$script:newGeneration+'/pocket-upgrade.exe')
    $code=Spawn-Test $exe @('--install-root',$script:install,'--state',$script:state,'--operation-id',$Operation) -Wait
    $status=Read-PocketRepairJson (Join-Path $script:state 'desktop/upgrade.json')
    return @{exit=$code;status=$status}
}
# Only package discovery is substituted. All process identities, full inventory
# verification, authentication, idle admission and shared handoff are real.
function Get-PocketDesktopPackage{return [pscustomobject]@{InstallLocation=$script:package;PackageFamilyName='isolated-fixture'}}
try{
    Directory-Test $script:root
    [IO.File]::WriteAllText((Join-Path $script:root '.fixture-owner'),'isolated-actual-payload')
    foreach($d in @($script:install,$script:state,$script:runtimes,(Join-Path $script:install 'control'),(Join-Path $script:package 'app/resources'))){Directory-Test $d}
    [IO.File]::WriteAllText((Join-Path $script:install '.fixture-owner'),'isolated-actual-payload')
    foreach($n in @('native_fixture.exe','coordinator_driver.exe','ClientRequest.json')){[IO.File]::Copy((Join-Path $script:toolsRoot $n),(Join-Path $script:root $n),$false)}
    foreach($n in @('USERPROFILE','HOME','APPDATA','LOCALAPPDATA','CODEX_HOME','TEMP','TMP')){Directory-Test (Join-Path $script:root ('empty-'+$n))}
    foreach($n in @('profile','profile/AppData/Local','profile/AppData/Roaming')){Directory-Test (Join-Path $script:root $n)}
    [IO.File]::Copy((Join-Path $script:toolsRoot 'native_fixture.exe'),(Join-Path $script:package 'app/Codex.exe'),$false)
    [IO.File]::Copy((Join-Path $script:toolsRoot 'native_fixture.exe'),(Join-Path $script:package 'app/resources/codex.exe'),$false)
    $oldManifest=Read-PocketRepairJson (Join-Path $LegacyDirectory 'windows-manifest.json')
    Assert-Test ($oldManifest.version -eq '0.6.6') 'Must use actual 0.6.6 inventory'
    $script:oldGeneration=Get-PocketFileHash (Join-Path $LegacyDirectory 'windows-manifest.json')
    $oldBin=Join-Path $script:install ('bin/'+$script:oldGeneration)
    Copy-Test $LegacyDirectory $oldBin
    Record-Test 'exact 0.6.6 inventory copied and verified' @{source=$LegacyDirectory;generation=$script:oldGeneration;files=$oldManifest.files}
    if($PrepareOnly){$script:success=$true;return}
    Assert-Test ((Spawn-Test (Join-Path $oldBin 'pocket-agent.exe') @('init','--state',$script:state,'--json') -Wait) -eq 0) 'Old helper init failed'
    $cfg=Read-PocketRepairJson (Join-Path $script:state 'config.json')
    $cfg.runtimeDirectories=@($script:runtimes);$cfg.codexHomes=@();$cfg.relayUrl='';$cfg.routePreferences=@{policy='automatic';autoLan=$false;lan=$null;gateway=$null;routes=@()}
    Json-Test (Join-Path $script:state 'config.json') $cfg
    Directory-Test (Join-Path $script:state 'desktop')
    Directory-Test (Join-Path $script:state 'desktop/connectivity')
    Json-Test (Join-Path $script:state 'desktop/desired.json') @{enabled=$false;changedAt=1}
    $launcher=Join-Path $script:install 'pocket-launcher.exe';[IO.File]::Copy((Join-Path $oldBin 'pocket-launcher.exe'),$launcher,$false)
    Json-Test (Join-Path $script:install 'desktop-install.json') @{version=3;singleEntry=$true;installRoot=$script:install;stateDirectory=$script:state;generation=$script:oldGeneration;releaseHash=$script:oldGeneration;bridge=$launcher;launcherHash=(Get-PocketFileHash $launcher)}
    Start-SupervisorTest $script:oldGeneration
    Record-Test 'actual 0.6.6 supervisor/helper authenticated and isolated' @{generation=$script:oldGeneration;identities=(Group-Test)}
    Start-DesktopTest $script:oldGeneration -Withhold
    $m=Bridge-Test 'pocket/managementStatus';$read=Bridge-Test 'thread/read' @{threadId='fixture-thread';includeTurns=$true}
    Assert-Test ($m.ok -and $m.result.initialized -eq $false -and $m.result.busy -eq $false) 'Expected legacy missing-notification status'
    Record-Test 'actual legacy initialized=false bridge native read probe' @{management=$m.result;read=$read}
    Stop-DesktopTest
    Start-DesktopTest $script:oldGeneration
    foreach($mode in @('active','waiting','idle')){
        Mode-Test $mode
        $m=Bridge-Test 'pocket/managementStatus'
        Assert-Test ($m.ok -and $m.result.initialized -eq $true -and $m.result.busy -eq ($mode -ne 'idle')) ('Incorrect actual old busy proof: '+$mode)
        $before=Group-Test;$life=Enter-PocketInstallLifecycle $script:install $script:state (Read-PocketRepairJson (Join-Path $script:install 'desktop-install.json'))
        Assert-Test ($life.phase -eq 'waiting_idle' -and $life.processesStopped -eq 0) 'Unsafe lifecycle did not defer'
        foreach($p in $before){Assert-Test (Alive-Test $p) 'Deferral terminated fixture workload'}
        $read=Bridge-Test 'thread/read' @{threadId='fixture-thread';includeTurns=$true}
        Record-Test ('real 0.6.6 '+$mode+' native workload and installer deferral') @{management=$m.result;read=$read;deferredReason=$life.deferredReason;identities=$before}
    }
    # Demonstrate the probe/stop race without terminating any native workload.
    $idle=Bridge-Test 'pocket/managementStatus';Mode-Test 'active';$busy=Bridge-Test 'pocket/managementStatus'
    Assert-Test ($idle.result.busy -eq $false -and $busy.result.busy -eq $true) 'Race fixture invalid'
    $fence=Bridge-Test 'pocket/maintenance/quiesce' @{operationId=[guid]::NewGuid().ToString()}
    Record-Test 'actual old bridge admits desktop task after idle sample' @{idle=$idle.result;after=$busy.result;fenceProbe=$fence;stopped=0}
    Mode-Test 'idle'
    if($LegacyOnly){$script:success=$true;return}
    $candidate=Join-Path $script:root 'candidate'
    if($CandidateDirectory){Copy-Test $CandidateDirectory $candidate;$script:candidateKind='actual candidate payload'}else{
        Copy-Test $LegacyDirectory $candidate
        # Real debug binaries, not fake runtime placeholders. The old UI is
        # retained and never launched; this is explicitly a mixed debug harness.
        foreach($n in @('pocket-agent.exe','pocket-bridge.exe','pocket-supervisor.exe','pocket-upgrade.exe','pocket-runtime-host.exe','pocket-launcher.exe')){[IO.File]::Copy((Join-Path $DebugDirectory $n),(Join-Path $candidate $n),$true)}
        $man=Read-PocketRepairJson (Join-Path $candidate 'windows-manifest.json');$man.version='0.7.0';$man|Add-Member -NotePropertyName fixtureKind -NotePropertyValue 'isolated current debug runtime plus unchanged legacy nonexecuted inventory'
        foreach($entry in $man.files){$p=Join-Path $candidate $entry.path;$entry.sha256=Get-PocketFileHash $p;$entry.bytes=(Get-Item -LiteralPath $p).Length}
        Json-Test (Join-Path $candidate 'windows-manifest.json') $man
        Test-PocketWindowsDirectory $candidate|Out-Null
        $script:candidateKind='mixed current debug runtime; not release candidate acceptance'
    }
    $script:newGeneration=Get-PocketFileHash (Join-Path $candidate 'windows-manifest.json')
    Copy-Test $candidate (Join-Path $script:install ('bin/'+$script:newGeneration))
    $keep=@{};foreach($n in @('config.json','identity/private-key.der','identity/certificate.der')){$p=Join-Path $script:state $n;$keep[$p]=Get-PocketFileHash $p}
    $native=@((Group-Test)|Where-Object{$_.name -in @('associated-desktop','associated-native','pocket-bridge.exe')})
    $record=Read-PocketRepairJson (Join-Path $script:install 'desktop-install.json');$record.generation=$script:newGeneration;$record.releaseHash=$script:newGeneration;Json-Test (Join-Path $script:install 'desktop-install.json') $record
    $operation=Queue-Test $script:oldGeneration
    $handoff=Run-CoordinatorTest $operation
    Record-Test 'actual coordinator old to current result' @{candidateKind=$script:candidateKind;generation=$script:newGeneration;exit=$handoff.exit;status=$handoff.status}
    Assert-Test ($handoff.status.phase -eq 'succeeded') ('Actual handoff failed: '+$handoff.status.phase+' '+$handoff.status.failure)
    Healthy-Test $script:newGeneration|Out-Null
    if(-not $CandidateDirectory){
        $workerStatus=Wait-Test { $path=Join-Path $script:state 'desktop/updates/status.json';if(Test-Path -LiteralPath $path){$v=Read-PocketRepairJson $path;if($v.failure -eq 'update_trust_unavailable'){return $v}} }
        Record-Test 'actual current supervisor ran update maintenance with no UI process' @{phase=$workerStatus.phase;failure=$workerStatus.failure;uiProcesses=@((Group-Test)|Where-Object{$_.name -eq 'pocket-desktop.exe'}).Count}
    }
    $assistant=Invoke-PocketInstallControl $script:state 'assistant/status'
    Assert-Test ($null -ne $assistant.PSObject.Properties['runtimeReady']) 'Actual assistant status transport failed'
    Record-Test 'installer assistant status uses actual authenticated helper transport' @{runtimeReady=$assistant.runtimeReady;generation=$script:newGeneration}
    foreach($p in $native){Assert-Test (Alive-Test $p) 'Handoff disrupted native identity'}
    foreach($kv in $keep.GetEnumerator()){Assert-Test ((Get-PocketFileHash $kv.Key) -eq $kv.Value) 'Handoff changed retained identity/config bytes'}
    Record-Test 'idle helper switch preserved exact native identities and state bytes' @{native=$native;retained=$keep}
    # Replay the durable post-switch state as an interrupted rollback request;
    # actual coordinator recovery, not a fake backend or mock startup result.
    $j=Join-Path $script:state 'desktop/upgrade.json';$interrupted=Read-PocketRepairJson $j;$interrupted.phase='rolling_back';Json-Test $j $interrupted
    $recovery=Run-CoordinatorTest $operation
    Assert-Test ($recovery.status.phase -eq 'rolled_back') 'Interrupted rollback failed'
    Healthy-Test $script:oldGeneration|Out-Null
    foreach($p in $native){Assert-Test (Alive-Test $p) 'Rollback disrupted native identity'}
    foreach($kv in $keep.GetEnumerator()){Assert-Test ((Get-PocketFileHash $kv.Key) -eq $kv.Value) 'Rollback changed retained bytes'}
    Record-Test 'interrupted coordinator recovered through actual rollback' @{exit=$recovery.exit;status=$recovery.status;native=$native}
    Stop-DesktopTest
    # A second isolated operation with a missing immutable target file must
    # fail before release. The current old owner must remain healthy/alive.
    $before=Group-Test
    foreach($n in @('upgrade.json','upgrade-local.json','maintenance.json','maintenance-request.json')){$p=Join-Path $script:state ('desktop/'+$n);if(Test-Path -LiteralPath $p){$archive=$p+'.completed';[IO.File]::Copy($p,$archive,$false);Remove-Item -LiteralPath (Scope-Test $p)}}
    $missing=Join-Path $script:install ('bin/'+$script:newGeneration+'/pocket-bridge.exe');$bytes=[IO.File]::ReadAllBytes($missing);Remove-Item -LiteralPath (Scope-Test $missing)
    $rejected=$false;try{Queue-Test $script:oldGeneration|Out-Null}catch{$rejected=$true}
    Assert-Test $rejected 'Missing candidate inventory accepted'
    foreach($p in $before){Assert-Test (Alive-Test $p) 'Missing-file validation stopped old owner'}
    [IO.File]::WriteAllBytes($missing,$bytes);Healthy-Test $script:oldGeneration|Out-Null
    Record-Test 'missing target file rejected before releasing actual old owner' @{old=$before;restoredHash=(Get-PocketFileHash $missing)}
    $script:success=$true
}catch{$script:errors.Add($_.Exception.Message);Write-Output ('FAIL '+$_.Exception.Message);throw}finally{
    try{
        if(Test-Path -LiteralPath $script:install){
            # Cleanup remains possible if WMI itself failed. Capture each actual
            # image through the same current-user kernel identity API.
            foreach($p in Get-Process){try{if($p.Path -and $p.Path.StartsWith($script:root+'\',[StringComparison]::OrdinalIgnoreCase)){Capture-Test (Get-PocketInstallProcessIdentity $p.Id)}}catch{}}
        }
        foreach($p in @($script:captured.Values|Sort-Object @{Expression={if($_.name -eq 'pocket-supervisor.exe'){0}elseif($_.name -eq 'Codex.exe'){1}else{2}}})){
            if(Alive-Test $p){Scope-Test $p.executable|Out-Null;Assert-Test ((Get-PocketFileHash $p.executable) -eq $p.sha256) 'Cleanup image changed';Stop-PocketInstallProcess $p}
        }
        foreach($p in $script:captured.Values){Assert-Test (-not (Alive-Test $p)) 'Fixture cleanup left process alive'}
        Assert-PocketInstallLocksFree $script:install $script:state
        Json-Test (Join-Path $script:root 'acceptance-result.json') @{success=$script:success;cases=$script:cases.ToArray();errors=$script:errors.ToArray();captured=@($script:captured.Values);cleanup='exact fixture identities exited; locks released';installerHash=(Get-PocketFileHash (Join-Path $PSScriptRoot 'windows-install.ps1'))}
    }catch{Write-Output ('CLEANUP FAILURE '+$_.Exception.Message);throw}
    Write-Output ('Evidence retained: '+$script:root)
}
