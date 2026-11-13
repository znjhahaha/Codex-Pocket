#requires -Version 5.1
[CmdletBinding()]
param([switch]$InstallerRegression,[string]$TestName)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'windows-install.ps1')
if($InstallerRegression){
    # Run existing tests unchanged on disk, with only external COM boundaries
    # substituted in memory. Temporary ShellLink writes also use no COM.
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Text;
using System.Security.Cryptography;
public static class PocketShellLink {
 public static void Write(string path,string target,string mode,string icon) { File.WriteAllText(path,target+"|"+mode+"|"+icon+"|0",new UTF8Encoding(false)); }
 public static string[] Read(string path) { var value=File.ReadAllText(path).Split('|'); if(value.Length!=4) throw new InvalidOperationException("fixture invalid link"); return value; }
 public static void DeleteIfHash(string path,string expected) {
  string actual;using(var file=File.Open(path,FileMode.Open,FileAccess.Read,FileShare.Read)){using(var hash=SHA256.Create()){actual=BitConverter.ToString(hash.ComputeHash(file)).Replace("-","").ToLowerInvariant();}}
  if(actual!=expected)throw new InvalidOperationException("fixture shortcut drift");File.Delete(path);
 }
}
'@
    $source=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'windows-installer-tests.ps1'))
    $source=$source.Replace('param()',('param()'+[Environment]::NewLine+'$PSScriptRoot='+"'"+$PSScriptRoot.Replace("'","''")+"'"))
    $boundary=@'
function Test-PocketTaskEquivalent([AllowNull()]$Left,[AllowNull()]$Right) { return $Left -ceq $Right }
function New-Object {
 [CmdletBinding()]param([Parameter(Position=0)][string]$TypeName,[string]$ComObject,[object[]]$ArgumentList)
 if($ComObject){
  if($ComObject -ne 'WScript.Shell'){throw 'FORBIDDEN real COM in installer regression'}
  $shell=[pscustomobject]@{}
  $shell|Add-Member -MemberType ScriptMethod -Name CreateShortcut -Value {param($Path);$parts=[PocketShellLink]::Read($Path);return [pscustomobject]@{TargetPath=$parts[0];Arguments=$parts[1];IconLocation=$parts[2]}}
  return $shell
 }
 return Microsoft.PowerShell.Utility\New-Object @PSBoundParameters
}
'@
    $anchor='$script:realShortcutBytes='+[char]36+'{function:New-PocketShortcutBytes}'
    if(-not $source.Contains($anchor)){throw 'Installer mock anchor changed; refuse unsafe regression'}
    $source=$source.Replace($anchor,($anchor+[Environment]::NewLine+$boundary))
    . ([scriptblock]::Create($source))
    exit 0
}
$testRoot=[IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('pocket-repair-'+[guid]::NewGuid().ToString('N'))))
[void][IO.Directory]::CreateDirectory($testRoot)
$script:passed=0
function Assert($Condition,[string]$Message) { if(-not $Condition){throw $Message} }
function Test([string]$Name,[scriptblock]$Body) { if($TestName -and $TestName -cne $Name){return}; & $Body; $script:passed++; Write-Output ('PASS '+$Name) }
function Put([string]$Path,[string]$Text) { [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path));[IO.File]::WriteAllText($Path,$Text,[Text.UTF8Encoding]::new($false)) }
function Get-PocketUserCliPath { return $script:cli }
function Get-PocketUserCliKind { return 'String' }
function Set-PocketUserCliPath { throw 'FORBIDDEN environment mutation' }
function Send-PocketEnvironmentChanged { throw 'FORBIDDEN environment broadcast' }
function Initialize-PocketPrivateState { throw 'FORBIDDEN identity initialization' }
function Copy-PocketState { throw 'FORBIDDEN private state migration' }
function Get-PocketDesktopPackage { throw 'FORBIDDEN native runtime discovery' }
function Start-PocketTask { throw 'FORBIDDEN task start' }
function Assert-PocketInitializedState([string]$Directory) {
    if($Directory -ne $script:state -or -not [IO.Directory]::Exists($Directory)){throw 'fixture invalid state'}
}
function Get-PocketTaskXml {
    $script:taskReads++
    if($script:taskUnknown){Invoke-PocketInstallStep 'startup_read' 'ITaskFolder.GetTask' {throw [Runtime.InteropServices.COMException]::new('fixture-private-read',-2147024891)}}
    return $script:task
}
function Set-PocketTaskXml([AllowNull()]$Xml) {
    $script:taskWrites++;$script:task=$Xml
    if($script:taskFail){$script:taskFail=$false;$script:taskUnknown=$true;Invoke-PocketInstallStep 'startup_register' 'ITaskFolder.RegisterTask' {throw [Runtime.InteropServices.COMException]::new('fixture-private-unknown',-2147467259)}}
}
function Test-PocketTaskEquivalent([AllowNull()]$Left,[AllowNull()]$Right) { return $Left -ceq $Right }
function New-PocketShortcutBytes([string]$Temporary,[string]$Launcher,[string]$Mode,[string]$IconPath) {
    $script:linkWrites++
    if($script:linkFail){Invoke-PocketInstallStep 'shortcut' 'IPersistFile.Save' {throw [Runtime.InteropServices.COMException]::new('fixture-private-link',-2147467259)}}
    return ,([Text.Encoding]::UTF8.GetBytes(($Launcher+'|'+$Mode+'|'+$IconPath)))
}
function Test-PocketShortcutTarget([string]$Path,[string]$Launcher,[string]$Mode) { return [IO.File]::Exists($Path) -and ([IO.File]::ReadAllText($Path)).StartsWith($Launcher+'|'+$Mode+'|') }
$script:realWriteBackup=${function:Write-PocketBackup}
function Write-PocketBackup($Backup,[AllowNull()]$ExpectedHash,[switch]$Compare) {
    if($null -eq $Backup.bytes){
        Assert-PocketBackup $Backup
        if($Compare -and (Get-PocketFileHash $Backup.path) -ne $ExpectedHash){throw 'fixture drift'}
        if([IO.File]::Exists($Backup.path)){[IO.File]::Delete($Backup.path)}
    }else{ & $script:realWriteBackup @PSBoundParameters }
}
function New-Fixture {
    $root=Join-Path $testRoot ([guid]::NewGuid().ToString('N'))
    $context=@{InstallRoot=(Join-Path $root 'installed');Programs=(Join-Path $root 'programs');Startup=(Join-Path $root 'startup')}
    foreach($p in $context.Values){[void][IO.Directory]::CreateDirectory($p)}
    $source=Join-Path $root 'payload'
    foreach($name in Get-PocketWindowsFiles){Put (Join-Path $source $name) ($name+' fixture never execute')}
    Write-PocketJson (Join-Path $source 'windows-manifest.json') @{schema=1;version='0.3.0';files=(Get-PocketInventory $source (Get-PocketWindowsFiles))}
    $generation=Get-PocketFileHash (Join-Path $source 'windows-manifest.json')
    $bin=Join-Path $context.InstallRoot ('bin\'+$generation);[void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($bin));[IO.Directory]::Move($source,$bin)
    $launcher=Join-Path $context.InstallRoot 'pocket-launcher.exe';[IO.File]::Copy((Join-Path $bin 'pocket-launcher.exe'),$launcher)
    $icon=Join-Path $context.InstallRoot ('resources\'+(Get-PocketFileHash (Join-Path $bin 'codex-pocket.ico'))+'\codex-pocket.ico')
    Put $icon ([IO.File]::ReadAllText((Join-Path $bin 'codex-pocket.ico')))
    $script:state=Join-Path $root 'private-state';[void][IO.Directory]::CreateDirectory($script:state);Put (Join-Path $script:state 'sentinel') 'fixture-private-state'
    $script:cli=$launcher;$script:task=$null;$script:taskUnknown=$false;$script:taskFail=$false;$script:taskReads=0;$script:taskWrites=0;$script:linkWrites=0;$script:linkFail=$false
    $links=@((Join-Path $context.Programs 'Codex Pocket.lnk'),(Join-Path $context.Programs 'Codex Pocket Desktop.lnk'),(Join-Path $context.Startup 'Codex Pocket.lnk'))
    $entries=@();foreach($p in $links){$entries+=@{path=$p;original=$null;originalHash=$null;installed=$null;installedHash=$null}}
    $record=@{version=3;singleEntry=$true;coreInstalled=$true;installationState='installed_incomplete';runtimeVerification='pending';installRoot=$context.InstallRoot;generation=$generation;releaseHash=$generation;bridge=$launcher;launcherHash=(Get-PocketFileHash $launcher);stateDirectory=$script:state;previousCliPath=$null;previousCliKind='String';previousTask=$null;previousTaskKnown=$true;shortcuts=$entries;rollback=$null;startupPreference='Preserve';integration=@{shortcuts=@((New-PocketPendingIntegration 'shortcut'),(New-PocketPendingIntegration 'shortcut'),(New-PocketPendingIntegration 'shortcut'));startup=(New-PocketPendingIntegration 'startup_register')}}
    $manifest=Join-Path $context.InstallRoot 'desktop-install.json';Write-PocketJson $manifest $record
    return @{Context=$context;Generation=$generation;Bin=$bin;Launcher=$launcher;Icon=$icon;Links=$links;Manifest=$manifest;Journal=(Join-Path $context.InstallRoot 'installation-pending.json');Record=$record}
}
function Repair($Fixture,[string]$Step,[string]$Id=([guid]::NewGuid().ToString('D')),[switch]$Check) {
    return Invoke-PocketIntegrationRepair -RepairStep $Step -OperationId $Id -ExpectedGeneration $Fixture.Generation -CheckRepair:$Check -Context $Fixture.Context
}
function Read-Record($Fixture) { return Get-Content -LiteralPath $Fixture.Manifest -Raw -Encoding UTF8 | ConvertFrom-Json }
function Assert-SafeResult($Result) {
    $json=$Result|ConvertTo-Json -Depth 10 -Compress
    Assert (-not $json.Contains('fixture-private') -and -not $json.Contains($testRoot) -and -not $json.Contains('base64')) 'Public result leaked private data'
    Assert ($Result.processesStarted -eq 0 -and $Result.processesStopped -eq 0 -and $Result.runtimeVerification -eq 'pending') 'Runtime claim changed'
}
Test 'repair function and closed CLI parameters exist' {
    Assert ($null -ne (Get-Command Invoke-PocketIntegrationRepair -ErrorAction SilentlyContinue)) 'Missing fixed repair protocol'
}
Test 'shortcut zero repairs only entry and same operation never replays' {
    $f=New-Fixture;$id=[guid]::NewGuid().ToString('D');$original=[IO.File]::ReadAllBytes($f.Launcher)
    $result=Repair $f shortcut_0 $id;Assert ($result.state -eq 'completed') 'Entry repair failed';Assert-SafeResult $result
    $record=Read-Record $f;Assert ($record.integration.shortcuts[0].state -eq 'completed' -and $record.integration.shortcuts[1].state -eq 'pending' -and $record.integration.startup.state -eq 'pending') 'Unselected integration changed'
    Assert ($script:linkWrites -eq 1 -and $script:taskReads -eq 0 -and $script:taskWrites -eq 0) 'Unselected COM boundary invoked'
    $hash=Get-PocketFileHash $f.Manifest;$repeat=Repair $f shortcut_0 $id
    Assert ($repeat.state -eq 'completed' -and $script:linkWrites -eq 1 -and (Get-PocketFileHash $f.Manifest) -eq $hash) 'Repeat replayed operation'
    Assert ((Get-PocketBytesHash $original) -eq (Get-PocketFileHash $f.Launcher)) 'Core launcher changed'
}
Test 'legacy cleanup restores only the selected original bytes' {
    foreach($slot in @(1,2)){
        $f=New-Fixture
        $original=[Text.Encoding]::UTF8.GetBytes('preserved custom link');$f.Record.shortcuts[$slot].original=[Convert]::ToBase64String($original);$f.Record.shortcuts[$slot].originalHash=Get-PocketBytesHash $original
        $bytes=New-PocketShortcutBytes 'unused' $f.Launcher $(if($slot -eq 1){'--pocket-desktop'}else{'--pocket-ui'}) $f.Icon
        [IO.File]::WriteAllBytes($f.Links[$slot],$bytes);$f.Record.shortcuts[$slot].installed=[Convert]::ToBase64String($bytes);$f.Record.shortcuts[$slot].installedHash=Get-PocketBytesHash $bytes
        Write-PocketJson $f.Manifest $f.Record
        $result=Repair $f ('shortcut_'+$slot);Assert ($result.state -eq 'completed') 'Legacy cleanup failed'
        Assert ((Get-PocketFileHash $f.Links[$slot]) -eq (Get-PocketBytesHash $original)) 'Original legacy link not restored'
        Assert ($script:taskReads -eq 0 -and $script:taskWrites -eq 0) 'Legacy cleanup touched startup'
        $record=Read-Record $f;Assert ($record.integration.shortcuts[0].state -eq 'pending') 'Entry was retried by cleanup'
    }
}
Test 'custom legacy links are preserved and owned links restore absence' {
    $f=New-Fixture;Put $f.Links[1] 'custom bytes';$b=Get-PocketBackup $f.Links[1]
    $f.Record.shortcuts[1].installed=$b.bytes;$f.Record.shortcuts[1].installedHash=$b.hash;Write-PocketJson $f.Manifest $f.Record
    Assert ((Repair $f shortcut_1).state -eq 'completed') 'Custom cleanup failed'
    Assert ([IO.File]::ReadAllText($f.Links[1]) -eq 'custom bytes') 'Custom link overwritten'
    $f=New-Fixture;$bytes=New-PocketShortcutBytes 'unused' $f.Launcher '--pocket-ui' $f.Icon
    [IO.File]::WriteAllBytes($f.Links[2],$bytes);$f.Record.shortcuts[2].installed=[Convert]::ToBase64String($bytes);$f.Record.shortcuts[2].installedHash=Get-PocketBytesHash $bytes;Write-PocketJson $f.Manifest $f.Record
    Assert ((Repair $f shortcut_2).state -eq 'completed' -and -not [IO.File]::Exists($f.Links[2])) 'Owned link was not removed'
}
Test 'startup honors installed preference and captures unknown originals without starting' {
    foreach($preference in @('Preserve','Enable','Disable')){
        $f=New-Fixture;$f.Record.startupPreference=$preference;$f.Record.previousTaskKnown=$false
        $f.Record.rollback=@{taskKnown=$false;task=$null;fixture='preserved rollback metadata'};Write-PocketJson $f.Manifest $f.Record
        $result=Repair $f startup;Assert ($result.state -eq 'completed') 'Startup repair failed';Assert-SafeResult $result
        Assert ((Test-PocketTaskLogon $script:task) -eq ($preference -ne 'Disable')) 'Recorded startup preference ignored'
        Assert ($script:taskWrites -eq 1 -and $script:linkWrites -eq 0) 'Startup replayed shortcut'
        $record=Read-Record $f;Assert ($record.previousTaskKnown -and $record.rollback.taskKnown -and $record.integration.shortcuts[0].state -eq 'pending') 'Original capture or isolation failed'
    }
}
Test 'completed selected steps are no-ops even if other steps remain pending' {
    $f=New-Fixture;$f.Record.integration.shortcuts[1]=@{state='completed';stage='shortcut';code='integration_completed';call=$null;hresult=$null};Write-PocketJson $f.Manifest $f.Record
    $hash=Get-PocketFileHash $f.Manifest;$r=Repair $f shortcut_1
    Assert ($r.code -eq 'integration_already_completed' -and (Get-PocketFileHash $f.Manifest) -eq $hash -and $script:linkWrites -eq 0) 'Completed slot was replayed'
}
Test 'UUID step and generation validation reject without mutation' {
    $f=New-Fixture;$hash=Get-PocketFileHash $f.Manifest
    foreach($bad in @(@{step='anything';id=[guid]::NewGuid().ToString('D');gen=$f.Generation},@{step='STARTUP';id=[guid]::NewGuid().ToString('D');gen=$f.Generation},@{step='startup';id='..\escape';gen=$f.Generation},@{step='startup';id=[guid]::NewGuid().ToString('D');gen='A'*64})){
        $r=Invoke-PocketIntegrationRepair -RepairStep $bad.step -OperationId $bad.id -ExpectedGeneration $bad.gen -Context $f.Context
        Assert ($r.code -eq 'invalid_params') 'Invalid request was accepted';Assert-SafeResult $r
    }
    $r=Invoke-PocketIntegrationRepair -RepairStep startup -OperationId ([guid]::NewGuid().ToString('D')) -ExpectedGeneration ('f'*64) -Context $f.Context
    Assert ($r.code -eq 'generation_changed' -and (Get-PocketFileHash $f.Manifest) -eq $hash -and $script:taskWrites -eq 0) 'Stale generation mutated integration'
}
Test 'same ID cannot select another step and missing check stays unknown' {
    $f=New-Fixture;$id=[guid]::NewGuid().ToString('D');Assert ((Repair $f shortcut_0 $id).state -eq 'completed') 'Fixture repair failed'
    Assert ((Repair $f startup $id).code -eq 'operation_conflict' -and $script:taskWrites -eq 0) 'Operation binding changed'
    $hash=Get-PocketFileHash $f.Manifest;$id=[guid]::NewGuid().ToString('D');$r=Repair $f startup $id -Check
    Assert ($r.state -eq 'unknown' -and (Get-PocketFileHash $f.Manifest) -eq $hash -and -not [IO.File]::Exists((Join-Path $f.Context.InstallRoot ('integration-repair-operations\'+$id+'.json')))) 'Unknown check admitted operation'
}
Test 'core manifest launcher icon and state failures are closed' {
    foreach($kind in @('launcher','manifest','icon','state','record','cli')){
        $f=New-Fixture
        switch($kind){
            launcher {Put $f.Launcher 'damaged'}
            manifest {Put (Join-Path $f.Bin 'pocket-agent.exe') 'damaged'}
            icon {Put $f.Icon 'damaged'}
            state {$f.Record.stateDirectory=Join-Path $testRoot 'absent';Write-PocketJson $f.Manifest $f.Record}
            record {$f.Record.integration.shortcuts[0].state='success';Write-PocketJson $f.Manifest $f.Record}
            cli {$script:cli='fixture-private-foreign-command'}
        }
        $r=Repair $f shortcut_0;Assert ($r.state -eq 'failed' -and $script:linkWrites -eq 0 -and $script:taskWrites -eq 0) ('Unverified '+$kind+' authorized mutation');Assert-SafeResult $r
    }
}
Test 'selected and unselected shortcut drift refuse all publication' {
    foreach($slot in @(0,1,2)){
        $f=New-Fixture;Put $f.Links[$slot] 'external edit';$hash=Get-PocketFileHash $f.Manifest
        $r=Repair $f shortcut_0;Assert ($r.code -eq 'integration_drift' -and $script:linkWrites -eq 0 -and (Get-PocketFileHash $f.Manifest) -eq $hash) 'Shortcut drift overwritten'
    }
}
Test 'foreign and unknown startup stay truthful and never mutate' {
    $f=New-Fixture;$script:task=New-PocketTaskXml $f.Launcher;$script:task=$script:task.Replace('--pocket-supervise','--foreign')
    $r=Repair $f startup;Assert ($r.state -eq 'failed' -and $r.stage -eq 'startup_ownership' -and $script:taskWrites -eq 0) 'Foreign startup was overwritten'
    $f=New-Fixture;$script:taskUnknown=$true;$id=[guid]::NewGuid().ToString('D');$r=Repair $f startup $id
    Assert ($r.state -eq 'failed' -and $r.stage -eq 'startup_read' -and $r.call -eq 'ITaskFolder.GetTask' -and $r.hresult -eq '0x80070005' -and $script:taskWrites -eq 0) 'Unknown startup lost HRESULT'
    $script:taskUnknown=$false;Assert ((Repair $f startup $id).state -eq 'failed' -and $script:taskWrites -eq 0) 'Failed ID replayed automatically'
}
Test 'shortcut failure keeps COM stage HRESULT and same ID failure is durable' {
    $f=New-Fixture;$script:linkFail=$true;$id=[guid]::NewGuid().ToString('D');$hash=Get-PocketFileHash $f.Manifest;$r=Repair $f shortcut_0 $id
    Assert ($r.state -eq 'failed' -and $r.stage -eq 'shortcut' -and $r.call -eq 'IPersistFile.Save' -and $r.hresult -eq '0x80004005') 'Shortcut error attribution lost'
    Assert ((Get-PocketFileHash $f.Manifest) -eq $hash) 'Failed COM changed record';Assert-SafeResult $r
    $script:linkFail=$false;Assert ((Repair $f shortcut_0 $id).state -eq 'failed' -and $script:linkWrites -eq 1) 'Failed operation was replayed'
}
Test 'ambiguous startup mutation requires targeted recovery before a fresh ID' {
    $f=New-Fixture;$script:taskFail=$true;$id=[guid]::NewGuid().ToString('D');$r=Repair $f startup $id
    Assert ($r.state -eq 'recovery-needed' -and $r.hresult -eq '0x80004005' -and [IO.File]::Exists($f.Journal)) 'Unknown mutation did not retain journal'
    Assert ((Repair $f shortcut_0).state -eq 'recovery-needed' -and $script:linkWrites -eq 0) 'Different slot recovered task'
    $script:taskUnknown=$false;$r=Repair $f startup $id -Check
    Assert ($r.state -eq 'failed' -and $r.hresult -eq '0x80004005' -and $r.stage -eq 'startup_register' -and $null -eq $script:task -and -not [IO.File]::Exists($f.Journal)) 'Check did not restore original task or retain original failure'
    $writes=$script:taskWrites;Assert ((Repair $f startup $id).state -eq 'failed' -and $script:taskWrites -eq $writes) 'Recovered ID replayed'
    Assert ((Repair $f startup).state -eq 'completed') 'Fresh operation could not repair'
}
Test 'untargeted and malformed legacy journals remain recovery-needed' {
    foreach($kind in @('malformed','core','multiple','path')){
        $f=New-Fixture;$snapshot=Get-PocketIntegrationSnapshot $f.Manifest $f.Links -SkipTask
        $after=$snapshot|ConvertTo-Json -Depth 30|ConvertFrom-Json
        $journal=@{before=$snapshot;after=$after;optionalIntegration=$true;taskManaged=$false}
        switch($kind){
            malformed {Put $f.Journal 'fixture-private-invalid'}
            core {$journal.optionalIntegration=$false;Write-PocketJson $f.Journal $journal}
            multiple {$journal.after.links[1].hash='f'*64;Write-PocketJson $f.Journal $journal}
            path {$journal.after.manifest.path=Join-Path $testRoot 'foreign.json';Write-PocketJson $f.Journal $journal}
        }
        $hash=Get-PocketFileHash $f.Manifest;$r=Repair $f shortcut_0
        Assert ($r.state -eq 'recovery-needed' -and [IO.File]::Exists($f.Journal) -and (Get-PocketFileHash $f.Manifest) -eq $hash -and $script:linkWrites -eq 0) 'Unsafe journal recovered';Assert-SafeResult $r
    }
}
Test 'interrupted prepared and running operations reconcile without replay' {
    foreach($kind in @('running','prepared')){
        $f=New-Fixture;$id=[guid]::NewGuid().ToString('D');Assert ((Repair $f shortcut_0 $id).state -eq 'completed') 'Fixture repair failed'
        $path=Join-Path $f.Context.InstallRoot ('integration-repair-operations\'+$id+'.json');$op=Read-PocketRepairJson $path
        $op.phase=$kind;$op.result=$null
        if($kind -eq 'running'){$op.beforeHash=Get-PocketFileHash $f.Manifest}
        Write-PocketJson $path $op;$writes=$script:linkWrites;$r=Repair $f shortcut_0 $id -Check
        Assert ($r.state -eq $(if($kind -eq 'prepared'){'completed'}else{'failed'}) -and $script:linkWrites -eq $writes) 'Interrupted operation replayed'
    }
}
Test 'saved result metadata is allowlisted before returning' {
    $f=New-Fixture;$id=[guid]::NewGuid().ToString('D');Repair $f shortcut_0 $id|Out-Null
    $path=Join-Path $f.Context.InstallRoot ('integration-repair-operations\'+$id+'.json');$op=Read-PocketRepairJson $path
    $op.result.call='fixture-private-command';$op.result.stage='fixture-private-path';$op.result.hresult='0x80070005 secret';$op.result|Add-Member -NotePropertyName private -NotePropertyValue 'fixture-private-backup'
    Write-PocketJson $path $op;$r=Repair $f shortcut_0 $id
    Assert-SafeResult $r;Assert ($r.call -eq 'Invoke-PocketIntegrationRepair' -and $null -eq $r.hresult) 'Saved metadata escaped allowlist'
}
Test 'repair CLI rejects full installer switches with one sanitized JSON line' {
    $scriptPath=Join-Path $PSScriptRoot 'windows-install.ps1';$id=[guid]::NewGuid().ToString('D')
    $output=@(& powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath -RepairStep startup -OperationId $id -ExpectedGeneration ('f'*64) -Apply)
    Assert ($LASTEXITCODE -eq 1 -and $output.Count -eq 1) 'Repair CLI output/exit contract violated'
    $r=$output[0]|ConvertFrom-Json;Assert ($r.code -eq 'invalid_params') 'Repair fell through full installer';Assert-SafeResult $r
}
Test 'shared installer mutex refuses another thread without mutation' {
    $f=New-Fixture;$name='Local\CodexPocketInstall_'+(Get-PocketBytesHash ([Text.Encoding]::UTF8.GetBytes($f.Context.InstallRoot.ToLowerInvariant())))
    $ready=[Threading.ManualResetEventSlim]::new($false);$release=[Threading.ManualResetEventSlim]::new($false)
    $worker=[powershell]::Create()
    [void]$worker.AddScript({param($Name,$Ready,$Release);$m=[Threading.Mutex]::new($false,$Name);$held=$m.WaitOne(0);$Ready.Set();try{if($held){[void]$Release.Wait(10000)}}finally{if($held){$m.ReleaseMutex()};$m.Dispose()}}).AddArgument($name).AddArgument($ready).AddArgument($release)
    $pending=$worker.BeginInvoke()
    try{
        Assert ($ready.Wait(5000)) 'Mutex fixture did not start'
        $hash=Get-PocketFileHash $f.Manifest;$r=Repair $f shortcut_0
        Assert ($r.state -eq 'busy' -and $r.exitCode -eq 4 -and (Get-PocketFileHash $f.Manifest) -eq $hash -and $script:linkWrites -eq 0) 'Busy repair mutated integration'
    }finally{$release.Set();$worker.EndInvoke($pending)|Out-Null;$worker.Dispose();$ready.Dispose();$release.Dispose()}
}
Test 'physical path mismatch reparse and directory link refuse repair' {
    $f=New-Fixture;$original=(Get-Item Function:Get-PocketPhysicalPath).ScriptBlock;$script:redirectedFixture=$f.Context.InstallRoot
    function Get-PocketPhysicalPath([string]$Path) { if($Path -eq $script:redirectedFixture){return $Path+'-redirected'}; & $original $Path }
    try{$r=Repair $f shortcut_0;Assert ($r.code -eq 'path_unverified' -and $script:linkWrites -eq 0) 'Physical redirect was accepted'}finally{Set-Item Function:Get-PocketPhysicalPath $original}
    $f=New-Fixture;[void][IO.Directory]::CreateDirectory($f.Links[0]);$r=Repair $f shortcut_0
    Assert ($r.state -eq 'failed' -and $script:linkWrites -eq 0) 'Shortcut directory accepted'
    $f=New-Fixture;$external=Join-Path $testRoot 'junction-target';[void][IO.Directory]::CreateDirectory($external)
    New-Item -ItemType Junction -Path $f.Links[0] -Target $external|Out-Null
    $r=Repair $f shortcut_0;Assert ($r.state -eq 'failed' -and $script:linkWrites -eq 0) 'Reparse link accepted'
}
Test 'late unselected drift never publishes that integration or selected bytes' {
    $f=New-Fixture;$real=(Get-Item Function:New-PocketShortcutBytes).ScriptBlock;$script:lateDriftFixture=$f
    function New-PocketShortcutBytes([string]$Temporary,[string]$Launcher,[string]$Mode,[string]$IconPath) {
        Put $script:lateDriftFixture.Links[1] 'late external drift'
        & $real @PSBoundParameters
    }
    try{
        $hash=Get-PocketFileHash $f.Manifest;$r=Repair $f shortcut_0
        Assert ($r.state -eq 'failed' -and [IO.File]::ReadAllText($f.Links[1]) -eq 'late external drift' -and -not [IO.File]::Exists($f.Links[0]) -and (Get-PocketFileHash $f.Manifest) -eq $hash) 'Late unselected drift overwritten'
    }finally{Set-Item Function:New-PocketShortcutBytes $real}
}
Test 'scoped existing optional journal recovers only its selected slot' {
    $f=New-Fixture;$id=[guid]::NewGuid().ToString('D');$before=Get-PocketIntegrationSnapshot $f.Manifest $f.Links -SkipTask
    $candidate=$f.Record|ConvertTo-Json -Depth 30|ConvertFrom-Json
    $bytes=New-PocketShortcutBytes 'unused' $f.Launcher '--pocket-ui' $f.Icon
    $backup=@{path=$f.Links[0];bytes=[Convert]::ToBase64String($bytes);hash=(Get-PocketBytesHash $bytes)}
    $candidate.shortcuts[0].installed=$backup.bytes;$candidate.shortcuts[0].installedHash=$backup.hash
    $candidate.integration.shortcuts[0]=@{state='completed';stage='shortcut';code='integration_completed';call=$null;hresult=$null};Update-PocketInstallState $candidate
    $after=@{manifest=(Get-PocketRecordBackup $candidate $f.Manifest);links=@($before.links);cli=$before.cli;cliKind=$before.cliKind;task=$null;taskKnown=$false};$after.links[0]=$backup
    Write-PocketJson $f.Journal @{before=$before;after=$after;optionalIntegration=$true;taskManaged=$false}
    [IO.File]::WriteAllBytes($f.Links[0],$bytes)
    $writes=$script:linkWrites;$r=Repair $f shortcut_0 $id
    Assert ($r.state -eq 'failed' -and -not [IO.File]::Exists($f.Journal) -and $script:taskReads -eq 0 -and $script:linkWrites -eq $writes -and -not [IO.File]::Exists($f.Links[0])) 'Safe legacy optional journal could not be targeted'
    Assert ((Repair $f shortcut_0 $id -Check).state -eq 'failed' -and $script:linkWrites -eq $writes) 'Legacy recovery was not durable'
    Assert ((Repair $f shortcut_0).state -eq 'completed') 'Fresh ID did not repair after legacy recovery'
}
Test 'unknown check with legacy journal does not recover or admit' {
    $f=New-Fixture;$before=Get-PocketIntegrationSnapshot $f.Manifest $f.Links -SkipTask
    Write-PocketJson $f.Journal @{before=$before;after=$before;optionalIntegration=$true;taskManaged=$false}
    $hash=Get-PocketFileHash $f.Journal;$r=Repair $f shortcut_0 -Check
    Assert ($r.state -eq 'recovery-needed' -and (Get-PocketFileHash $f.Journal) -eq $hash -and $script:linkWrites -eq 0) 'Unknown check authorized recovery'
}
Test 'lost final operation record reconciles committed publication without replay' {
    $f=New-Fixture;$id=[guid]::NewGuid().ToString('D');$real=(Get-Item Function:Write-PocketRepairDurableJson).ScriptBlock;$script:loseFinal=$true
    function Write-PocketRepairDurableJson([string]$Path,$Value){
        if($script:loseFinal -and (Get-PocketProperty $Value 'phase' '') -eq 'terminal'){$script:loseFinal=$false;throw 'fixture-private-final-record-failure'}
        & $real $Path $Value
    }
    try{
        $r=Repair $f shortcut_0 $id
        Assert ($r.state -eq 'recovery-needed' -and $r.code -eq 'operation_record_failed' -and $r.recoveryState -eq 'unknown') 'Lost final result was claimed failed or complete'
        $writes=$script:linkWrites;$r=Repair $f shortcut_0 $id -Check
        Assert ($r.state -eq 'completed' -and $script:linkWrites -eq $writes) 'Committed publication was replayed'
    }finally{Set-Item Function:Write-PocketRepairDurableJson $real}
}
Test 'operation admission persistence failure prevents integration mutation' {
    $f=New-Fixture;$real=(Get-Item Function:Write-PocketRepairDurableJson).ScriptBlock
    function Write-PocketRepairDurableJson {throw 'fixture-private-admission-failure'}
    try{$r=Repair $f startup;Assert ($r.code -eq 'operation_record_failed' -and $r.state -eq 'recovery-needed' -and $script:taskWrites -eq 0 -and $script:linkWrites -eq 0) 'Admission failed after mutation'}finally{Set-Item Function:Write-PocketRepairDurableJson $real}
}
Test 'record publication failure rolls back only selected bytes with exact stage' {
    $f=New-Fixture;$hash=Get-PocketFileHash $f.Manifest;$real=(Get-Item Function:Write-PocketBackup).ScriptBlock;$script:failRecord=$f.Manifest
    function Write-PocketBackup($Backup,[AllowNull()]$ExpectedHash,[switch]$Compare){
        if($Backup.path -eq $script:failRecord -and $null -ne $Backup.bytes){$script:failRecord=$null;throw [Runtime.InteropServices.COMException]::new('fixture-private-record-failure',-2147024891)}
        & $real @PSBoundParameters
    }
    try{
        $r=Repair $f shortcut_0
        Assert ($r.state -eq 'failed' -and $r.stage -eq 'installation_publish' -and $r.hresult -eq '0x80070005' -and (Get-PocketFileHash $f.Manifest) -eq $hash -and -not [IO.File]::Exists($f.Links[0]) -and $script:taskWrites -eq 0) 'Scoped metadata rollback failed'
    }finally{Set-Item Function:Write-PocketBackup $real}
}
Test 'repair preserves every payload resource private sentinel and identity field' {
    $f=New-Fixture;$hashes=@{}
    foreach($path in @($f.Launcher,$f.Icon,(Join-Path $script:state 'sentinel'))+@((Get-PocketWindowsFiles)|ForEach-Object{Join-Path $f.Bin $_})){ $hashes[$path]=Get-PocketFileHash $path }
    $identity=@{generation=$f.Record.generation;stateDirectory=$f.Record.stateDirectory;previousCliPath=$f.Record.previousCliPath;launcherHash=$f.Record.launcherHash;rollback=$f.Record.rollback}
    foreach($step in @('shortcut_0','shortcut_1','shortcut_2','startup')){Assert ((Repair $f $step).state -eq 'completed') ('Repair failed '+$step)}
    foreach($path in $hashes.Keys){Assert ((Get-PocketFileHash $path) -eq $hashes[$path]) 'Repair changed a core resource or private state'}
    $record=Read-Record $f;foreach($key in $identity.Keys){
        $actual=Get-PocketProperty $record $key $null
        Assert (Test-PocketRepairValueEqual $identity[$key] $actual) ('Repair reinitialized installed identity: '+$key)
    }
    Assert ($record.installationState -eq 'installed' -and $record.runtimeVerification -eq 'pending') 'All repaired steps became runtime acceptance'
}
Write-Output ('PASS '+$script:passed+' fixed integration repair fixture tests')
