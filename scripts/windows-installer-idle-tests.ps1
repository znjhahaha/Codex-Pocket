#requires -Version 5.1
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'windows-install.ps1')
$now=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$healthy=@{protocol=1;updatedAt=$now;acceptingWrites=$true;remoteControlProtocol=1;remoteEnabled=$false;lifecycle=@{observedAt=$now;idle=$true;nativeTasksPreserved=$true;activeTurns=0;pendingQuestions=0;pendingWrites=0;unknownOperations=0}}
if(-not (Test-PocketInstallerIdle $healthy $now)){throw 'Complete fresh idle evidence rejected'}
foreach($field in @('activeTurns','pendingQuestions','pendingWrites','unknownOperations')) {
    $healthy.lifecycle[$field]=1
    if(Test-PocketInstallerIdle $healthy $now){throw ('Unsafe shutdown allowed with '+$field)}
    $healthy.lifecycle[$field]=0
}
$healthy.lifecycle.Remove('activeTurns')
if(Test-PocketInstallerIdle $healthy $now){throw 'Missing native count accepted'}
$healthy.lifecycle.activeTurns=0;$healthy.lifecycle.nativeTasksPreserved=$false
if(Test-PocketInstallerIdle $healthy $now){throw 'Unverified process lifecycle accepted'}
$healthy.lifecycle.nativeTasksPreserved=$true
if(Test-PocketInstallerIdle $healthy ($now+11)){throw 'Stale idle snapshot accepted'}
$healthy.remoteEnabled=$true
if(Test-PocketInstallerIdle $healthy $now){throw 'Unfenced phone ingress allowed before process shutdown'}
if(Test-PocketInstallerIdle @{protocol=1;helperVersion='0.6.6';acceptingWrites=$true;updatedAt=$now} $now){throw 'Legacy 0.6.6 without idle evidence allowed shutdown'}
Write-Output 'PASS active turns, pending questions/writes, unknown operations, missing/stale/unverified lifecycle, active ingress and legacy 0.6.6 all defer'

# Exercise the real Enter lifecycle with only synthetic identities and local
# disposable JSON. None of these PIDs is passed to a real process API.
$script:fixtureParent=Join-Path ([IO.Path]::GetTempPath()) ('pocket-idle-installer-'+[Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($script:fixtureParent)
function Get-PocketInstallGroup {return @($script:fixtureGroup)}
function Get-PocketInstallShadowLauncher {return $null}
function Invoke-PocketInstallControl {return $script:fixtureHealth}
function Get-PocketAuthenticatedInstallerAgent {return $null}
function Stop-PocketInstallProcess {$script:fixtureStopped++}
function Cancel-PocketInstallerHandoff {$script:fixtureCancelled++}
function New-IdleCase($Health,[string]$Kind='pocket-agent.exe') {
    $script:fixtureStopped=0;$script:fixtureCancelled=0;$script:fixtureHealth=$Health
    $root=Join-Path $script:fixtureParent ([Guid]::NewGuid().ToString('N'));$state=Join-Path $root 'state'
    [void][IO.Directory]::CreateDirectory($state);Write-PocketJson (Join-Path $state 'config.json') @{computerId='fixture'}
    $script:fixtureGroup=@(@{pid=424242;name=$Kind;createdAt=123;executable=(Join-Path $root $Kind);sha256=('a'*64)})
    $r=Enter-PocketInstallLifecycle $root $state $null
    if($r.phase -ne 'waiting_idle' -or $r.processesStopped -ne 0 -or $script:fixtureStopped -ne 0 -or $script:fixtureCancelled -ne 0){throw 'Deferred installation touched a process or handoff'}
    if(-not $r.nativeTasksPreserved -or -not $r.nativeRestartRequired){throw 'Deferred installation lost its recovery explanation'}
}
foreach($field in @('activeTurns','pendingQuestions','pendingWrites','unknownOperations')) {
    $sample=@{protocol=1;computerId='fixture';updatedAt=$now;acceptingWrites=$true;remoteControlProtocol=1;remoteEnabled=$false;lifecycle=@{observedAt=$now;idle=$true;nativeTasksPreserved=$true;activeTurns=0;pendingQuestions=0;pendingWrites=0;unknownOperations=0}}
    $sample.lifecycle[$field]=1;New-IdleCase $sample
}
New-IdleCase @{protocol=1;computerId='fixture';helperVersion='0.6.6';acceptingWrites=$true;updatedAt=$now}
$idle=@{protocol=1;computerId='fixture';updatedAt=$now;acceptingWrites=$true;remoteControlProtocol=1;remoteEnabled=$false;lifecycle=@{observedAt=$now;idle=$true;nativeTasksPreserved=$true;activeTurns=0;pendingQuestions=0;pendingWrites=0;unknownOperations=0}}
foreach($kind in @('associated-native','associated-desktop','pocket-bridge.exe','pocket-runtime-host.exe')){New-IdleCase $idle $kind}
Write-Output ('PASS real installer lifecycle performed zero shutdown/cancellation calls for active/pending/unknown/legacy and native groups; retained fixtures: '+$script:fixtureParent)
